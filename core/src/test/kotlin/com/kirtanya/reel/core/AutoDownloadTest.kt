package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.AutoDownload
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.playback.EpisodeNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoDownloadTest {
    private val aired = (1..5).map { EpisodeNumber(1, it) } + (1..2).map { EpisodeNumber(2, it) }
    private fun dl(ep: EpisodeNumber, hash: String, finished: Boolean = true) =
        KnownDownload(hash, DownloadOrigin("tv", 7, ep.season, ep.episode, "x"), finished)

    @Test fun nextUnwatchedAfterTheLastWatched(): Unit {
        val watched = setOf("tv:7:s1e1", "tv:7:s1e2")
        assertEquals(listOf(EpisodeNumber(1, 3), EpisodeNumber(1, 4)), AutoDownload.wanted(7, aired, 2, watched::contains))
        // Crosses into the next season.
        val most = (1..5).map { "tv:7:s1e$it" }.toSet()
        assertEquals(listOf(EpisodeNumber(2, 1), EpisodeNumber(2, 2)), AutoDownload.wanted(7, aired, 3, most::contains))
    }

    @Test fun notStartedOrCaughtUpGetsNothing(): Unit {
        assertTrue(AutoDownload.wanted(7, aired, 2) { false }.isEmpty())
        val all = aired.map { "tv:7:s${it.season}e${it.episode}" }.toSet()
        assertTrue(AutoDownload.wanted(7, aired, 2, all::contains).isEmpty())
    }

    @Test fun skipsWhatIsAlreadyDownloading(): Unit {
        val want = listOf(EpisodeNumber(1, 3), EpisodeNumber(1, 4))
        assertEquals(listOf(EpisodeNumber(1, 4)), AutoDownload.missing(7, want, listOf(dl(EpisodeNumber(1, 3), "a", finished = false))))
    }

    @Test fun deletesOnlyFinishedDownloadsWatchedLongEnoughAgo(): Unit {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val downloads = listOf(dl(EpisodeNumber(1, 1), "old"), dl(EpisodeNumber(1, 2), "recent"), dl(EpisodeNumber(1, 3), "running", finished = false), dl(EpisodeNumber(1, 4), "unwatched"))
        val watchedAt = mapOf("tv:7:s1e1" to now - 8 * day, "tv:7:s1e2" to now - 2 * day, "tv:7:s1e3" to now - 30 * day)
        assertEquals(listOf("old"), AutoDownload.toDelete(downloads, 7, now, watchedAt::get))
        assertTrue(AutoDownload.toDelete(downloads, null, now, watchedAt::get).isEmpty())
    }
}
