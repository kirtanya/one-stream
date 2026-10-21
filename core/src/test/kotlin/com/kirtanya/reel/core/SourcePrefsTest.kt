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
