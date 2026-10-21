package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.DownloadTag
import com.kirtanya.reel.core.library.LastPlayed
import com.kirtanya.reel.core.library.SourceMarks
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.tmdb.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceMarksTest {
    private val film = SourceRequest(550, "tt0137523", MediaType.MOVIE, "Fight Club", 1999)
    private val e1 = SourceRequest(1399, null, MediaType.TV, "Game of Thrones", 2011, 1, 1)
    private val e2 = e1.copy(episode = 2)
    private fun torrent(hash: Char, group: String? = null) =
        Source(SourceKind.TORRENT, "Release $hash", "Addon", Quality(1080), infoHash = hash.toString().repeat(40), bingeGroup = group)

    private val a = torrent('a')
    private val b = torrent('b')

    @Test fun oneFavouritePerTitleAndItIsPicked(): Unit {
        var marks = SourceMarks.set(emptyList(), film, a, favourite = true, now = 1)
        marks = SourceMarks.set(marks, film, b, favourite = true, now = 2)
        assertEquals(b, SourceMarks.favourite(listOf(a, b), marks, film))
        assertEquals(listOf(b.key), marks.map { it.sourceKey }) // a's mark had nothing left → dropped
    }

    @Test fun starredGoFirstAndCanBeMany(): Unit {
        val c = torrent('c')
        var marks = SourceMarks.set(emptyList(), film, c, starred = true, now = 1)
        marks = SourceMarks.set(marks, film, b, starred = true, now = 2)
        assertEquals(listOf(b, c, a), SourceMarks.order(listOf(a, b, c), marks, film))
        marks = SourceMarks.set(marks, film, b, starred = false, now = 3)
        assertEquals(listOf(c, a, b), SourceMarks.order(listOf(a, b, c), marks, film))
    }

    @Test fun aShowsFavouriteReleaseFollowsToTheNextEpisode(): Unit {
        val ep1 = torrent('a', group = "torrentio|1080p|GROUP")
        val ep2 = torrent('d', group = "torrentio|1080p|GROUP")
        val marks = SourceMarks.set(emptyList(), e1, ep1, favourite = true, now = 1)
        assertEquals(ep2, SourceMarks.favourite(listOf(torrent('e'), ep2), marks, e2))
        // Films never borrow marks from other sources.
        assertNull(SourceMarks.favourite(listOf(torrent('f')), marks, film))
    }

    @Test fun tags(): Unit {
        val marks = SourceMarks.set(emptyList(), film, a, favourite = true, now = 1).let { SourceMarks.set(it, film, a, starred = true, now = 2) }
        assertEquals(
            listOf("Last played", "Favourite", "Starred", "Downloading 42%"),
            SourceMarks.tags(a, film, marks, LastPlayed(same = a), DownloadTag(0.42f, finished = false)),
        )
        assertEquals(listOf("Downloaded"), SourceMarks.tags(b, film, marks, LastPlayed(same = a), DownloadTag(1f, finished = true)))
        val ep2 = torrent('d', group = "g")
        assertEquals(listOf("Same as last episode"), SourceMarks.tags(ep2, e2, emptyList(), LastPlayed(latestOfShow = torrent('a', group = "g")), null))
    }

    @Test fun shareTextIsTheMagnetOrLink(): Unit {
        assertTrue(SourceMarks.shareText(a)!!.startsWith("magnet:?xt=urn:btih:" + "a".repeat(40)))
        assertEquals("https://x/y.mp4", SourceMarks.shareText(Source(SourceKind.DIRECT, "t", "p", Quality(), url = "https://x/y.mp4")))
    }
}
