package com.kirtanya.reel.ui.phone

import androidx.compose.ui.input.key.Key
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** 5 per row, 12 cards: rows 0–4, 5–9, 10–11. */
class SearchGridMovesTest {
    private fun go(i: Int, key: Key) = SearchGridMoves.target(i, cols = 5, size = 12, key = key)

    @Test fun downIsStraightBelow() {
        assertEquals(8 as Int?, go(3, Key.DirectionDown))
        assertEquals(11 as Int?, go(8, Key.DirectionDown)) // short last row: its last card
        assertEquals(10 as Int?, go(10, Key.DirectionDown)) // last row: stays
    }

    @Test fun upIsStraightAboveAndLeavesFromTheTopRow() {
        assertEquals(6 as Int?, go(11, Key.DirectionUp))
        assertNull(go(2, Key.DirectionUp))
    }

    @Test fun rightWrapsToNextRowStart() {
        assertEquals(5 as Int?, go(4, Key.DirectionRight))
        assertEquals(10 as Int?, go(9, Key.DirectionRight))
        assertNull(go(2, Key.DirectionRight)) // mid-row: the normal move
        assertEquals(11 as Int?, go(11, Key.DirectionRight)) // very last card stays
    }

    @Test fun leftIsTheNormalMove() = assertNull(go(5, Key.DirectionLeft))
}
