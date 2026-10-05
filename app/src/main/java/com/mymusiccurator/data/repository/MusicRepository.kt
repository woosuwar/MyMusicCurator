package com.mymusiccurator.data.repository

import androidx.room.withTransaction
import com.mymusiccurator.data.db.AppDatabase
import com.mymusiccurator.data.db.PlayEventEntity
import com.mymusiccurator.data.db.TrackEntity
import com.mymusiccurator.domain.PlayRecord
import com.mymusiccurator.domain.SourceFilter
import com.mymusiccurator.data.metadata.TagClassifier

class MusicRepository(private val db: AppDatabase) {
    private val trackDao = db.trackDao()
    private val playDao = db.playEventDao()

    val tracksWithStats = trackDao.observeTracksWithStats()
    val recommendations = db.recommendationDao().observeAll()

    fun trackWithStats(id: Long) = trackDao.observeTrackWithStats(id)
    fun history(trackId: Long) = playDao.observeHistory(trackId)
    fun playRowsSince(since: Long) = playDao.observePlayRows(since, Long.MAX_VALUE)

    /** [from, until) 구간의 재생 기록 */
    fun playRowsBetween(from: Long, until: Long) = playDao.observePlayRows(from, until)

    /** 재생 1회를 저장한다. 메타데이터 보강이 필요한 새 곡이면 true. */
    suspend fun recordPlay(record: PlayRecord): Boolean = db.withTransaction {
        val meta = record.meta
        val existing = trackDao.findByKey(meta.key)
        val track = if (existing == null) {
            val new = TrackEntity(
                trackKey = meta.key,
                title = meta.title.trim(),
                artist = meta.artist.trim(),
                album = meta.album?.takeIf { it.isNotBlank() },
                durationMs = meta.durationMs,
                genre = meta.genre?.let(TagClassifier::canonicalGenre),
                year = meta.year,
                firstSeenAt = record.startedAt,
            )
            new.copy(id = trackDao.insert(new))
        } else {
            val merged = existing.copy(
                album = existing.album ?: meta.album?.takeIf { it.isNotBlank() },
                durationMs = existing.durationMs.takeIf { it > 0 } ?: meta.durationMs,
                genre = existing.genre ?: meta.genre?.let(TagClassifier::canonicalGenre),
                year = existing.year ?: meta.year,
            )
            if (merged != existing) trackDao.update(merged)
            merged
        }
        playDao.insert(
            PlayEventEntity(
                trackId = track.id,
                startedAt = record.startedAt,
                endedAt = record.endedAt,
                listenedMs = record.listenedMs,
                completed = record.completed,
                sourcePackage = record.sourcePackage,
            ),
        )
        track.enrichedAt == null
    }

    suspend fun resetEnrichment(trackId: Long) = trackDao.resetEnrichment(trackId)

    /** 잘못 인식된 곡을 재생 기록과 함께 삭제한다 */
    suspend fun deleteTracks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(500).forEach { chunk ->
                playDao.deleteForTracks(chunk)
                trackDao.deleteByIds(chunk)
            }
        }
    }

    /** 제외 대상 앱(YouTube 등)에서 이미 쌓인 기록 수 */
    val excludedSourcePlayCount = playDao.observeCountBySource(SourceFilter.EXCLUDED_PACKAGES)

    /** 제외 대상 앱에서 쌓인 재생 기록을 지우고, 기록이 없어진 곡도 정리한다. @return 지운 재생 수 */
    suspend fun deleteExcludedSourcePlays(): Int = db.withTransaction {
        val removed = playDao.deleteBySource(SourceFilter.EXCLUDED_PACKAGES)
        trackDao.deleteOrphans()
        removed
    }
}
