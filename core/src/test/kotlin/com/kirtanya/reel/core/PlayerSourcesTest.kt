package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.PlayerSources
import com.kirtanya.reel.core.sources.ByteSize
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player's Change source page: rows, the playing one marked, search progress, empty note (2026-11-04). */
class PlayerSourcesTest {
    private fun torrent(n: Int, res: Int? = 1080, seeders: Int? = 120, size: Long? = 4_500_000_000L, provider: String = "Torrentio | RD", title: String = "Film.2024.$n") = Source(
        kind = SourceKind.TORRENT, title = title, provider = provider, quality = Quality(resolution = res),
        infoHash = "%040x".format(n), seeders = seeders, sizeBytes = size,
    )

    private fun direct(n: Int, title: String = "Film $n", extra: String? = null) = Source(
        kind = SourceKind.DIRECT, title = title, provider = "Built-in: Internet Archive", quality = Quality(resolution = 720),
        url = "https://example.org/$n.mp4", extra = extra,
    )

    private fun external() = Source(
        kind = SourceKind.EXTERNAL, title = "Netflix", provider = "WatchHub", quality = Quality(), externalUrl = "https://netflix.example/1",
    )

    private fun done(vararg s: Source) = SourceSearch(s.toList(), pending = emptyList(), failed = emptyMap(), total = 4)

    @Test fun rowsKeepTheRankersOrderAndMarkThePlayingOne() {
        val a = torrent(1, res = 2160, size = 12_000_000_000L)
        val b = torrent(2)
        val c = torrent(3, res = 720, size = 900_000_000L)
        val page = PlayerSources.page(done(a, b, c), playing = b)
        assertEquals(listOf(a.key, b.key, c.key), page.rows.map { it.key })
        assertEquals(listOf(false, true, false), page.rows.map { it.current })
        assertNull(page.searching)
        assertNull(page.empty)
    }

    @Test fun rowTextHasQualitySizeProviderAndSeedHealth() {
        val row = PlayerSources.row(torrent(1, res = 2160, size = 12_000_000_000L, seeders = 412), current = false)
        assertEquals("4K · 11.2 GB", row.title)
        assertEquals("Torrentio · Strong", row.detail)
        assertEquals("1080p · 4.2 GB", PlayerSources.row(torrent(2), false).title)
        assertEquals("Torrentio · Weak", PlayerSources.row(torrent(3, seeders = 3), false).detail)
        assertEquals("Torrentio · No seeds", PlayerSources.row(torrent(4, seeders = 0), false).detail)
        assertEquals("Torrentio · Seeds unknown", PlayerSources.row(torrent(5, seeders = null), false).detail)
    }

    @Test fun directLinksSayDirectAndAnUnknownSizeIsLeftOut() {
        val row = PlayerSources.row(direct(1), current = false)
        assertEquals("720p", row.title)
        assertEquals("Internet Archive · Direct", row.detail)
        assertEquals("SD?", PlayerSources.row(torrent(6, res = null, size = null), false).title)
    }

    @Test fun anUncachedDebridLinkIsFlagged() {
        val uncached = direct(2, title = "Film 2024 1080p [RD download]")
        assertEquals("Internet Archive · Not cached", PlayerSources.row(uncached, false).detail)
    }

    @Test fun aLanguageEndsTheDetailLine() {
        val hindi = torrent(7, title = "Film.2024.1080p.Hindi.WEB-DL")
        assertEquals("Torrentio · Strong · Hindi", PlayerSources.row(hindi, false).detail)
    }

    @Test fun servicesAndTrailersNeverListed() {
        val film = torrent(1)
        val page = PlayerSources.page(done(external(), direct(9, extra = "Trailer"), film), playing = film)
        assertEquals(listOf(film.key), page.rows.map { it.key })
    }

    @Test fun aPlayingSourceMissingFromTheResultsHeadsTheList() {
        val playing = torrent(1)
        val other = torrent(2)
        val page = PlayerSources.page(done(other), playing)
        assertEquals(listOf(playing.key, other.key), page.rows.map { it.key })
        assertTrue(page.rows.first().current)
    }

    @Test fun searchingShowsHowManyHaveAnswered() {
        val running = SourceSearch(listOf(torrent(2)), pending = listOf("A", "B", "C"), failed = emptyMap(), total = 7)
        assertEquals("Searching… 4 of 7", PlayerSources.page(running, torrent(1)).searching)
        assertEquals("Searching…", PlayerSources.page(null, torrent(1)).searching)
        // While it runs there is no "nothing found" note, even with only the playing source listed.
        assertNull(PlayerSources.page(SourceSearch(emptyList(), listOf("A"), emptyMap(), 1), torrent(1)).empty)
    }

    @Test fun noResultsSaysSoOnceTheSearchEnded() {
        val playing = torrent(1)
        val page = PlayerSources.page(done(), playing)
        assertEquals(PlayerSources.NONE_FOUND, page.empty)
        assertEquals(1, page.rows.size)
        assertEquals(PlayerSources.NONE_FOUND, PlayerSources.page(done(playing), playing).empty)
    }

    @Test fun theListIsCapped() {
        val many = (1..40).map { torrent(it) }
        assertEquals(PlayerSources.MAX_ROWS, PlayerSources.page(done(*many.toTypedArray()), many[0]).rows.size)
    }

    @Test fun byteSizesReadLikeTheSourcesPage() {
        assertEquals("900 B", ByteSize.format(900))
        assertEquals("858 MB", ByteSize.format(900_000_000))
        assertEquals("4.2 GB", ByteSize.format(4_500_000_000))
    }
}
