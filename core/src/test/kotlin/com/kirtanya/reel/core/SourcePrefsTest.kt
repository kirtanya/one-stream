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
        val hindi = t("Movie 2024 1080p Hindi", seeds = 300)
        val plain = t("Movie 2024 1080p WEB", seeds = 100)
        val eng = t("Movie 2024 1080p English ESub", seeds = 100)
        val multi = t("Movie 2024 1080p Multi", seeds = 100)
        assertEquals(listOf(eng, multi, plain, hindi), SourceRanker.rank(listOf(hindi, plain, eng, multi)))
    }

    @Test fun hindiPlusEnglishCountsAsEnglish(): Unit {
        assertEquals(30.0, SourceRanker.languageScore(t("Movie Hindi + English 1080p"), "English"))
        assertEquals(0.0, SourceRanker.languageScore(t("Movie Hindi"), null))
    }
}
