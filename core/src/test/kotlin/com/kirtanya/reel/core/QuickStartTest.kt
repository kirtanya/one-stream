package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.QuickStart
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.StartProbe
import com.kirtanya.reel.core.sources.StartSpeed
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TmdbParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickStartTest {
    private val gb = 1_000_000_000L

    private fun direct(name: String, size: Long? = null, url: String? = "https://cdn.example/$name.mp4", extra: String? = null) =
        Source(SourceKind.DIRECT, name, "Addon", Quality(resolution = 1080), url = url, sizeBytes = size, extra = extra)

    private fun torrent(name: String, seeds: Int?, size: Long? = null) =
        Source(SourceKind.TORRENT, name, "Torrentio", Quality(resolution = 1080), infoHash = name.hashCode().toString().padEnd(40, '0'), seeders = seeds, sizeBytes = size)

    private fun external() = Source(SourceKind.EXTERNAL, "Netflix", "WatchHub", Quality(), externalUrl = "https://netflix.com/x")

    @Test fun probeTargetsKeepOrderAndSkipUnfetchable() {
        val a = direct("a")
        val trailer = direct("t", extra = "Trailer")
        val noUrl = direct("n", url = null)
        val ftp = direct("f", url = "ftp://x/y")
        val b = direct("b")
        val c = direct("c")
        val ranked = listOf(torrent("t1", 100), a, trailer, external(), noUrl, ftp, b, c)
        assertEquals(listOf(a, b, c), QuickStart.probeTargets(ranked))
        assertEquals(listOf(a, b), QuickStart.probeTargets(ranked, max = 2))
    }

    @Test fun neededRateIsSizeOverRuntime() {
        assertEquals(1_000_000L, QuickStart.neededBytesPerSec(direct("a", size = 6 * gb), 100))
        assertNull(QuickStart.neededBytesPerSec(direct("a", size = 6 * gb), null))
        assertNull(QuickStart.neededBytesPerSec(direct("a", size = null), 100))
        assertNull(QuickStart.neededBytesPerSec(direct("a", size = 0), 100))
        assertNull(QuickStart.neededBytesPerSec(direct("a", size = gb), 0))
    }

    @Test fun directSpeedFromProbe() {
        val s = direct("a", size = 6 * gb) // needs 1 MB/s for a 100 min film
        fun sp(p: StartProbe?) = QuickStart.speed(s, p, 100)
        assertEquals(StartSpeed.UNKNOWN, sp(null))
        assertEquals(StartSpeed.FAILED, sp(StartProbe(failed = true)))
        assertEquals(StartSpeed.FAST, sp(StartProbe(ttfbMs = 400, bytesPerSec = 2_500_000)))
        assertEquals(StartSpeed.OK, sp(StartProbe(ttfbMs = 2000, bytesPerSec = 2_500_000)))
        assertEquals(StartSpeed.OK, sp(StartProbe(ttfbMs = 400, bytesPerSec = 1_300_000)))
        assertEquals(StartSpeed.SLOW, sp(StartProbe(ttfbMs = 400, bytesPerSec = 900_000)))
        assertEquals(StartSpeed.SLOW, sp(StartProbe(ttfbMs = 5000, bytesPerSec = 9_000_000)))
    }

    @Test fun directWithoutSizeUsesEightMbitDefault() {
        val s = direct("a")
        assertEquals(StartSpeed.FAST, QuickStart.speed(s, StartProbe(ttfbMs = 100, bytesPerSec = 2_000_000), 100))
        assertEquals(StartSpeed.SLOW, QuickStart.speed(s, StartProbe(ttfbMs = 100, bytesPerSec = 1_100_000), null))
    }

    @Test fun uncachedDebridLinkIsNeverBetterThanSlow() {
        val s = direct("Movie 1080p [RD download]")
        assertEquals(StartSpeed.SLOW, QuickStart.speed(s, null, 100))
        assertEquals(StartSpeed.SLOW, QuickStart.speed(s, StartProbe(ttfbMs = 50, bytesPerSec = 50_000_000), 100))
        assertEquals(StartSpeed.FAILED, QuickStart.speed(s, StartProbe(failed = true), 100))
    }

    @Test fun torrentTable() {
        fun sp(seeds: Int?, size: Long?) = QuickStart.speed(torrent("x", seeds, size), null, 100)
        assertEquals(StartSpeed.UNKNOWN, sp(null, gb))
        assertEquals(StartSpeed.FAILED, sp(0, gb))
        assertEquals(StartSpeed.SLOW, sp(3, gb))
        assertEquals(StartSpeed.OK, sp(8, gb))
        assertEquals(StartSpeed.SLOW, sp(8, 4 * gb))
        assertEquals(StartSpeed.SLOW, sp(8, null))
        assertEquals(StartSpeed.OK, sp(20, 8 * gb))
        assertEquals(StartSpeed.FAST, sp(30, 2 * gb))
        assertEquals(StartSpeed.OK, sp(30, 4 * gb))
        assertEquals(StartSpeed.FAST, sp(50, 20 * gb))
    }

    @Test fun externalAndExtrasAreUnknown() {
        assertEquals(StartSpeed.UNKNOWN, QuickStart.speed(external(), null, 100))
        assertEquals(StartSpeed.UNKNOWN, QuickStart.speed(direct("t", extra = "Trailer"), StartProbe(ttfbMs = 1, bytesPerSec = 99_000_000), 100))
    }

    @Test fun wantedLanguages() {
        assertEquals(listOf("English", "Hindi"), QuickStart.wantedLanguages("en"))
        assertEquals(listOf("English", "Hindi"), QuickStart.wantedLanguages(null))
        assertEquals(listOf("English", "Hindi"), QuickStart.wantedLanguages("ko"))
        assertEquals(listOf("Hindi", "English"), QuickStart.wantedLanguages("hi"))
    }

    @Test fun recommendPutsFastWantedLanguageFirst() {
        val plainOk = torrent("Movie 2024 1080p WEB", 20, 3 * gb)                  // OK, unnamed
        val hindiFast = torrent("Movie 2024 1080p Hindi", 300, gb)                  // FAST, Hindi
        val englishFast = torrent("Movie 2024 1080p English", 300, gb)              // FAST, English
        val frenchFast = torrent("Movie 2024 1080p French", 300, gb)                // wrong language
        val multiFast = torrent("Movie 2024 1080p Multi", 300, gb)                  // FAST, Multi
        val slow = torrent("Movie 2024 1080p English slow", 2, gb)                  // too slow
        val failed = torrent("Movie 2024 1080p English dead", 0, gb)
        val ranked = listOf(frenchFast, plainOk, slow, multiFast, hindiFast, failed, englishFast, external())
        val en = QuickStart.recommend(ranked, emptyMap(), 100, listOf("English", "Hindi"))
        assertEquals(listOf(englishFast, hindiFast, multiFast, plainOk), en)
        val hi = QuickStart.recommend(ranked, emptyMap(), 100, listOf("Hindi", "English"))
        assertEquals(listOf(hindiFast, englishFast, multiFast, plainOk), hi)
    }

    @Test fun recommendUsesProbesCapsAtFiveAndMayBeEmpty() {
        val links = (1..8).map { direct("d$it", size = 6 * gb) }
        val probes = links.associate { QuickStart.key(it) to StartProbe(ttfbMs = 300, bytesPerSec = 2_000_000) }
        val rec = QuickStart.recommend(links, probes, 100, listOf("English", "Hindi"))
        assertEquals(QuickStart.RECOMMENDED, rec.size)
        assertEquals(links.take(5), rec)
        assertTrue(QuickStart.recommend(links, emptyMap(), 100, listOf("English")).isEmpty())
        val slow = links.associate { QuickStart.key(it) to StartProbe(ttfbMs = 300, bytesPerSec = 100_000) }
        assertTrue(QuickStart.recommend(links, slow, 100, listOf("English")).isEmpty())
    }

    @Test fun originalLanguageIsParsed() {
        val body = javaClass.getResource("/tmdb/movie.json")!!.readText()
        assertNull(TmdbParser.parseDetails(body, MediaType.MOVIE).originalLanguage)
        val withLang = "{\"original_language\":\"hi\"," + body.trimStart().removePrefix("{")
        assertEquals("hi", TmdbParser.parseDetails(withLang, MediaType.MOVIE).originalLanguage)
    }
}
