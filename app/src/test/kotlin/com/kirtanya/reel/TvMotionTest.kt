package com.kirtanya.reel

import androidx.compose.foundation.ExperimentalFoundationApi
import com.kirtanya.reel.ui.tv.ColumnGlideSpec
import com.kirtanya.reel.ui.tv.IdleWatch
import com.kirtanya.reel.ui.tv.RowPivotSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's scroll maths: where a focused item ends up (docs/SMOOTH_UI_PLAN.md §3). */
@OptIn(ExperimentalFoundationApi::class)
class TvMotionTest {
    // A 540 px tall page, items asked about as (offset from the top, size).
    private val glide = ColumnGlideSpec(revealPx = 80f)

    @Test fun `an item already in view with its caption does not scroll`() {
        assertEquals(0f, glide.calculateScrollDistance(offset = 100f, size = 150f, containerSize = 540f), 0f)
    }

    @Test fun `an item whose caption is cut off scrolls just far enough`() {
        // Item ends at 500; with 80 px of caption it needs to end at 540 - 80 = 460: 40 px.
        assertEquals(40f, glide.calculateScrollDistance(offset = 350f, size = 150f, containerSize = 540f), 0f)
    }

    @Test fun `an item above the top scrolls back up to its own top`() {
        assertEquals(-60f, glide.calculateScrollDistance(offset = -60f, size = 150f, containerSize = 540f), 0f)
    }

    @Test fun `scrolling down never goes past the item's own top`() {
        // Taller than the page: align its top, don't scroll the item out of sight.
        assertEquals(30f, glide.calculateScrollDistance(offset = 30f, size = 800f, containerSize = 540f), 0f)
    }

    @Test fun `a row pivots the focused card to its start line`() {
        val row = RowPivotSpec(startPx = 96f)
        assertEquals(0f, row.calculateScrollDistance(offset = 96f, size = 224f, containerSize = 1920f), 0f)
        assertEquals(500f, row.calculateScrollDistance(offset = 596f, size = 224f, containerSize = 1920f), 0f)
        assertEquals(-96f, row.calculateScrollDistance(offset = 0f, size = 224f, containerSize = 1920f), 0f)
    }

    @Test fun `a key press moves no state, only the clock`() {
        val w = IdleWatch()
        val before = w.lastKeyAt
        Thread.sleep(2)
        w.lastKeyAt = System.currentTimeMillis()
        assertTrue(w.lastKeyAt > before)
        assertEquals(false, w.dreaming)
    }
}
