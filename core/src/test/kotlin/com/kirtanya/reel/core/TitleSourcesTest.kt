package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.TitleSources
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.sources.StartSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The TV title page's source list: order, the five-row cut, "All sources…", resume label, searching and empty states. */
class TitleSourcesTest {
    private fun torrent(n: Int, res: Int? = 1080) = Source(
        kind = SourceKind.TORRENT, title = "Film.2024.$n", provider = "Torrentio", quality = Quality(resolution = res),
        infoHash = "%040x".format(n), seeders = 100 + n, sizeBytes = 4_000_000_000L,
    )

    private fun external() = Source(SourceKind.EXTERNAL, "Netflix", "WatchHub", Quality(), externalUrl = "https://netflix.example/1")

    private fun trailer() = Source(SourceKind.DIRECT, "Film Trailer", "Trailers", Quality(1080), url = "https://x/t.mp4", extra = "Trailer")

    private fun done(vararg s: Source, total: Int = 4, failed: Map<String, String> = emptyMap()) =
        SourceSearch(s.toList(), pending = emptyList(), failed = failed, total = total)

    @Test fun quickStartRowsComeFirstOnceAndKeepTheirMarks() {
        val all = (1..8).map { torrent(it) }
        val quick = listOf(all[6], all[3])
        val page = TitleSources.page(done(*all.toTypedArray()), all, expanded = false, quick = quick, speed = { if (it.key == all[6].key) StartSpeed.FAST else StartSpeed.UNKNOWN })
        assertEquals(listOf(all[6], all[3], all[0], all[1], all[2]).map { it.key }, page.rows.map { it.key })
        assertEquals(2, page.quick)
        assertEquals(StartSpeed.FAST, page.rows[0].speed)
        assertEquals(StartSpeed.UNKNOWN, page.rows[1].speed)
        assertEquals(3, page.more)
        assertEquals(5, page.firstExtra)
    }

    @Test fun aFullQuickStartGroupIsNeverCutAndExpandingStillHasNoDuplicates() {
        val all = (1..9).map { torrent(it) }
        val quick = all.takeLast(6)
        val collapsed = TitleSources.page(done(*all.toTypedArray()), all, expanded = false, quick = quick)
        assertEquals(5, collapsed.rows.size)
        assertEquals(5, collapsed.quick)
        assertEquals(4, collapsed.more)
        val open = TitleSources.page(done(*all.toTypedArray()), all, expanded = true, quick = quick)
        assertEquals(9, open.rows.map { it.key }.distinct().size)
        assertEquals(5, open.firstExtra)
    }

    @Test fun quickStartSkipsWhatThePageWouldNotList() {
        val a = torrent(1)
        val page = TitleSources.page(done(a, external()), listOf(a, external()), expanded = false, quick = listOf(external(), trailer(), a))
        assertEquals(listOf(a.key, external().key), page.rows.map { it.key })
        assertEquals(1, page.quick)
        assertEquals(listOf(a), TitleSources.quickCandidates(listOf(external(), trailer(), a)))
        assertEquals(emptyList(), TitleSources.quickCandidates(listOf(a), fits = { false }))
    }

    @Test fun showsTheBestFiveThenCountsTheRest() {
        val all = (1..8).map { torrent(it) }
        val page = TitleSources.page(done(*all.toTypedArray()), all, expanded = false)
        assertEquals(all.take(5).map { it.key }, page.rows.map { it.key })
        assertEquals(3, page.more)
    }

    @Test fun expandingShowsEverything() {
        val all = (1..8).map { torrent(it) }
        val page = TitleSources.page(done(*all.toTypedArray()), all, expanded = true)
        assertEquals(8, page.rows.size)
        assertEquals(0, page.more)
    }

    @Test fun keepsTheCallersOrderWhichCarriesStarsAndDemotions() {
        val a = torrent(1); val b = torrent(2); val c = torrent(3)
        val page = TitleSources.page(done(a, b, c), listOf(c, a, b), expanded = false)
        assertEquals(listOf(c, a, b).map { it.key }, page.rows.map { it.key })
    }

    @Test fun trailersNeverShowAndServicesGoLast() {
        val a = torrent(1)
        val page = TitleSources.page(done(external(), trailer(), a), listOf(external(), trailer(), a), expanded = false)
        assertEquals(listOf(a.key, external().key), page.rows.map { it.key })
        assertEquals("Streaming service", page.rows[1].title)
        assertEquals("WatchHub · Opens in its app", page.rows[1].detail)
    }

    @Test fun rowsUseThePlayersChangeSourceText() {
        val row = TitleSources.page(done(torrent(1)), listOf(torrent(1)), expanded = false).rows.single()
        assertEquals(com.kirtanya.reel.core.player.PlayerSources.row(torrent(1), current = false).title, row.title)
        assertEquals("Torrentio · 101 seeders", row.detail)
    }

    @Test fun resumeSitsOnTheTopRowThatPlaysInTheApp() {
        val a = torrent(1); val b = torrent(2)
        val page = TitleSources.page(done(external(), a, b), listOf(external(), a, b), expanded = false, resume = "Resume 42:10")
        assertEquals(listOf(a.key, b.key, external().key), page.rows.map { it.key })
        assertEquals(listOf("Resume 42:10", null, null), page.rows.map { it.resume })
    }

    @Test fun noResumeLabelWithoutASavedPosition() {
        val page = TitleSources.page(done(torrent(1)), listOf(torrent(1)), expanded = false)
        assertNull(page.rows.single().resume)
    }

    @Test fun marksAFileThisDeviceCannotHold() {
        val a = torrent(1); val b = torrent(2)
        val page = TitleSources.page(done(a, b), listOf(a, b), expanded = false, fits = { it.key != a.key })
        assertEquals(listOf(true, false), page.rows.map { it.tooBig })
    }

    @Test fun searchingSaysHowFarItIs() {
        val a = torrent(1)
        val s = SourceSearch(listOf(a), pending = listOf("Comet", "Peerflix"), failed = emptyMap(), total = 7)
        val page = TitleSources.page(s, listOf(a), expanded = false)
        assertEquals("Searching… 5 of 7", page.searching)
        assertNull(page.empty)
        assertEquals("Searching…", TitleSources.page(null, emptyList(), expanded = false).searching)
    }

    @Test fun emptyOnlyOnceTheSearchHasEnded() {
        val running = SourceSearch(emptyList(), pending = listOf("Comet"), failed = emptyMap(), total = 2)
        assertNull(TitleSources.page(running, emptyList(), expanded = false).empty)
        val ended = TitleSources.page(done(total = 5), emptyList(), expanded = false)
        assertNull(ended.searching)
        assertEquals("Nothing found", ended.empty?.title)
        assertEquals("None of your 5 sources had this. Try again later, or add more sources in Settings.", ended.empty?.body)
    }

    @Test fun onlyTrailersCountsAsNothingToPlay() {
        val page = TitleSources.page(done(trailer()), listOf(trailer()), expanded = false)
        assertTrue(page.rows.isEmpty())
        assertEquals("Only trailers and extras found", page.empty?.title)
    }

    @Test fun saysWhyForNoProvidersAndForAllFailed() {
        assertEquals("No sources for this yet", TitleSources.page(done(total = 0), emptyList(), false).empty?.title)
        val failed = TitleSources.page(done(total = 2, failed = mapOf("A" to "x", "B" to "y")), emptyList(), false).empty
        assertEquals("Your sources didn't answer", failed?.title)
        assertEquals("A: x · B: y. Check the connection and search again.", failed?.body)
        assertNotNull(failed)
        assertEquals("Your only source didn't have this. Try again later, or add more sources in Settings.", TitleSources.noSources(done(total = 1)).body)
    }

    @Test fun namesFailedSourcesWhenSomeAnsweredWithNothing() {
        // A friend's TV (0.16.7): six addons unreachable, one plugin empty read as "None of your 7 sources had this".
        val failed = (1..6).associate { "Addon $it" to "Timed out" }
        val empty = TitleSources.noSources(done(total = 7, failed = failed))
        assertEquals("Some sources didn't answer", empty.title)
        assertEquals(
            "Addon 1: Timed out · Addon 2: Timed out · Addon 3: Timed out · +3 more. The others had nothing for this. Check the connection and search again.",
            empty.body,
        )
        assertEquals("None of your 7 sources had this. Try again later, or add more sources in Settings.", TitleSources.noSources(done(total = 7)).body)
    }

    @Test fun neverMoreThanFortyRows() {
        val all = (1..60).map { torrent(it) }
        val page = TitleSources.page(done(*all.toTypedArray()), all, expanded = true)
        assertEquals(TitleSources.MAX, page.rows.size)
        assertFalse(page.more > 0)
    }

    @Test fun wordsASavedPosition() {
        assertEquals("Resume 42:10", TitleSources.resumeLabel((42 * 60 + 10) * 1000L))
        assertEquals("Resume 0:07", TitleSources.resumeLabel(7_000))
        assertEquals("Resume 1:05:09", TitleSources.resumeLabel((3600 + 5 * 60 + 9) * 1000L))
    }
}
