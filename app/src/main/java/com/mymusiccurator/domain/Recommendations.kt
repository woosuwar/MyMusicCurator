package com.mymusiccurator.domain

import android.util.Log
import com.mymusiccurator.data.db.PlayEventDao
import com.mymusiccurator.data.db.RecommendationDao
import com.mymusiccurator.data.db.RecommendationEntity
import com.mymusiccurator.data.db.RecommendationType
import com.mymusiccurator.data.db.TrackDao
import com.mymusiccurator.data.metadata.TagClassifier
import com.mymusiccurator.data.remote.LastFmClient
import com.mymusiccurator.data.remote.NamedScore

data class ScoredItem(val name: String, val score: Double, val reasons: List<String>, val url: String? = null)

/** 네트워크와 무관한 순수 점수 계산 (테스트 가능) */
object RecommendationScorer {

    /**
     * @param similarByArtist 내가 좋아하는 가수 → Last.fm 유사 가수 (match 0~1)
     * @param topByGenre 내가 좋아하는 장르 → 그 장르의 인기 가수 (순위 점수 0~1)
     * @param known 이미 들어본 가수 (소문자)
     */
    fun scoreArtists(
        affinity: Affinity,
        similarByArtist: Map<String, List<NamedScore>>,
        topByGenre: Map<String, List<NamedScore>>,
        known: Set<String>,
        limit: Int = 20,
    ): List<ScoredItem> {
        val artistWeight = affinity.artists.toMap()
        val genreWeight = affinity.genres.toMap()

        class Acc(val name: String, var score: Double = 0.0, val reasons: MutableList<Pair<String, Double>> = mutableListOf(), var url: String? = null)
        val acc = linkedMapOf<String, Acc>()

        fun add(c: NamedScore, contribution: Double, reason: String) {
            val key = c.name.lowercase()
            if (key in known || contribution <= 0) return
            val a = acc.getOrPut(key) { Acc(c.name) }
            a.score += contribution
            a.reasons += reason to contribution
            if (a.url == null) a.url = c.url
        }

        similarByArtist.forEach { (seed, list) ->
            val w = artistWeight[seed] ?: 0.0
            list.forEach { add(it, w * it.score, "'$seed' 와(과) 비슷해요") }
        }
        topByGenre.forEach { (genre, list) ->
            val w = (genreWeight[genre] ?: 0.0) * 0.6
            list.forEach { add(it, w * it.score, "좋아하는 장르 '$genre' 의 인기 가수") }
        }

        return acc.values
            .sortedByDescending { it.score }
            .take(limit)
            .map { a -> ScoredItem(a.name, a.score, a.reasons.sortedByDescending { it.second }.map { it.first }.distinct().take(2), a.url) }
    }

    /** 추천 가수들의 태그 중, 내가 아직 많이 듣지 않은 장르 */
    fun scoreGenres(
        candidates: List<ScoredItem>,
        tagsByArtist: Map<String, List<String>>,
        userGenres: Set<String>,
        limit: Int = 6,
    ): List<ScoredItem> {
        val score = mutableMapOf<String, Double>()
        val via = mutableMapOf<String, MutableList<String>>()
        candidates.forEach { c ->
            val genres = TagClassifier.classify(tagsByArtist[c.name].orEmpty(), c.name).genres.take(3)
            genres.forEachIndexed { i, g ->
                if (g in userGenres) return@forEachIndexed
                score[g] = (score[g] ?: 0.0) + c.score / (i + 1)
                via.getOrPut(g) { mutableListOf() } += c.name
            }
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { (g, s) ->
            ScoredItem(g, s, listOf("추천 가수 ${via.getValue(g).take(3).joinToString(", ")} 의 장르"))
        }
    }
}

class RecommendationEngine(
    private val trackDao: TrackDao,
    private val playEventDao: PlayEventDao,
    private val recommendationDao: RecommendationDao,
    private val lastFm: LastFmClient,
) {
    sealed interface Outcome {
        data class Success(val artists: Int, val genres: Int) : Outcome
        data object NotEnoughData : Outcome
        data object NoApiKey : Outcome
        /** 네트워크 오류 등으로 결과가 없음 – 기존 추천은 그대로 둔다 */
        data object NoResults : Outcome
    }

    suspend fun refresh(now: Long = System.currentTimeMillis()): Outcome {
        if (!lastFm.isConfigured) return Outcome.NoApiKey
        val rows = playEventDao.allPlayRows()
        if (rows.size < 3) return Outcome.NotEnoughData

        val affinity = StatsCalculator.affinity(rows, now)
        val seedArtists = affinity.artists.take(5)
        val seedGenres = affinity.genres.take(3)

        val similar = seedArtists.associate { (artist, _) -> artist to safe { lastFm.similarArtists(artist, 15) } }
        val byGenre = seedGenres.associate { (genre, _) -> genre to safe { lastFm.tagTopArtists(genre, 15) } }

        val known = trackDao.allArtists().map { it.lowercase() }.toSet()
        val artists = RecommendationScorer.scoreArtists(affinity, similar, byGenre, known)
        if (artists.isEmpty()) return Outcome.NoResults

        val tags = artists.take(8).associate { it.name to safe { lastFm.artistTopTags(it.name) } }
        val genres = RecommendationScorer.scoreGenres(artists, tags, affinity.genres.take(5).map { it.first }.toSet())

        recommendationDao.replaceAll(
            artists.map { RecommendationEntity(type = RecommendationType.ARTIST, name = it.name, score = it.score, reason = it.reasons.joinToString(" · "), url = it.url, createdAt = now) } +
                genres.map { RecommendationEntity(type = RecommendationType.GENRE, name = it.name, score = it.score, reason = it.reasons.joinToString(" · "), createdAt = now) },
        )
        return Outcome.Success(artists.size, genres.size)
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RecommendationEngine", "Last.fm 호출 실패", e)
            emptyList()
        }
}
