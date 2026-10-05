package com.mymusiccurator.data.remote

import com.mymusiccurator.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NamedScore(val name: String, val score: Double, val url: String? = null)

data class LastFmTrackInfo(val album: String?, val durationMs: Long?, val tags: List<String>)

class ApiException(message: String) : IOException(message)

object Network {
    private const val USER_AGENT = "MyMusicCurator/1.0 (Android; https://github.com/woosuwar/MyMusicCurator)"

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val lastFm: LastFmApi by lazy { retrofit("https://ws.audioscrobbler.com/").create(LastFmApi::class.java) }
    val musicBrainz: MusicBrainzApi by lazy { retrofit("https://musicbrainz.org/").create(MusicBrainzApi::class.java) }
    val getSongBpm: GetSongBpmApi by lazy { retrofit("https://api.getsong.co/").create(GetSongBpmApi::class.java) }
}

// ------------------------------------------------------------------ JSON helpers

/** Last.fm 은 항목이 1개면 배열 대신 객체를 준다. 둘 다 리스트로 만든다. */
private fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> listOf(this)
    else -> emptyList()
}

private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

// ------------------------------------------------------------------ Last.fm

class LastFmClient(
    private val api: LastFmApi = Network.lastFm,
    private val apiKey: String = BuildConfig.LASTFM_API_KEY,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private suspend fun call(
        method: String,
        artist: String? = null,
        track: String? = null,
        tag: String? = null,
        limit: Int? = null,
    ): JsonObject? {
        if (!isConfigured) return null
        val res = try {
            api.call(method, apiKey, artist, track, tag, limit)
        } catch (e: retrofit2.HttpException) {
            // Last.fm 은 오류도 JSON 본문({"error": n, "message": ...})으로 준다
            e.response()?.errorBody()?.string()
                ?.let { runCatching { Network.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: throw IOException("Last.fm HTTP ${e.code()}", e)
        }
        res["error"]?.let { err ->
            val code = (err as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            // 6 = 결과 없음, 그 외(키 오류, 레이트리밋 등)는 예외
            if (code == 6) return null
            throw ApiException("Last.fm error $code: ${res.str("message")}")
        }
        return res
    }

    suspend fun trackInfo(artist: String, title: String): LastFmTrackInfo? {
        val track = call("track.getInfo", artist = artist, track = title)?.get("track") ?: return null
        val tags = track.obj("toptags").obj("tag").asObjectList().mapNotNull { it.str("name") }
        return LastFmTrackInfo(
            album = track.obj("album").str("title"),
            durationMs = track.str("duration")?.toLongOrNull()?.takeIf { it > 0 },
            tags = tags,
        )
    }

    suspend fun artistTopTags(artist: String): List<String> =
        call("artist.getTopTags", artist = artist)?.get("toptags").obj("tag").asObjectList()
            .mapNotNull { it.str("name") }

    suspend fun similarArtists(artist: String, limit: Int = 15): List<NamedScore> =
        call("artist.getSimilar", artist = artist, limit = limit)?.get("similarartists").obj("artist").asObjectList()
            .mapNotNull { a ->
                val name = a.str("name") ?: return@mapNotNull null
                NamedScore(name, a.str("match")?.toDoubleOrNull() ?: 0.0, a.str("url"))
            }

    suspend fun tagTopArtists(tag: String, limit: Int = 15): List<NamedScore> {
        val list = call("tag.getTopArtists", tag = tag, limit = limit)?.get("topartists").obj("artist").asObjectList()
        return list.mapIndexedNotNull { i, a ->
            val name = a.str("name") ?: return@mapIndexedNotNull null
            NamedScore(name, 1.0 - i.toDouble() / list.size.coerceAtLeast(1), a.str("url"))
        }
    }
}

// ------------------------------------------------------------------ MusicBrainz

class MusicBrainzClient(private val api: MusicBrainzApi = Network.musicBrainz) {
    private val mutex = Mutex()
    private var lastCallAt = 0L

    /** 가장 먼저 발매된 연도 (곡의 원 발매 시기) */
    suspend fun firstReleaseYear(artist: String, title: String): Int? {
        val q = "recording:\"${escape(title)}\" AND artist:\"${escape(artist)}\""
        val result = throttled { api.searchRecordings(q, limit = 10) }
        return result.recordings
            .filter { it.score >= 80 }
            .mapNotNull { r -> r.firstReleaseDate?.take(4)?.toIntOrNull() }
            .minOrNull()
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = mutex.withLock {
        val wait = lastCallAt + 1_100 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } catch (e: retrofit2.HttpException) {
            throw IOException("MusicBrainz HTTP ${e.code()}", e)
        } finally {
            lastCallAt = System.currentTimeMillis()
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

// ------------------------------------------------------------------ GetSongBPM

class GetSongBpmClient(
    private val api: GetSongBpmApi = Network.getSongBpm,
    private val apiKey: String = BuildConfig.GETSONGBPM_API_KEY,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun bpm(artist: String, title: String): Int? {
        if (!isConfigured) return null
        val res = try {
            api.search(apiKey, "song:$title artist:$artist")
        } catch (e: retrofit2.HttpException) {
            if (e.code() in 400..499) return null else throw IOException("GetSongBPM HTTP ${e.code()}", e)
        }
        // 결과가 없으면 "search": {"error": "no result"} 형태
        val first = res["search"].asObjectList().firstOrNull { it["tempo"] != null } ?: return null
        return first["tempo"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { Math.round(it).toInt() }
            ?.takeIf { it in 20..400 }
    }
}
