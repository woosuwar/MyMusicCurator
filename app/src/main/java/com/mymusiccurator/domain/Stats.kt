package com.mymusiccurator.domain

import com.mymusiccurator.data.db.PlayRow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

data class Ranked(val name: String, val count: Int, val sub: String? = null, val id: Long? = null)

data class DashboardStats(
    val totalPlays: Int = 0,
    val totalListenMs: Long = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val topTracks: List<Ranked> = emptyList(),
    val topArtists: List<Ranked> = emptyList(),
    val topGenres: List<Ranked> = emptyList(),
    val moods: List<Ranked> = emptyList(),
    val bpmBuckets: List<Ranked> = emptyList(),
    val decades: List<Ranked> = emptyList(),
    /** 0~23시 재생 수 */
    val byHour: List<Int> = List(24) { 0 },
    /** 월(0)~일(6) 재생 수 */
    val byWeekday: List<Int> = List(7) { 0 },
)

/** 한 곡을 얼마나 자주 듣는지 (재생 주기) */
data class PlayCycle(
    val playCount: Int,
    val averageIntervalMs: Long?,
    val medianIntervalMs: Long?,
    val lastPlayedAt: Long?,
    val nextExpectedAt: Long?,
    val playsLast7Days: Int,
    val playsLast30Days: Int,
) {
    /** 평소 주기보다 1.5배 이상 안 들었으면 "다시 들을 때" */
    fun isDue(now: Long): Boolean =
        averageIntervalMs != null && lastPlayedAt != null && now - lastPlayedAt > averageIntervalMs * 1.5
}

object StatsCalculator {
    private const val DAY = 24 * 60 * 60 * 1000L
    val BPM_BUCKETS = listOf("~79" to (0 until 80), "80~99" to (80 until 100), "100~119" to (100 until 120),
        "120~139" to (120 until 140), "140~" to (140 until 1000))

    fun dashboard(rows: List<PlayRow>, zone: ZoneId = ZoneId.systemDefault(), topN: Int = 10): DashboardStats {
        if (rows.isEmpty()) return DashboardStats()

        fun <K> rank(keyOf: (PlayRow) -> K?, label: (K) -> String = { it.toString() }): List<Ranked> =
            rows.mapNotNull { r -> keyOf(r)?.let { it to r } }
                .groupingBy { it.first }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { Ranked(label(it.key), it.value) }

        val topTracks = rows.groupBy { it.trackId }
            .map { (id, plays) -> Ranked(plays.first().title, plays.size, plays.first().artist, id) }
            .sortedByDescending { it.count }
            .take(topN)

        val byHour = IntArray(24)
        val byWeekday = IntArray(7)
        rows.forEach { r ->
            val t = Instant.ofEpochMilli(r.startedAt).atZone(zone)
            byHour[t.hour]++
            byWeekday[t.dayOfWeek.value - 1]++
        }

        return DashboardStats(
            totalPlays = rows.size,
            totalListenMs = rows.sumOf { it.listenedMs },
            uniqueTracks = rows.distinctBy { it.trackId }.size,
            uniqueArtists = rows.distinctBy { it.artist.lowercase() }.size,
            topTracks = topTracks,
            topArtists = rank({ it.artist }).take(topN),
            topGenres = rank({ it.genre }).take(topN),
            moods = rank({ it.mood }),
            bpmBuckets = BPM_BUCKETS.map { (label, range) -> Ranked(label, rows.count { r -> r.bpm != null && r.bpm in range }) },
            decades = rank({ r -> r.year?.let { it / 10 * 10 } }, { "${it}년대" }).sortedBy { it.name },
            byHour = byHour.toList(),
            byWeekday = byWeekday.toList(),
        )
    }

    fun cycle(playTimes: List<Long>, now: Long): PlayCycle {
        val sorted = playTimes.sorted()
        val intervals = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val avg = if (intervals.isEmpty()) null else intervals.average().toLong()
        val median = if (intervals.isEmpty()) null else intervals.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
        val last = sorted.lastOrNull()
        return PlayCycle(
            playCount = sorted.size,
            averageIntervalMs = avg,
            medianIntervalMs = median,
            lastPlayedAt = last,
            nextExpectedAt = if (last != null && median != null) last + median else null,
            playsLast7Days = sorted.count { now - it <= 7 * DAY },
            playsLast30Days = sorted.count { now - it <= 30 * DAY },
        )
    }

    /** 최근 재생일수록, 끝까지 들은 곡일수록 가중치가 큰 선호 점수 */
    fun affinity(rows: List<PlayRow>, now: Long, halfLifeDays: Double = 30.0): Affinity {
        val artists = mutableMapOf<String, Double>()
        val genres = mutableMapOf<String, Double>()
        val artistNames = mutableMapOf<String, String>()
        rows.forEach { r ->
            val ageDays = (now - r.startedAt).coerceAtLeast(0) / DAY.toDouble()
            val w = exp(-ageDays * Math.log(2.0) / halfLifeDays) * (if (r.completed) 1.0 else 0.6)
            val ak = r.artist.lowercase()
            artistNames.putIfAbsent(ak, r.artist)
            artists[ak] = (artists[ak] ?: 0.0) + w
            r.genre?.let { genres[it] = (genres[it] ?: 0.0) + w }
        }
        fun norm(m: Map<String, Double>): List<Pair<String, Double>> {
            val total = m.values.sum().takeIf { it > 0 } ?: return emptyList()
            return m.entries.sortedByDescending { it.value }.map { it.key to it.value / total }
        }
        return Affinity(
            artists = norm(artists).map { (k, v) -> artistNames.getValue(k) to v },
            genres = norm(genres),
        )
    }
}

/** 정규화된 선호도 (합계 1.0) – 높은 순 */
data class Affinity(val artists: List<Pair<String, Double>>, val genres: List<Pair<String, Double>>)
