package com.mymusiccurator.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 한 곡. 같은 (가수, 제목)은 trackKey 로 하나로 묶는다. */
@Entity(tableName = "tracks", indices = [Index(value = ["trackKey"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val genre: String? = null,
    val bpm: Int? = null,
    val mood: String? = null,
    /** 원 발매(작곡) 연도 */
    val year: Int? = null,
    val composer: String? = null,
    /** 외부 서비스에서 받은 태그 (쉼표 구분) */
    val tags: String? = null,
    val localUri: String? = null,
    /** 메타데이터 출처 (file, lastfm, musicbrainz, getsongbpm) */
    val metadataSource: String? = null,
    val enrichedAt: Long? = null,
    val enrichAttempts: Int = 0,
    val firstSeenAt: Long = System.currentTimeMillis(),
)

/** 재생 1회. 일정 시간 이상 들었을 때만 기록된다. */
@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index("startedAt")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
    val sourcePackage: String,
)

enum class RecommendationType { ARTIST, GENRE }

@Entity(tableName = "recommendations")
data class RecommendationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: RecommendationType,
    val name: String,
    val score: Double,
    val reason: String,
    val url: String? = null,
    val createdAt: Long,
)

/** 곡 + 누적 재생 통계 */
data class TrackWithStats(
    @Embedded val track: TrackEntity,
    val playCount: Int,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
    val totalListenedMs: Long,
)

/** 통계 계산용으로 재생 이벤트와 곡 정보를 합친 행 */
data class PlayRow(
    val trackId: Long,
    val title: String,
    val artist: String,
    val genre: String?,
    val mood: String?,
    val bpm: Int?,
    val year: Int?,
    val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

data class PlayHistoryItem(
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
    val sourcePackage: String,
)
