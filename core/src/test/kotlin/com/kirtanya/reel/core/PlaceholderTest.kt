package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.NotReadySources
import com.kirtanya.reel.core.player.PlaceholderCheck
import com.kirtanya.reel.core.player.PlaceholderFallback
import com.kirtanya.reel.core.player.Readiness
import com.kirtanya.reel.core.player.ReadyCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import com.kirtanya.reel.core.sources.CacheState
import com.kirtanya.reel.core.sources.DebridCache
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceBrowser
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.StremioAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Debrid "downloading" placeholder clips: cache markers, detection, fallback, not-ready memory. */
class PlaceholderTest {
    private fun direct(title: String, provider: String = "Debridio", res: Int? = 1080, url: String = "https://x.example/$title.mkv", cache: CacheState? = null) =
        Source(SourceKind.DIRECT, title, provider, Quality(resolution = res), url = url, cache = cache)

    // ------------------------------------------------------------ DebridCache

    @Test fun cachedMarkers(): Unit {
        assertEquals(CacheState.CACHED, DebridCache.classify("Debridio ⚡ 1080p", null))
        assertEquals(CacheState.CACHED, DebridCache.classify("[RD+] Torrentio\n1080p", "Movie.2020.1080p.WEB-DL"))
        assertEquals(CacheState.CACHED, DebridCache.classify("[AD+] Comet", null))
        assertEquals(CacheState.CACHED, DebridCache.classify("Torrentio RD+ 4k", null))
        assertEquals(CacheState.CACHED, DebridCache.classify("MediaFusion Instant", null))
        assertEquals(CacheState.CACHED, DebridCache.classify("Addon", "Cached on Real-Debrid"))
    }

    @Test fun uncachedMarkers(): Unit {
        assertEquals(CacheState.UNCACHED, DebridCache.classify("Debridio ⏳ 1080p", null))
        assertEquals(CacheState.UNCACHED, DebridCache.classify("Comet ⬇️ RD", null))
        assertEquals(CacheState.UNCACHED, DebridCache.classify("[RD download] Torrentio\n1080p", null))
        assertEquals(CacheState.UNCACHED, DebridCache.classify("Addon", "Uncached · Movie.2020.1080p"))
        assertEquals(CacheState.UNCACHED, DebridCache.classify("Addon", "Not cached"), "\"Not cached\" is not \"cached\"")
        assertEquals(CacheState.UNCACHED, DebridCache.classify("Download to debrid", null))
    }

    @Test fun noMarkersOrLookalikesAreUnknown(): Unit {
        assertEquals(CacheState.UNKNOWN, DebridCache.classify("Torrentio\n1080p", "Movie.2020.1080p.WEB-DL.DDP5.1"))
        assertEquals(CacheState.UNKNOWN, DebridCache.classify("Addon", "Movie 2020 2160p HDR10+ DD+ Atmos"), "DD+ / HDR10+ are not debrid services")
        assertEquals(CacheState.UNKNOWN, DebridCache.classify("Addon", "Instant.Family.2018.1080p"), "Instant in a release name is a film")
        assertEquals(CacheState.UNKNOWN, DebridCache.classify("Addon", "Downloaded.Movie.2019"), "Downloaded is not a marker")
        assertEquals(CacheState.UNKNOWN, DebridCache.classify("[RD+] ⏳", null), "both kinds → unknown")
    }

    @Test fun onlyDirectLinksHaveACacheState(): Unit {
        assertEquals(CacheState.UNCACHED, DebridCache.of(direct("x", cache = CacheState.UNCACHED)))
        assertEquals(CacheState.UNCACHED, DebridCache.of(direct("Uncached Movie 1080p")), "fallback reads the title")
        val torrent = Source(SourceKind.TORRENT, "⏳ Movie", "Torrentio", Quality(1080), infoHash = "a".repeat(40))
        assertEquals(CacheState.UNKNOWN, DebridCache.of(torrent))
        assertEquals("Not cached", DebridCache.tag(direct("x", cache = CacheState.UNCACHED)))
        assertNull(DebridCache.tag(direct("x", cache = CacheState.CACHED)))
    }

    @Test fun stremioStreamsCarryTheirCacheState(): Unit {
        val body = """{"streams":[
            {"name":"[RD+] Torrentio\n1080p","title":"Movie.2020.1080p.WEB-DL\n👤 12 💾 2.1 GB","url":"https://a.example/1"},
            {"name":"[RD download] Torrentio\n1080p","title":"Movie.2020.1080p.BluRay","url":"https://a.example/2"},
            {"name":"Torrentio\n720p","title":"Movie.2020.720p","url":"https://a.example/3"}
        ]}"""
        val list = StremioAddon.parseStreams(body, "Torrentio")
        assertEquals(listOf(CacheState.CACHED, CacheState.UNCACHED, CacheState.UNKNOWN), list.map { it.cache })
    }

    @Test fun uncachedRanksBelowCachedOfTheSameQualityButAboveALowerResolution(): Unit {
        val cached = direct("Cached 1080p", cache = CacheState.CACHED)
        val uncached = direct("Uncached 1080p", cache = CacheState.UNCACHED)
        val cached720 = direct("Cached 720p", res = 720, cache = CacheState.CACHED)
        assertEquals(listOf(cached, uncached, cached720), SourceRanker.rank(listOf(uncached, cached720, cached)))
    }

    @Test fun sourcesPageTagsUncachedRows(): Unit {
        val v = SourceBrowser.view(direct("Movie.2020.1080p.HEVC", cache = CacheState.UNCACHED), listOf("Last played"))
        assertEquals(listOf("Last played", "Not cached", "HEVC"), v.tags)
    }

    // ------------------------------------------------------------ PlaceholderCheck

    @Test fun appliesToDirectHttpLinksOfTitlesOnly(): Unit {
        assertTrue(PlaceholderCheck.applies(direct("x"), isTitle = true))
        assertFalse(PlaceholderCheck.applies(direct("x"), isTitle = false), "a link from another app has no runtime")
        assertFalse(PlaceholderCheck.applies(direct("x").copy(extra = "Trailer"), isTitle = true))
        assertFalse(PlaceholderCheck.applies(direct("x", url = "content://x"), isTitle = true))
        assertFalse(PlaceholderCheck.applies(Source(SourceKind.TORRENT, "t", "p", Quality(), infoHash = "b".repeat(40)), isTitle = true))
    }

    @Test fun shortClipWhereAFilmIsExpected(): Unit {
        assertTrue(PlaceholderCheck.byDuration(30_000, 90 * 60_000L))
        assertTrue(PlaceholderCheck.byDuration(30_000, null), "unknown runtime: a film / episode runs over 10 min")
        assertFalse(PlaceholderCheck.byDuration(3 * 60_000L, 90 * 60_000L), "3 min or more is not a placeholder")
        assertFalse(PlaceholderCheck.byDuration(90_000, 8 * 60_000L), "a short episode")
        assertFalse(PlaceholderCheck.byDuration(90_000, 10 * 60_000L), "10 min exactly is short")
        assertFalse(PlaceholderCheck.byDuration(0, 90 * 60_000L), "no duration yet")
    }

    @Test fun expectedRuntimeIgnoresASavedPlaceholderDuration(): Unit {
        assertEquals(90 * 60_000L, PlaceholderCheck.expectedMs(90, 30_000))
        assertEquals(45 * 60_000L, PlaceholderCheck.expectedMs(null, 45 * 60_000L))
        assertNull(PlaceholderCheck.expectedMs(null, 30_000), "a 30 s history entry was a placeholder")
        assertNull(PlaceholderCheck.expectedMs(0, null))
    }

    @Test fun placeholderFileNames(): Unit {
        assertEquals("downloading", PlaceholderCheck.matchUrl("https://torrentio.example/videos/downloading_v2.mp4"))
        assertEquals("downloading", PlaceholderCheck.matchUrl("https://debridio.example/static/debridio_downloading.mp4?token=abc"))
        assertEquals("not_cached", PlaceholderCheck.matchUrl("https://a.example/media/not_cached.mp4"))
        assertEquals("not_downloaded", PlaceholderCheck.matchUrl("https://mf.example/static/exceptions/torrent_not_downloaded.mp4"))
        assertEquals("static-videos", PlaceholderCheck.matchUrl("https://comet.example/static/videos/transfer_error.mp4"))
    }

    @Test fun realFilesNeverMatch(): Unit {
        assertNull(PlaceholderCheck.matchUrl("https://dl.example/abc123/Downloading.Nancy.2019.1080p.WEB-DL.mkv"))
        assertNull(PlaceholderCheck.matchUrl("https://dl.example/d/XYZ/Movie.2020.1080p.mkv"))
        assertNull(PlaceholderCheck.matchUrl("https://dl.example/downloading/Movie.2020.1080p.mkv"), "folder names alone don't count")
        assertNull(PlaceholderCheck.matchUrl("https://dl.example/x.mp4?f=downloading.mp4"), "the query is not read")
        assertNull(PlaceholderCheck.matchUrl("https://dl.example"))
    }

    @Test fun copy(): Unit {
        assertEquals("Debridio is still downloading this to your debrid service. It's usually ready in a few minutes.", PlaceholderCheck.notReadyText("Debridio"))
        assertEquals("Debridio is still fetching that one — playing Comet 1080p instead", PlaceholderCheck.switchNote("Debridio", direct("x", provider = "Comet | RD")))
    }

    // ------------------------------------------------------------ fallback + not-ready memory

    @Test fun fallbackPicksAnotherCachedDirectLink(): Unit {
        val current = direct("current", cache = CacheState.UNCACHED)
        val uncached = direct("other uncached", cache = CacheState.UNCACHED)
        val unknown = direct("unknown")
        val cached = direct("cached 720", res = 720, cache = CacheState.CACHED)
        val torrent = Source(SourceKind.TORRENT, "t", "Torrentio", Quality(1080), infoHash = "c".repeat(40))
        val trailer = direct("trailer", cache = CacheState.CACHED).copy(extra = "Trailer")
        val ranked = listOf(current, torrent, trailer, uncached, unknown, cached)
        assertEquals(cached, PlaceholderFallback.pick(current, ranked) { false }, "cached first")
        assertEquals(unknown, PlaceholderFallback.pick(current, ranked) { it == cached.key }, "then unknown")
        assertNull(PlaceholderFallback.pick(current, ranked) { it == cached.key || it == unknown.key }, "never torrents, trailers or uncached")
    }

    @Test fun candidatesSkipWhatTheDeviceCantPlay(): Unit {
        val current = direct("current")
        val av1 = direct("av1", cache = CacheState.CACHED).copy(quality = Quality(resolution = 1080, av1 = true))
        val h264 = direct("h264")
        val ranked = listOf(current, av1, h264)
        assertEquals(listOf(av1, h264), PlaceholderFallback.candidates(current, ranked, { false }))
        assertEquals(listOf(h264), PlaceholderFallback.candidates(current, ranked, { false }, skip = { it.quality.av1 }), "no AV1 decoder: skipped")
    }

    @Test fun readyCheckReadsWhereTheLinkEnded(): Unit {
        // Debridio: an uncached link ends at its "downloading" clip; a cached one at the debrid service's file.
        assertEquals(Readiness.NOT_READY, ReadyCheck.classify("https://static.debridio.com/videos/downloading.mp4", 206))
        assertEquals(Readiness.READY, ReadyCheck.classify("https://n3zy9n.debrid.it/dl/abc/Avengers.Age.of.Ultron.2015.1080p.mkv", 206))
        assertEquals(Readiness.FAILED, ReadyCheck.classify("https://addon.debridio.com/x/play/y", 500))
        assertEquals(Readiness.FAILED, ReadyCheck.classify(null, 0))
    }

    @Test fun firstReadyWaitsForBetterRankedOnes() = runTest {
        val a = direct("a"); val b = direct("b"); val c = direct("c"); val d = direct("d")
        // b answers last but is ranked above c, so b wins; a isn't ready.
        val slow = mapOf(a to 10L, b to 300L, c to 20L, d to 5L)
        val ready = mapOf(a to Readiness.NOT_READY, b to Readiness.READY, c to Readiness.READY, d to Readiness.READY)
        val (pick, answers) = ReadyCheck.firstReady(listOf(a, b, c, d), parallel = 4) { delay(slow.getValue(it)); ready.getValue(it) }
        assertEquals(b, pick)
        assertEquals(Readiness.NOT_READY, answers[a], "the not-ready one is reported, to be marked")
    }

    @Test fun firstReadyGivesUpInTime() = runTest {
        val a = direct("a"); val b = direct("b")
        val (pick, answers) = ReadyCheck.firstReady(listOf(a, b), budgetMs = 1_000) { if (it == a) Readiness.NOT_READY else { delay(5_000); Readiness.READY } }
        assertNull(pick, "nothing ready within the budget")
        assertEquals(Readiness.NOT_READY, answers[a])
    }

    @Test fun notReadyLastsThirtyMinutes(): Unit {
        val m = NotReadySources()
        m.mark("url:a", now = 1_000)
        assertTrue(m.isNotReady("url:a", 1_000 + 29 * 60_000L))
        assertFalse(m.isNotReady("url:a", 1_000 + 30 * 60_000L))
        m.mark("url:b", now = 0)
        m.clear("url:b")
        assertFalse(m.isNotReady("url:b", 1), "Try again clears it")
    }

    @Test fun notReadySourcesGoLast(): Unit {
        val a = direct("a"); val b = direct("b"); val c = direct("c")
        assertEquals(listOf(a, c, b), NotReadySources.demote(listOf(a, b, c)) { it == b.key })
    }
}
