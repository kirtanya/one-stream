package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.WatchMarks
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchMarksTest {
    private val now = 1_000_000_000L
    private fun marker(ep: Int) = Playback.watchedMarker(
        "tv:1399", "tv:1399:s1e$ep", "Game of Thrones", "tv", 1399, EpisodeNumber(1, ep), EpisodeNumber(1, ep + 1),
    )

    @Test fun unplayedEpisodeMarkedWatchedShowsUpNext(): Unit {
        val list = WatchMarks.watched(emptyList(), marker(3), runtimeMs = null, now = now)
        val e = list.single()
        assertTrue(e.finished && e.playback.markedOnly)
        val card = ContinueWatching.select(list, now).single()
        assertEquals(EpisodeNumber(1, 4), (card as ContinueItem.UpNext).next)
    }

    @Test fun theStandInSourceIsNeverPickedOrPlayed(): Unit {
        val real = Source(SourceKind.TORRENT, "GoT S01E04 1080p", "Torrentio", Quality(1080), infoHash = "a".repeat(40))
        assertEquals(real, pickNextSource(marker(3).source, listOf(real)))
        assertEquals(0, ContinueWatching.resumePositionMs(WatchMarks.watched(emptyList(), marker(3), null, now).single()))
    }

    @Test fun aPlayedEpisodeKeepsItsRealSource(): Unit {
        val src = Source(SourceKind.TORRENT, "GoT S01E03", "Torrentio", Quality(1080), infoHash = "b".repeat(40))
        val played = WatchEntry(marker(3).copy(source = src), 600_000, 3_000_000, now - 10)
        val e = WatchMarks.watched(listOf(played), marker(3), runtimeMs = null, now = now).single()
        assertEquals(src, e.playback.source)
        assertEquals(3_000_000, e.positionMs)
        assertFalse(e.playback.markedOnly)
    }

    @Test fun unwatchedForgetsOnlyThatEpisode(): Unit {
        val list = WatchMarks.watched(WatchMarks.watched(emptyList(), marker(1), 1, now), marker(2), 1, now + 1)
        assertEquals(listOf("tv:1399:s1e1"), WatchMarks.unwatched(list, "tv:1399:s1e2").map { it.playback.key })
    }
}
