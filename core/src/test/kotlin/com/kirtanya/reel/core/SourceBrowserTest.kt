package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.QualityBucket
import com.kirtanya.reel.core.sources.SeedHealth
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceBrowser
import com.kirtanya.reel.core.sources.SourceFacts
import com.kirtanya.reel.core.sources.SourceFilters
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.sources.SourceSort
import com.kirtanya.reel.core.sources.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceBrowserTest {
    private fun src(
        title: String,
        res: Int? = 1080,
        kind: SourceKind = SourceKind.TORRENT,
        provider: String = "Torrentio",
        seeders: Int? = 100,
        size: Long? = 2_000_000_000L,
        n: Int = title.hashCode(),
    ) = Source(
        kind = kind, title = title, provider = provider, quality = Quality(resolution = res),
        infoHash = if (kind == SourceKind.TORRENT) "%040x".format(n.toLong() and 0xffffffffL) else null,
        url = if (kind == SourceKind.DIRECT) "https://example.org/$n.mp4" else null,
        externalUrl = if (kind == SourceKind.EXTERNAL) "https://service.example/$n" else null,
        seeders = if (kind == SourceKind.TORRENT) seeders else null, sizeBytes = size,
    )

    // ─── Facts ──────────────────────────────────────────────────────────────────────────────

    @Test fun tagsFromARemuxName() {
        val tags = SourceFacts.tags("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FraMeSToR")
        assertEquals(listOf("Dolby Vision", "HDR", "HEVC", "Atmos", "TrueHD", "7.1", "REMUX", "BluRay"), tags)
    }

    @Test fun tagsFromAWebName() {
        assertEquals(listOf("Atmos", "DD+", "5.1", "WEB-DL"), SourceFacts.tags("Dune.Part.Two.2024.1080p.AMZN.WEB-DL.DDP5.1.Atmos.H.264-FLUX"))
        assertEquals(listOf("HDR10+", "10-bit", "HEVC", "WEBRip"), SourceFacts.tags("Show S01E01 2160p WEBRip HDR10+ 10bit x265"))
        assertEquals(listOf("DTS-HD", "BluRay"), SourceFacts.tags("Film 1080p BluRay DTS-HD MA"))
        assertEquals(listOf("CAM"), SourceFacts.tags("Dune.Part.Two.2024.HDCAM.x264"))
    }

    @Test fun tagsIgnoreWordsInsideOtherWords() {
        // "Atmosphere", "Advent", "Dvd", "Tsunami" must not become tags.
        assertEquals(emptyList(), SourceFacts.tags("Atmosphere Advent Tsunami 1080p"))
        assertEquals(emptyList(), SourceFacts.tags("Episode 5 1080p"))
    }

    @Test fun languagesFromWordsAndFlags() {
        assertEquals(listOf("Hindi", "English"), SourceFacts.languages("Movie 2024 1080p [Hindi + English] x264"))
        assertEquals(listOf("Italian", "Spanish").sorted(), SourceFacts.languages("Film 1080p\n🇮🇹 / 🇪🇸").sorted())
        assertEquals(listOf("Multi"), SourceFacts.languages("Film.2023.MULTi.1080p"))
        assertEquals(listOf("Italian", "English"), SourceFacts.languages("Film.ITA.ENG.1080p"))
        // Only the release text: a provider listing languages isn't read.
        val s = src("Film 1080p", provider = "MediaFusion 🇮🇳 (Hindi, Tamil)")
        assertEquals(emptyList(), SourceFacts.languages(s))
    }

    @Test fun languagesNeedWholeWords() {
        // Inside words, or lower-case codes ("por" is Spanish for "for"): not languages.
        assertEquals(emptyList(), SourceFacts.languages("Hotel Tango Kanye Por Favor ita 1080p"))
    }

    @Test fun providerLabels() {
        assertEquals("Comet", SourceFacts.providerLabel("Comet | Real-Debrid"))
        assertEquals("MediaFusion", SourceFacts.providerLabel("MediaFusion 🇮🇳 (Hindi, Tamil, Telugu)"))
        assertEquals("Internet Archive", SourceFacts.providerLabel("Built-in: Internet Archive"))
        assertEquals("Torrentio", SourceFacts.providerLabel("Torrentio"))
    }

    @Test fun seedHealthBands() {
        assertEquals(SeedHealth.STRONG, SeedHealth.of(src("a", seeders = 50)))
        assertEquals(SeedHealth.OK, SeedHealth.of(src("a", seeders = 10)))
        assertEquals(SeedHealth.WEAK, SeedHealth.of(src("a", seeders = 1)))
        assertEquals(SeedHealth.NONE, SeedHealth.of(src("a", seeders = 0)))
        assertEquals(SeedHealth.UNKNOWN, SeedHealth.of(src("a", seeders = null)))
        assertNull(SeedHealth.of(src("a", kind = SourceKind.DIRECT)))
    }

    @Test fun qualityBuckets() {
        assertEquals(QualityBucket.UHD, QualityBucket.of(Quality(2160)))
        assertEquals(QualityBucket.FHD, QualityBucket.of(Quality(1440)))
        assertEquals(QualityBucket.HD, QualityBucket.of(Quality(720)))
        assertEquals(QualityBucket.SD, QualityBucket.of(Quality(480)))
        assertEquals(QualityBucket.SD, QualityBucket.of(Quality(null)))
    }

    // ─── Recommended, facets, filters, sort ────────────────────────────────────────────────

    private val ranked = listOf(
        src("Film 1080p WEB-DL [English]", 1080, provider = "Torrentio", seeders = 400, size = 3_000_000_000L, n = 1),
        src("Film 2160p REMUX [Hindi]", 2160, provider = "Comet | RD", seeders = 20, size = 40_000_000_000L, n = 2),
        src("Film 720p", 720, kind = SourceKind.DIRECT, provider = "Built-in: Internet Archive", size = 900_000_000L, n = 3),
        src("Film 1080p x265", 1080, provider = "Torrentio", seeders = 0, size = null, n = 4),
        src("Watch on a service", null, kind = SourceKind.EXTERNAL, provider = "WatchHub", size = null, n = 5),
    )

    @Test fun recommendedSkipsServices() {
        val services = listOf(src("svc", kind = SourceKind.EXTERNAL, n = 9)) + ranked
        assertEquals(ranked[0], SourceBrowser.recommended(services))
        assertNull(SourceBrowser.recommended(listOf(src("svc", kind = SourceKind.EXTERNAL))))
    }

    @Test fun recommendedToPlayIsOneThisDeviceCanStream() {
        val small = { s: com.kirtanya.reel.core.sources.Source -> (s.sizeBytes ?: 0L) < 10_000_000_000L }
        val pick = SourceBrowser.recommended(ranked, small)
        assertTrue(pick != null && small(pick), "not the 40 GB REMUX: ${pick?.title}")
        assertEquals(SourceBrowser.recommended(ranked), SourceBrowser.recommended(ranked) { true }, "downloading: the ranker's winner")
        assertEquals(ranked[0], SourceBrowser.recommended(ranked.take(1)) { false }, "nothing fits: still the winner (with its warning)")
    }

    @Test fun facetsOnlyWhereThereIsAChoice() {
        val rows = ranked.map(SourceBrowser::view)
        val f = SourceBrowser.facets(rows)
        assertEquals(listOf("4K" to 1, "1080p" to 2, "720p" to 1, "SD" to 1), f.qualities.map { it.label to it.count })
        assertEquals(listOf("Torrentio" to 2), f.providers.take(1).map { it.label to it.count })
        assertEquals(4, f.providers.size)
        assertEquals(listOf(SourceType.TORRENT, SourceType.DIRECT, SourceType.SERVICE), f.types.map { it.value })
        assertEquals(setOf("English", "Hindi"), f.languages.map { it.value }.toSet())

        // One provider, one type, no languages: only quality chips.
        val single = listOf(ranked[0].copy(title = "a 1080p"), ranked[3]).map(SourceBrowser::view)
        val g = SourceBrowser.facets(single)
        assertTrue(g.providers.isEmpty() && g.types.isEmpty() && g.languages.isEmpty())
        assertEquals(1, g.qualities.size)
    }

    @Test fun oneNamedLanguageStillSplitsTheList() {
        val rows = listOf(ranked[0], ranked[3]).map(SourceBrowser::view)
        assertEquals(listOf("English"), SourceBrowser.facets(rows).languages.map { it.value })
        // …but not when every row says the same language.
        assertTrue(SourceBrowser.facets(listOf(ranked[0]).map(SourceBrowser::view)).languages.isEmpty())
    }

    @Test fun filtersOrWithinAGroupAndAcrossGroups() {
        val rows = ranked.map(SourceBrowser::view)
        fun titles(f: SourceFilters) = SourceBrowser.apply(rows, f).map { it.source.title }
        assertEquals(5, titles(SourceFilters()).size)
        assertEquals(listOf("Film 1080p WEB-DL [English]", "Film 2160p REMUX [Hindi]", "Film 1080p x265"),
            titles(SourceFilters(qualities = setOf(QualityBucket.UHD, QualityBucket.FHD))))
        assertEquals(listOf("Film 1080p WEB-DL [English]", "Film 1080p x265"),
            titles(SourceFilters(qualities = setOf(QualityBucket.UHD, QualityBucket.FHD), providers = setOf("Torrentio"))))
        assertEquals(listOf("Film 2160p REMUX [Hindi]"), titles(SourceFilters(languages = setOf("Hindi"))))
        assertEquals(listOf("Film 720p"), titles(SourceFilters(types = setOf(SourceType.DIRECT))))
        assertEquals(emptyList(), titles(SourceFilters(qualities = setOf(QualityBucket.HD), types = setOf(SourceType.TORRENT))))
    }

    @Test fun sorts() {
        val rows = ranked.map(SourceBrowser::view)
        val smallest = SourceBrowser.apply(rows, SourceFilters(sort = SourceSort.SMALLEST)).map { it.source.title }
        assertEquals(listOf("Film 720p", "Film 1080p WEB-DL [English]", "Film 2160p REMUX [Hindi]"), smallest.take(3))
        // Unknown sizes go last, in ranked order.
        assertEquals(listOf("Film 1080p x265", "Watch on a service"), smallest.drop(3))
        val seeds = SourceBrowser.apply(rows, SourceFilters(sort = SourceSort.SEEDS)).map { it.source.title }
        assertEquals(listOf("Film 1080p WEB-DL [English]", "Film 2160p REMUX [Hindi]", "Film 720p", "Film 1080p x265", "Watch on a service"), seeds)
    }

    @Test fun pruneDropsChipsThatDisappeared() {
        val rows = ranked.take(2).map(SourceBrowser::view)
        val f = SourceFilters(qualities = setOf(QualityBucket.HD, QualityBucket.UHD), providers = setOf("Gone"), sort = SourceSort.SEEDS)
        val pruned = SourceBrowser.prune(f, SourceBrowser.facets(rows))
        assertEquals(setOf(QualityBucket.UHD), pruned.qualities)
        assertTrue(pruned.providers.isEmpty())
        assertEquals(SourceSort.SEEDS, pruned.sort)
        assertFalse(SourceFilters(sort = SourceSort.SEEDS).active)
        assertEquals(SourceFilters(sort = SourceSort.SEEDS), f.cleared())
    }

    @Test fun statusLines() {
        assertEquals("Getting your sources ready…", SourceBrowser.status(null).headline)
        val searching = SourceBrowser.status(SourceSearch(ranked.take(4), pending = listOf("Torrentio", "Comet", "MediaFusion"), failed = emptyMap(), total = 7))
        assertEquals("Searching · 4 of 7 · 4 results", searching.headline)
        assertEquals("Still searching: Torrentio, Comet +1", searching.detail)
        assertEquals(4f / 7, searching.progress)
        val done = SourceBrowser.status(SourceSearch(ranked.take(1), pending = emptyList(), failed = mapOf("Orion" to "HTTP 403"), total = 1))
        assertEquals("1 result from 1 source", done.headline)
        assertEquals("Didn't answer: Orion", done.detail)
        assertNull(done.progress)
        assertNull(SourceBrowser.status(SourceSearch(emptyList(), emptyList(), emptyMap(), 0)).progress)
    }
}
