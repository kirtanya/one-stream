package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.Ambient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientTest {
    @Test fun barsOnlyWhenTheVideoLeavesThem() {
        assertFalse(Ambient.hasBars(1920, 1080, 1920, 1080), "16:9 on a 16:9 TV fills it")
        assertTrue(Ambient.hasBars(1920, 800, 1920, 1080), "2.4:1 film: bars top and bottom")
        assertTrue(Ambient.hasBars(1440, 1080, 1920, 1080), "4:3: bars at the sides")
        assertFalse(Ambient.hasBars(1920, 1070, 1920, 1080), "under 3 % of the screen: not worth it")
        assertFalse(Ambient.hasBars(0, 0, 1920, 1080), "no video yet")
    }

    @Test fun glowIsSmoothOpaqueAndDimmed() {
        // Left half pure red, right half pure blue.
        val w = 32; val h = 8
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        val frame = IntArray(w * h) { if (it % w < w / 2) red else blue }
        val g = Ambient.glow(frame, w, h, radius = 3, brightness = 1f)
        assertTrue(g.all { it ushr 24 == 0xFF }, "opaque")
        val row = (0 until w).map { g[it] }
        val reds = row.map { it shr 16 and 0xFF }
        assertTrue(reds.zipWithNext().all { (a, b) -> b <= a }, "red fades smoothly into blue: $reds")
        assertEquals(255, reds.first(), "far edge keeps its colour (edges held, not darkened)")
        assertTrue(reds[w / 2] in 60..195, "the seam is blended: ${reds[w / 2]}")

        val dim = Ambient.glow(IntArray(w * h) { 0xFFC8C8C8.toInt() }, w, h, brightness = 0.5f)
        assertEquals(100, dim[0] and 0xFF, "dimmed to half")
    }
}
