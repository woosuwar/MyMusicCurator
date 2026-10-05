package com.mymusiccurator

import com.mymusiccurator.data.db.PlayRow
import com.mymusiccurator.data.metadata.TagClassifier
import com.mymusiccurator.data.remote.NamedScore
import com.mymusiccurator.domain.PlayRecord
import com.mymusiccurator.domain.PlaybackTracker
import com.mymusiccurator.domain.RecommendationScorer
import com.mymusiccurator.domain.StatsCalculator
import com.mymusiccurator.domain.TrackMeta
import com.mymusiccurator.domain.trackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TagClassifierTest {
    @Test
    fun classifiesGenreAndMoodIgnoringJunk() {
        val r = TagClassifier.classify(listOf("seen live", "IU", "kpop", "ballad", "sad", "female vocalists", "2010s"), artist = "IU")
        assertEquals("K-Pop", r.genre)
        assertEquals(listOf("K-Pop", "Ballad"), r.genres)
        assertEquals("슬픈", r.mood)
        assertFalse(r.tags.any { it.equals("seen live", true) || it == "IU" || it == "2010s" })
    }

    @Test
    fun canonicalizesGenreSpellings() {
        assertEquals("Hip-Hop", TagClassifier.canonicalGenre("hip hop"))
        assertEquals("Hip-Hop", TagClassifier.canonicalGenre("HipHop"))
        assertEquals("R&B", TagClassifier.canonicalGenre("rnb"))
        assertEquals("Indie Rock", TagClassifier.canonicalGenre("indie rock"))
    }
}

class PlaybackTrackerTest {
    private val song = TrackMeta("Spring Day", "BTS", durationMs = 274_000)
    private val other = TrackMeta("Butter", "BTS", durationMs = 164_000)

    private fun tracker(out: MutableList<PlayRecord>) = PlaybackTracker(onPlay = { out += it })

    @Test
    fun countsOnlyActualPlayingTime() {
        val plays = mutableListOf<PlayRecord>()
        val t = tracker(plays)
        t.onPlaybackStateChanged("s", true, 0, 0)
        t.onMetadataChanged("s", "app", song, 0)
        t.onPlaybackStateChanged("s", false, 60_000, 60_000)     // 60초 듣고 일시정지
        t.onPlaybackStateChanged("s", true, 60_000, 600_000)      // 9분 뒤 재개
        t.onMetadataChanged("s", "app", other, 700_000)            // 100초 더 듣고 다음 곡
        assertEquals(1, plays.size)
        assertEquals(160_000, plays[0].listenedMs)
        assertFalse(plays[0].completed)
    }

    @Test
    fun skipsShortListens() {
        val plays = mutableListOf<PlayRecord>()
        val t = tracker(plays)
        t.onPlaybackStateChanged("s", true, 0, 0)
        t.onMetadataChanged("s", "app", song, 0)
        t.onMetadataChanged("s", "app", other, 20_000)  // 20초 만에 넘김
        t.onSessionDestroyed("s", 200_000)               // 다음 곡은 끝까지
        assertEquals(listOf("Butter"), plays.map { it.meta.title })
        assertTrue(plays[0].completed)
    }

    @Test
    fun repeatOneCountsEachLoop() {
        val plays = mutableListOf<PlayRecord>()
        val t = tracker(plays)
        t.onPlaybackStateChanged("s", true, 0, 0)
        t.onMetadataChanged("s", "app", other, 0)
        t.onPlaybackStateChanged("s", true, 0, 164_000)   // 같은 곡이 처음부터 다시
        t.onPlaybackStateChanged("s", false, 164_000, 328_000)
        t.flushAll(328_000)
        assertEquals(2, plays.size)
    }

    @Test
    fun ignoresDuplicateMetadataForSameTrack() {
        val plays = mutableListOf<PlayRecord>()
        val t = tracker(plays)
        t.onPlaybackStateChanged("s", true, 0, 0)
        t.onMetadataChanged("s", "app", song, 0)
        t.onMetadataChanged("s", "app", song.copy(title = "Spring Day (feat. Nobody)"), 50_000)
        t.flushAll(200_000)
        assertEquals(1, plays.size)
        assertEquals(200_000, plays[0].listenedMs)
    }

    @Test
    fun trackKeyNormalizes() {
        assertEquals(trackKey("IU", "Love wins all"), trackKey(" iu ", "Love  Wins All (feat. X)"))
    }
}

class StatsTest {
    private val day = 86_400_000L

    private fun row(id: Long, artist: String, genre: String?, at: Long, bpm: Int? = null, year: Int? = null) =
        PlayRow(id, "t$id", artist, genre, null, bpm, year, at, 180_000, true)

    @Test
    fun dashboardAggregates() {
        val rows = listOf(
            row(1, "A", "Rock", 0, bpm = 128, year = 1995),
            row(1, "A", "Rock", day, bpm = 128, year = 1995),
            row(2, "B", "Jazz", 2 * day, bpm = 90, year = 2003),
        )
        val s = StatsCalculator.dashboard(rows, ZoneOffset.UTC)
        assertEquals(3, s.totalPlays)
        assertEquals(2, s.uniqueTracks)
        assertEquals("A", s.topArtists.first().name)
        assertEquals(2, s.topTracks.first().count)
        assertEquals(listOf("1990년대", "2000년대"), s.decades.map { it.name })
        assertEquals(2, s.bpmBuckets.first { it.name == "120~139" }.count)
        assertEquals(3, s.byHour[0])
    }

    @Test
    fun cycleUsesIntervalsBetweenPlays() {
        val c = StatsCalculator.cycle(listOf(0, 2 * day, 4 * day, 6 * day), now = 20 * day)
        assertEquals(2 * day, c.averageIntervalMs)
        assertEquals(8 * day, c.nextExpectedAt)
        assertTrue(c.isDue(20 * day))
        assertFalse(StatsCalculator.cycle(listOf(0, 2 * day), now = 3 * day).isDue(3 * day))
    }

    @Test
    fun affinityFavorsRecentPlays() {
        val now = 100 * day
        val rows = List(5) { row(1, "Old", "Rock", 0) } + List(2) { row(2, "New", "Pop", now) }
        val a = StatsCalculator.affinity(rows, now)
        assertEquals("New", a.artists.first().first)
        assertEquals(1.0, a.artists.sumOf { it.second }, 1e-9)
    }
}

class RecommendationScorerTest {
    @Test
    fun excludesKnownArtistsAndCombinesSources() {
        val day = 86_400_000L
        val rows = List(3) { PlayRow(1, "x", "IU", "K-Pop", null, null, null, 0, 1, true) } +
            PlayRow(2, "y", "AKMU", "Folk", null, null, null, 0, 1, true)
        val affinity = StatsCalculator.affinity(rows, day)
        val result = RecommendationScorer.scoreArtists(
            affinity,
            similarByArtist = mapOf(
                "IU" to listOf(NamedScore("Taeyeon", 1.0), NamedScore("AKMU", 0.9), NamedScore("BOL4", 0.5)),
                "AKMU" to listOf(NamedScore("BOL4", 1.0)),
            ),
            topByGenre = mapOf("K-Pop" to listOf(NamedScore("Taeyeon", 1.0))),
            known = setOf("iu", "akmu"),
        )
        assertEquals(listOf("Taeyeon", "BOL4"), result.map { it.name })
        assertTrue(result.first().reasons.size == 2)

        val genres = RecommendationScorer.scoreGenres(
            result,
            mapOf("Taeyeon" to listOf("ballad", "k-pop"), "BOL4" to listOf("indie", "acoustic")),
            userGenres = setOf("K-Pop"),
        )
        assertEquals("Ballad", genres.first().name)
    }
}
