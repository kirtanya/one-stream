package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.PhoneLayout
import com.kirtanya.reel.core.player.PhoneLayout.Side
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
}
