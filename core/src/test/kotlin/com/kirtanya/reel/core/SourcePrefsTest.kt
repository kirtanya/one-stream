package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.RankPrefs
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourcePrefs
import com.kirtanya.reel.core.sources.SourceRanker
import kotlin.test.Test
import kotlin.test.assertEquals

class SourcePrefsTest {
    private fun t(provider: String, res: Int, seeds: Int = 100, cam: Boolean = false) =
        Source(SourceKind.TORRENT, "$provider $res", provider, Quality(resolution = res, lowQualityRip = cam), infoHash = "$provider$res".padEnd(40, '0'), seeders = seeds)

    @Test fun capPutsSharperSourcesLast(): Unit {
        val uhd = t("Torrentio", 2160, 500)
        val fhd = t("Torrentio", 1080, 20)
        val prefs = SourcePrefs(maxResolution = 1080).applyTo(RankPrefs(preferredResolution = 2160))
        assertEquals(listOf(fhd, uhd), SourceRanker.rank(listOf(uhd, fhd), prefs))
    }

    @Test fun neverCamAndBlockedAreLeftOut(): Unit {
        val cam = t("Torrentio", 1080, cam = true)
        val blocked = t("Peerflix", 1080)
        val ok = t("Comet", 720)
        val prefs = SourcePrefs(neverCam = true, blocked = setOf("Peerflix")).applyTo(RankPrefs())
        assertEquals(listOf(ok), SourceRanker.rank(listOf(cam, blocked, ok), prefs))
    }

    @Test fun pinnedWinsAmongEquals(): Unit {
        val a = t("Torrentio", 1080)
        val b = t("Comet", 1080)
        assertEquals(b, SourceRanker.rank(listOf(a, b), SourcePrefs(pinned = setOf("Comet")).applyTo(RankPrefs())).first())
    }

    @Test fun pinAndBlockExcludeEachOther(): Unit {
        val p = SourcePrefs().pin("Comet", true).block("Comet", true)
        assertEquals(setOf("Comet"), p.blocked)
        assertEquals(emptySet(), p.pinned)
    }
}

class EnglishFirstTest {
    private fun t(title: String, seeds: Int = 100) =
        Source(SourceKind.TORRENT, title, "Torrentio", Quality(resolution = 1080), infoHash = title.hashCode().toString().padEnd(40, '0'), seeders = seeds)

    @Test fun englishBeatsDubbedAmongEquals(): Unit {
        val hindi = t("Movie 2024 1080p Hindi", seeds = 100)
        val plain = t("Movie 2024 1080p WEB", seeds = 100)
        val eng = t("Movie 2024 1080p English ESub", seeds = 100)
        val multi = t("Movie 2024 1080p Multi", seeds = 100)
        assertEquals(listOf(eng, hindi, multi, plain), SourceRanker.rank(listOf(hindi, plain, eng, multi)))
    }

    @Test fun hindiIsAcceptableButOtherLanguagesAreNot(): Unit {
        val hindi = t("Movie 2024 1080p Hindi", seeds = 100)
        val french = t("Movie 2024 1080p French", seeds = 100)
        val plain = t("Movie 2024 1080p WEB", seeds = 100)
        assertEquals(20.0, SourceRanker.languageScore(hindi, "English", listOf("Hindi")))
        assertEquals(-80.0, SourceRanker.languageScore(french, "English", listOf("Hindi")))
        assertEquals(-80.0, SourceRanker.languageScore(hindi, "English"))
        assertEquals(listOf(hindi, plain, french), SourceRanker.rank(listOf(french, hindi, plain)))
        assertEquals(listOf(plain, french, hindi), SourceRanker.rank(listOf(french, hindi, plain), RankPrefs(alsoLanguages = emptyList())))
    }

    @Test fun veryFewSeedersRankBelowASmallHealthySwarm(): Unit {
        val three = t("Movie 2024 1080p WEB", seeds = 3)
        val ten = t("Movie 2024 1080p WEB x", seeds = 10)
        val none = t("Movie 2024 1080p WEB y", seeds = 0)
        assertEquals(listOf(ten, three, none), SourceRanker.rank(listOf(none, three, ten)))
        // 4 seeders is now clearly below 5 (the old log curve gave 4 -> 5 only +3).
        val gap = SourceRanker.score(t("a 1080p", seeds = 5), RankPrefs()) - SourceRanker.score(t("a 1080p", seeds = 4), RankPrefs())
        assertEquals(true, gap > 40.0)
    }

    @Test fun hindiPlusEnglishCountsAsEnglish(): Unit {
        assertEquals(30.0, SourceRanker.languageScore(t("Movie Hindi + English 1080p"), "English"))
        assertEquals(0.0, SourceRanker.languageScore(t("Movie Hindi"), null))
    }
}
