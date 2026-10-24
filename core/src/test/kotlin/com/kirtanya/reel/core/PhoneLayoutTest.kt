package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.PhoneLayout
import com.kirtanya.reel.core.player.PhoneLayout.Side
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class PhoneLayoutTest {
    @Test fun storedNamesRoundTripAndDefaultToClassic() {
        assertEquals(PhoneLayout.EDGE, PhoneLayout.of("EDGE"))
        assertEquals(PhoneLayout.CLASSIC, PhoneLayout.of("CLASSIC"))
        assertEquals(PhoneLayout.CLASSIC, PhoneLayout.of(null))
        assertEquals(PhoneLayout.CLASSIC, PhoneLayout.of("bogus"))
    }

    @Test fun edgeOnlyInLandscape() {
        assertEquals(PhoneLayout.EDGE, PhoneLayout.effective(PhoneLayout.EDGE, landscape = true))
        assertEquals(PhoneLayout.CLASSIC, PhoneLayout.effective(PhoneLayout.EDGE, landscape = false))
        assertEquals(PhoneLayout.CLASSIC, PhoneLayout.effective(PhoneLayout.CLASSIC, landscape = true))
    }

    @Test fun transportGoesOnTheCutoutSide() {
        assertEquals(Side.LEFT, PhoneLayout.cutoutSide(96, 0))
        assertEquals(Side.RIGHT, PhoneLayout.cutoutSide(0, 96))
        // No cutout, or one on a long edge: left.
        assertEquals(Side.LEFT, PhoneLayout.cutoutSide(0, 0))
    }

    @Test fun stackCentresWithGapsWhenThereIsRoom() {
        // 3 × 48 + 2 × 8 = 160 around 180 → 100, 156, 212.
        assertContentEquals(intArrayOf(100, 156, 212), PhoneLayout.stack(3, 48, 8, top = 0, bottom = 360, centre = 180))
    }

    @Test fun stackStaysAboveTheBottomLimit() {
        // 5 × 48 + 4 × 8 = 272, centred on 180 would end at 316 > 296: pushed up to end at 296.
        assertContentEquals(intArrayOf(24, 80, 136, 192, 248), PhoneLayout.stack(5, 48, 8, top = 8, bottom = 296, centre = 180))
    }

    @Test fun stackDropsGapsBeforeTouchAreasShrink() {
        // 5 × 48 + 4 × 8 = 272 > 250 of room: touching (240), still inside.
        assertContentEquals(intArrayOf(15, 63, 111, 159, 207), PhoneLayout.stack(5, 48, 8, top = 10, bottom = 260, centre = 135))
        // Taller than the room: starts at the top, never squeezed.
        assertContentEquals(intArrayOf(0, 48, 96), PhoneLayout.stack(3, 48, 8, top = 0, bottom = 100, centre = 50))
        assertEquals(0, PhoneLayout.stack(0, 48, 8, 0, 100, 50).size)
    }

    @Test fun skipButtonsFlankTheHole() {
        // Hole 6..34 × 166..194 (centre x 20) on an 800 × 360 screen; play 44..92 × 156..204 beside it.
        val f = PhoneLayout.flankHole(6, 166, 34, 194, size = 48, gap = 8, minX = 0, maxX = 800,
            playTop = 156, playBottom = 204, top = 64, bottom = 312)!!
        // Centred on x 20 would start at −4: kept on screen.
        assertEquals(0, f.x)
        // 8 dp above the hole would end at 158, inside play's rows (from 156): pushed up to end at 156.
        assertEquals(108, f.aboveTop)
        // 8 dp below the hole is 202, inside play's rows (to 204): pushed down to start at 204.
        assertEquals(204, f.belowTop)
    }

    @Test fun flankKeepsTheGapFromABigHole() {
        // A tall hole 150..210: the gap decides, play (160..200) is inside its rows.
        val f = PhoneLayout.flankHole(40, 150, 80, 210, size = 48, gap = 8, minX = 0, maxX = 800,
            playTop = 160, playBottom = 200, top = 0, bottom = 360)!!
        assertEquals(36, f.x)
        assertEquals(94, f.aboveTop)
        assertEquals(218, f.belowTop)
    }

    @Test fun noFlankWithoutAHoleOrRoom() {
        assertNull(PhoneLayout.flankHole(0, 0, 0, 0, 48, 8, 0, 800, 156, 204, 0, 360))
        // Too little room above: the plain stack stays.
        assertNull(PhoneLayout.flankHole(6, 166, 34, 194, 48, 8, 0, 800, 156, 204, top = 120, bottom = 312))
    }

    @Test fun oneLineThroughTheHoleWithRoom() {
        // Hole 6..34 × 100..128; buttons from 136, 8 apart: 136, 192, 248 (ends 296 ≤ 352).
        val c = PhoneLayout.holeColumn(6, 100, 34, 128, count = 3, size = 48, gap = 8, minX = 0, maxX = 800, top = 8, bottom = 352)!!
        assertEquals(0, c.x) // centred on 20 would start at −4: kept on screen
        assertEquals(8, c.backTop)
        assertContentEquals(intArrayOf(136, 192, 248), c.tops)
    }

    @Test fun oneLineCompressesTheSpacingOnTheUsersPhone() {
        // 360 dp tall, hole 169..191 in the middle: 199 + 3 × 48 = 343, 9 spare of 352 → 4 apart.
        val c = PhoneLayout.holeColumn(9, 169, 31, 191, count = 3, size = 48, gap = 8, minX = 0, maxX = 800, top = 8, bottom = 352)!!
        assertContentEquals(intArrayOf(199, 251, 303), c.tops)
        // Right side: centred on the hole (x 789 → 765) but kept inside 800.
        val r = PhoneLayout.holeColumn(769, 169, 791, 191, 3, 48, 8, minX = 0, maxX = 800, top = 8, bottom = 352)!!
        assertEquals(752, r.x)
        // Exactly no spare: touching, no overlap.
        assertContentEquals(intArrayOf(199, 247, 295), PhoneLayout.holeColumn(9, 169, 31, 191, 3, 48, 8, 0, 800, 8, 343)!!.tops)
    }

    @Test fun shortScreenFallsBackToTheFlank() {
        // 3 × 48 = 144 needed under the hole but only 120 left: no line, the previous arrangement instead.
        assertNull(PhoneLayout.holeColumn(6, 166, 34, 194, 3, 48, 8, 0, 800, top = 8, bottom = 322))
        val f = PhoneLayout.flankHole(6, 166, 34, 194, 48, 8, 0, 800, playTop = 156, playBottom = 204, top = 64, bottom = 312)
        assertEquals(204, f!!.belowTop)
        // Back can't sit above a hole that high: no line either.
        assertNull(PhoneLayout.holeColumn(6, 30, 34, 58, 3, 48, 8, 0, 800, top = 8, bottom = 352))
        // No hole.
        assertNull(PhoneLayout.holeColumn(0, 0, 0, 0, 3, 48, 8, 0, 800, 8, 352))
    }
}
