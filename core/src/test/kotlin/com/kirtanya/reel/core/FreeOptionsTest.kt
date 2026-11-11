package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.TitleSources
import com.kirtanya.reel.core.sources.FreeOptions
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.core.tmdb.WatchProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Free on <Service>" rows: legal free options from TMDB's India "where to watch", and the TV row's Hindi tag. */
class FreeOptionsTest {
    private fun p(id: Int, name: String) = Provider(id, name, null)

    @Test fun freeThenAdsOnceEachAndNothingPaid() {
        val w = WatchProviders(
            link = null,
            stream = listOf(p(8, "Netflix")), // subscription: not free
            free = listOf(p(2336, "JioHotstar"), p(300, "YouTube")),
            ads = listOf(p(300, "YouTube"), p(515, "MX Player")),
            rent = listOf(p(3, "Google Play Movies")),
        )
        val o = FreeOptions.of(w)
        assertEquals(listOf("Free on JioHotstar", "Free on YouTube", "Free on MX Player"), o.map { it.title })
        assertEquals(listOf(false, false, true), o.map { it.withAds })
        assertEquals("Opens in its app", o[0].detail)
        assertEquals("Opens in its app · with ads", o[2].detail)
    }

    @Test fun paidTiersThatCarryAdsAreNotFree() {
        val w = WatchProviders(link = null, ads = listOf(p(1, "Netflix Standard with Ads"), p(2, "Amazon Prime Video with Ads"), p(3, "Hoichoi Amazon Channel"), p(4, "Tubi")))
        assertEquals(listOf("Tubi"), FreeOptions.of(w).map { it.provider.name })
    }

    @Test fun cappedSoTheySitAboveTheSourcesQuietly() {
        val w = WatchProviders(link = null, free = (1..6).map { p(it, "Service $it") })
        assertEquals(FreeOptions.MAX, FreeOptions.of(w).size)
        assertTrue(FreeOptions.of(WatchProviders.NONE).isEmpty())
        assertTrue(FreeOptions.of(WatchProviders(link = null, stream = listOf(p(8, "Netflix")))).isEmpty())
    }

    @Test fun tvRowTagsHindiAndDoesNotSayItTwice() {
        fun src(file: String) = Source(
            SourceKind.TORRENT, "Film.2024.1080p", "Torrentio", Quality(1080), infoHash = "%040x".format(file.hashCode()), seeders = 142, filename = file,
        )
        val both = TitleSources.row(src("Film.[Hindi + English].mkv"), fits = true, resume = null)
        assertTrue(both.hindi)
        assertEquals("Torrentio · 142 seeders · English", both.detail)
        val only = TitleSources.row(src("Film.Hindi.mkv"), fits = true, resume = null)
        assertTrue(only.hindi)
        assertEquals("Torrentio · 142 seeders", only.detail)
        val multi = TitleSources.row(src("Film.MULTi.mkv"), fits = true, resume = null)
        assertFalse(multi.hindi, "Multi audio is not a claim about Hindi")
        assertEquals("Torrentio · 142 seeders · Multi", multi.detail)
        val plain = TitleSources.row(src("Film.ENG.mkv"), fits = true, resume = null)
        assertFalse(plain.hindi)
    }
}
