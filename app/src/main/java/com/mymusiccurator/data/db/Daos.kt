package com.mymusiccurator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE trackKey = :key LIMIT 1")
    suspend fun findByKey(key: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE enrichedAt IS NULL AND enrichAttempts < :maxAttempts AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun tracksNeedingEnrichment(afterId: Long, limit: Int, maxAttempts: Int): List<TrackEntity>

    @Query("UPDATE tracks SET enrichedAt = NULL, enrichAttempts = 0 WHERE id = :id")
    suspend fun resetEnrichment(id: Long)

    @Query(
        """
        SELECT t.*, COUNT(p.id) AS playCount, MIN(p.startedAt) AS firstPlayedAt,
               MAX(p.startedAt) AS lastPlayedAt, COALESCE(SUM(p.listenedMs), 0) AS totalListenedMs
        FROM tracks t LEFT JOIN play_events p ON p.trackId = t.id
        GROUP BY t.id
        ORDER BY playCount DESC, lastPlayedAt DESC
        """,
    )
    fun observeTracksWithStats(): Flow<List<TrackWithStats>>

    @Query(
        """
        SELECT t.*, COUNT(p.id) AS playCount, MIN(p.startedAt) AS firstPlayedAt,
               MAX(p.startedAt) AS lastPlayedAt, COALESCE(SUM(p.listenedMs), 0) AS totalListenedMs
        FROM tracks t LEFT JOIN play_events p ON p.trackId = t.id
        WHERE t.id = :id
        GROUP BY t.id
        """,
    )
    fun observeTrackWithStats(id: Long): Flow<TrackWithStats?>

    @Query("SELECT DISTINCT artist FROM tracks")
    suspend fun allArtists(): List<String>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 재생 기록이 하나도 남지 않은 곡 정리 */
    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT DISTINCT trackId FROM play_events)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    @Query(
        """
        SELECT p.trackId, t.title, t.artist, t.genre, t.mood, t.bpm, t.year,
               p.startedAt, p.listenedMs, p.completed
        FROM play_events p JOIN tracks t ON t.id = p.trackId
        WHERE p.startedAt >= :from AND p.startedAt < :until
        ORDER BY p.startedAt
        """,
    )
    fun observePlayRows(from: Long, until: Long): Flow<List<PlayRow>>

    @Query(
        """
        SELECT p.trackId, t.title, t.artist, t.genre, t.mood, t.bpm, t.year,
               p.startedAt, p.listenedMs, p.completed
        FROM play_events p JOIN tracks t ON t.id = p.trackId
        ORDER BY p.startedAt
        """,
    )
    suspend fun allPlayRows(): List<PlayRow>

    @Query("DELETE FROM play_events WHERE trackId IN (:trackIds)")
    suspend fun deleteForTracks(trackIds: List<Long>)

    @Query("DELETE FROM play_events WHERE sourcePackage IN (:packages)")
    suspend fun deleteBySource(packages: Collection<String>): Int

    @Query("SELECT COUNT(*) FROM play_events WHERE sourcePackage IN (:packages)")
    fun observeCountBySource(packages: Collection<String>): Flow<Int>

    @Query("SELECT startedAt, listenedMs, completed, sourcePackage FROM play_events WHERE trackId = :trackId ORDER BY startedAt DESC")
    fun observeHistory(trackId: Long): Flow<List<PlayHistoryItem>>
}

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM recommendations ORDER BY score DESC")
    fun observeAll(): Flow<List<RecommendationEntity>>

    @Query("DELETE FROM recommendations")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<RecommendationEntity>)

    @Transaction
    suspend fun replaceAll(items: List<RecommendationEntity>) {
        clear()
        insertAll(items)
    }
}
