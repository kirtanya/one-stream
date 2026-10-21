package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.Hidden
import com.kirtanya.reel.core.library.HiddenTitle
import com.kirtanya.reel.core.library.Watched
import com.kirtanya.reel.core.library.WatchedMark
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.HistoryDays
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.tmdb.SeasonSummary
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchedTest {
    private val seasons = listOf(SeasonSummary(0, "Specials", 3, null, null), SeasonSummary(1, "S1", 3, null, null), SeasonSummary(2, "S2", 4, null, null))
    private fun entry(key: String, finished: Boolean, at: Long) = WatchEntry(
        Playback(key, "tv:1", "Show", tmdbType = "tv", tmdbId = 1, source = Source(SourceKind.DIRECT, "x", "p", Quality(), url = "u")),
        if (finished) 100 else 10, 100, at,
    )
    private fun mark(key: String, watched: Boolean, at: Long) = WatchedMark(key, "tv:1", watched, at)

    @Test fun newestOfMarkAndHistoryDecides(): Unit {
        val k = "tv:1:s1e1"
        assertTrue(Watched.isWatched(k, emptyMap(), mapOf(k to entry(k, true, 5))))
        // Marked unwatched after finishing → unwatched.
        assertFalse(Watched.isWatched(k, mapOf(k to mark(k, false, 10)), mapOf(k to entry(k, true, 5))))
        // Finished again after that → watched.
        assertTrue(Watched.isWatched(k, mapOf(k to mark(k, false, 10)), mapOf(k to entry(k, true, 20))))
        // A half-watched entry never counts.
        assertFalse(Watched.isWatched(k, emptyMap(), mapOf(k to entry(k, false, 5))))
    }

    @Test fun episodesSeasonsAndUpTo(): Unit {
        val lastAired = 2 to 2
        assertEquals(5, Watched.allEpisodes(seasons, lastAired).size) // 3 + 2 aired, no specials
        assertEquals(listOf(EpisodeNumber(2, 1), EpisodeNumber(2, 2)), Watched.season(seasons, 2, lastAired))
        assertEquals(4, Watched.upTo(seasons, EpisodeNumber(2, 1), lastAired).size)
        val watched = setOf("tv:1:s1e1", "tv:1:s1e2")
        val eps = Watched.allEpisodes(seasons, lastAired)
        assertEquals(2, Watched.count(1, eps) { it in watched }.watched)
        assertEquals(EpisodeNumber(1, 3), Watched.nextUnwatched(1, eps) { it in watched })
        assertNull(Watched.nextUnwatched(1, eps) { false })
    }

    @Test fun finishedHistoryBecomesMarksOnce(): Unit {
        val e = entry("tv:1:s1e1", true, 50)
        assertEquals(1, Watched.fromHistory(emptyMap(), listOf(e, entry("tv:1:s1e2", false, 60))).size)
        assertTrue(Watched.fromHistory(mapOf(e.playback.key to mark(e.playback.key, true, 50)), listOf(e)).isEmpty())
    }

    @Test fun hiddenMergesLikeTheWatchlist(): Unit {
        val tv = listOf(HiddenTitle("movie:2", "B", changedAt = 5))
        val phone = listOf(HiddenTitle("movie:1", "A", changedAt = 3), HiddenTitle("movie:2", "B", hidden = false, changedAt = 4))
        assertEquals(setOf("movie:1", "movie:2"), Hidden.keys(Hidden.merge(tv, phone, now = 10))) // TV's newer hide stays
    }

    @Test fun historyDays(): Unit {
        val day = 24 * 60 * 60 * 1000L
        val now = 1_760_000_000_000L
        val days = HistoryDays.group(listOf(entry("a", true, now - 1000), entry("b", true, now - day), entry("c", true, now - 3 * day)), ZoneOffset.UTC, now)
        assertEquals(listOf("Today", "Yesterday"), days.take(2).map { it.label })
        assertEquals(3, days.size)
    }

    @Test fun olderBundlesStillRead(): Unit {
        val b = Json { ignoreUnknownKeys = true }.decodeFromString(SourcesBundle.serializer(), """{"addons":[]}""")
        assertNull(b.watched)
        assertNull(b.hidden)
    }
}
