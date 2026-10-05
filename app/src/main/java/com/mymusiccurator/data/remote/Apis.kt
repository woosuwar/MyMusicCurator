package com.mymusiccurator.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Last.fm API (https://www.last.fm/api). 응답 JSON 이 경우에 따라 배열/객체/빈 문자열로
 * 바뀌는 특성이 있어 JsonObject 로 받고 [LastFmClient] 에서 안전하게 파싱한다.
 */
interface LastFmApi {
    @GET("2.0/?format=json&autocorrect=1")
    suspend fun call(
        @Query("method") method: String,
        @Query("api_key") apiKey: String,
        @Query("artist") artist: String? = null,
        @Query("track") track: String? = null,
        @Query("tag") tag: String? = null,
        @Query("limit") limit: Int? = null,
    ): JsonObject
}

/** MusicBrainz (https://musicbrainz.org/doc/MusicBrainz_API) – 키 불필요, 초당 1회 제한 */
interface MusicBrainzApi {
    @GET("ws/2/recording/?fmt=json")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int = 5,
    ): MbRecordingSearch
}

@Serializable
data class MbRecordingSearch(val recordings: List<MbRecording> = emptyList())

@Serializable
data class MbRecording(
    val title: String = "",
    val score: Int = 0,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val tags: List<MbTag> = emptyList(),
)

@Serializable
data class MbArtistCredit(val name: String = "")

@Serializable
data class MbTag(val name: String = "", val count: Int = 0)

/** GetSongBPM (https://getsongbpm.com/api) – BPM 이 파일 태그에 없을 때만 사용 (선택) */
interface GetSongBpmApi {
    @GET("search/")
    suspend fun search(
        @Query("api_key") apiKey: String,
        @Query("lookup") lookup: String,
        @Query("type") type: String = "both",
    ): JsonObject
}
