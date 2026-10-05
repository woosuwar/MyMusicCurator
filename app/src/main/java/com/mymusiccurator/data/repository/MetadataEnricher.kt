package com.mymusiccurator.data.repository

import android.net.Uri
import android.util.Log
import com.mymusiccurator.data.db.TrackDao
import com.mymusiccurator.data.db.TrackEntity
import com.mymusiccurator.data.metadata.LocalMetadataReader
import com.mymusiccurator.data.metadata.TagClassifier
import com.mymusiccurator.data.remote.GetSongBpmClient
import com.mymusiccurator.data.remote.LastFmClient
import com.mymusiccurator.data.remote.MusicBrainzClient
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 곡의 장르/BPM/분위기/발매연도 등을 채운다.
 * 우선순위: 로컬 파일 태그 → Last.fm 태그 → MusicBrainz(발매연도) → GetSongBPM(BPM)
 */
class MetadataEnricher(
    private val trackDao: TrackDao,
    private val local: LocalMetadataReader,
    private val lastFm: LastFmClient,
    private val musicBrainz: MusicBrainzClient,
    private val getSongBpm: GetSongBpmClient,
) {
    private val artistTagCache = mutableMapOf<String, List<String>>()

    /** @return 처리한 곡 수, 네트워크 오류가 있었는지 */
    suspend fun enrichPending(maxTracks: Int = 100): Pair<Int, Boolean> {
        var processed = 0
        var hadNetworkError = false
        var cursor = 0L
        while (processed < maxTracks) {
            val batch = trackDao.tracksNeedingEnrichment(afterId = cursor, limit = 20, maxAttempts = MAX_ATTEMPTS)
            if (batch.isEmpty()) break
            for (track in batch) {
                if (!enrich(track)) hadNetworkError = true
                cursor = track.id
                processed++
            }
        }
        return processed to hadNetworkError
    }

    /** @return 네트워크 단계까지 모두 성공했으면 true */
    suspend fun enrich(original: TrackEntity): Boolean {
        var t = original
        val sources = original.metadataSource?.split(",")?.toMutableSet() ?: mutableSetOf()
        var networkOk = true

        // 1) 로컬 파일 태그
        runCatching {
            val uri = t.localUri?.let(Uri::parse) ?: local.findLocalAudio(t.title, t.artist, t.durationMs)
            if (uri != null) {
                val tags = local.read(uri)
                t = t.copy(
                    localUri = uri.toString(),
                    album = t.album ?: tags.album,
                    genre = t.genre ?: tags.genre,
                    bpm = t.bpm ?: tags.bpm,
                    mood = t.mood ?: tags.mood,
                    year = t.year ?: tags.year,
                    composer = t.composer ?: tags.composer,
                )
                sources += "file"
            }
        }.onFailure { Log.w(TAG, "로컬 태그 읽기 실패: ${t.title}", it) }

        // 2) Last.fm 태그 → 장르/분위기
        if (lastFm.isConfigured) {
            networkOk = step("Last.fm") {
                val info = lastFm.trackInfo(t.artist, t.title)
                var tags = info?.tags.orEmpty()
                if (tags.isEmpty()) {
                    tags = artistTagCache.getOrPut(t.artist) { lastFm.artistTopTags(t.artist) }
                }
                val c = TagClassifier.classify(tags, t.artist)
                t = t.copy(
                    album = t.album ?: info?.album,
                    durationMs = t.durationMs.takeIf { it > 0 } ?: info?.durationMs ?: 0,
                    genre = t.genre ?: c.genre,
                    mood = t.mood ?: c.mood,
                    tags = c.tags.joinToString(", ").ifEmpty { t.tags },
                )
                if (tags.isNotEmpty()) sources += "lastfm"
            } && networkOk
        }

        // 3) MusicBrainz → 원 발매연도
        if (t.year == null) {
            networkOk = step("MusicBrainz") {
                musicBrainz.firstReleaseYear(t.artist, t.title)?.let {
                    t = t.copy(year = it)
                    sources += "musicbrainz"
                }
            } && networkOk
        }

        // 4) GetSongBPM → BPM
        if (t.bpm == null && getSongBpm.isConfigured) {
            networkOk = step("GetSongBPM") {
                getSongBpm.bpm(t.artist, t.title)?.let {
                    t = t.copy(bpm = it)
                    sources += "getsongbpm"
                }
            } && networkOk
        }

        trackDao.update(
            t.copy(
                metadataSource = sources.filter { it.isNotBlank() }.joinToString(",").ifEmpty { null },
                enrichedAt = if (networkOk) System.currentTimeMillis() else null,
                enrichAttempts = t.enrichAttempts + 1,
            ),
        )
        return networkOk
    }

    private suspend fun step(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "$name 요청 실패", e)
        false
    } catch (e: Exception) {
        // 파싱 오류 등은 재시도해도 같으므로 실패로 보지 않는다
        Log.w(TAG, "$name 응답 처리 실패", e)
        true
    }

    companion object {
        private const val TAG = "MetadataEnricher"
        const val MAX_ATTEMPTS = 5
    }
}
