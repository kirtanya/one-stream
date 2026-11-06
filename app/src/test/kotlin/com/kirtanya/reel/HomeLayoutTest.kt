package com.kirtanya.reel

import com.kirtanya.reel.ui.home.RowIds
import com.kirtanya.reel.ui.home.TvCardShape
import com.kirtanya.reel.ui.home.TvCardSize
import com.kirtanya.reel.ui.home.TvCards
import com.kirtanya.reel.ui.home.dropRemoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    /** A layout saved before "On your services" was removed still lists it: it must just vanish. */
    @Test fun savedLayoutDropsRemovedServicesRow() {
        val saved = listOf(RowIds.CONTINUE, "services", RowIds.tmdb("Trending this week"), "")
        assertEquals(listOf(RowIds.CONTINUE, RowIds.tmdb("Trending this week")), dropRemoved(saved))
        assertEquals(emptyList<String>(), dropRemoved(setOf("services")))
        assertFalse("services" in RowIds.DEFAULT)
    }

    /** Sizes grow with Small < Medium < Large in both shapes; the default (landscape, small) is under today's cards. */
    @Test fun cardSizesGrow() {
        for (shape in listOf(TvCards::landscapeWidth, TvCards::portraitWidth)) {
            val w = TvCardSize.entries.map { shape(it).value }
            assertEquals(w.sorted(), w)
            assertEquals(w.distinct(), w)
        }
        // Today's TV cards: 112 dp posters, 240 dp wide cards.
        assertTrue(TvCards.landscapeWidth(TvCardSize.Small).value < 240f)
        assertEquals(TvCardShape.entries.size, 2)
    }

    /** Landscape cards use w500 backdrops (2 px/dp: up to 250 dp), portrait w342 posters (up to 171 dp). */
    @Test fun imageSizesCoverTheCards() {
        assertTrue(TvCardSize.entries.all { TvCards.landscapeWidth(it).value <= 250f })
        assertTrue(TvCardSize.entries.all { TvCards.portraitWidth(it).value <= 171f })
    }
}
