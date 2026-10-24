package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.CaptionSpace
import com.kirtanya.reel.core.player.CropBars
import com.kirtanya.reel.core.player.CropBars.Outcome
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropBarsTest {
    // The size the player samples at (PixelCopy into 128 × 200).
    private val w = 128
    private val h = 200

    /**
     * A synthetic frame: [top] / [bottom] black rows (video black + a little encoder noise), a
     * textured picture in between (a gradient with grain), optional subtitle text in the bottom bar.
     */
    private fun frame(
        top: Int, bottom: Int, seed: Int = 1, subtitleRows: IntRange? = null,
        picture: (x: Int, y: Int, r: Random) -> Int = { x, y, r -> 60 + (x + y) % 120 + r.nextInt(-8, 9) },
    ): CropBars.Frame {
        val r = Random(seed)
        val luma = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inBar = y < top || y >= h - bottom
            when {
                inBar && subtitleRows != null && y in subtitleRows && x in 40 until 88 && (x / 3) % 2 == 0 -> 235 // letters
                inBar -> r.nextInt(0, 4) // flat black
                else -> picture(x, y, r).coerceIn(0, 255)
            }
        }
        return CropBars.Frame(luma, w, h)
    }

    @Test fun letterboxedFilmIsFoundAndRoundedUpToTheGrid() {
        // 25 rows of 200 = 12.5 %, 26 = 13 %.
        val d = CropBars.detect(listOf(frame(26, 26), frame(26, 26, seed = 2), frame(26, 26, seed = 3)))
        assertEquals(Outcome.FOUND, d.outcome)
        assertEquals(13f, d.percent)
        // 2.4:1 inside 16:9 = 12.96 % → 13 %.
        val odd = CropBars.detect(listOf(frame(25, 26)))
        assertEquals(12.5f, odd.percent, "the smaller side, on the grid")
    }

    @Test fun pictureToTheEdgesHasNoBars() {
        val d = CropBars.detect(listOf(frame(0, 0), frame(0, 0, seed = 5)))
        assertEquals(Outcome.NONE, d.outcome)
        assertEquals(0f, d.percent)
    }

    @Test fun unevenBarsTakeTheSmallerSide() {
        val d = CropBars.detect(listOf(frame(20, 30)))
        assertEquals(Outcome.FOUND, d.outcome)
        assertEquals(10f, d.percent)
        // A bar on one side only (a dark sky at the top, ground at the bottom): nothing to crop evenly.
        assertEquals(Outcome.NONE, CropBars.detect(listOf(frame(30, 0))).outcome)
    }

    @Test fun burntInSubtitlesInTheBottomBarAreStillBar() {
        val withText = frame(26, 26, subtitleRows = (h - 20)..(h - 10))
        assertEquals(13f, CropBars.detect(listOf(withText)).percent)
        // Text right against the picture's edge too.
        val atEdge = frame(26, 26, subtitleRows = (h - 26)..(h - 18))
        assertEquals(13f, CropBars.detect(listOf(atEdge)).percent)
    }

    @Test fun darkGrainySceneIsNotABar() {
        // A night scene, edge to edge: dark but grainy (film grain spreads more than flat bar black).
        val night = frame(0, 0) { _, _, r -> r.nextInt(0, 30) }
        val d = CropBars.detect(listOf(night))
        assertTrue(d.outcome != Outcome.FOUND, "no crop from a dark scene: $d")
    }

    @Test fun darkFlatSceneIsUnsureNotFound() {
        // A heavily compressed night scene (flat, almost black) with a dim figure in the middle.
        val night = frame(0, 0) { x, y, _ -> if (x in 50..78 && y in 80..120) 40 else 6 }
        val d = CropBars.detect(listOf(night))
        assertEquals(Outcome.UNSURE, d.outcome)
        // A fade to black: nothing to judge either.
        assertEquals(Outcome.UNSURE, CropBars.detect(listOf(frame(0, 0) { _, _, _ -> 2 })).outcome)
    }

    @Test fun aDarkSceneInsideALetterboxNeverMakesTheBarsBigger() {
        // One frame's film is dark at its top and bottom edges (looks like thicker bars); the others are bright.
        val darkEdges = frame(26, 26) { x, y, r -> if (y < 60 || y > 140) r.nextInt(0, 3) else 90 + (x % 40) + r.nextInt(-5, 6) }
        val d = CropBars.detect(listOf(darkEdges, frame(26, 26, seed = 7), frame(26, 26, seed = 8)))
        assertEquals(13f, d.percent, "the smallest across frames is the real bar")
        // Only dark frames: skipped as unsure, the bright one decides.
        val night = frame(26, 26) { _, _, _ -> 5 }
        assertEquals(13f, CropBars.detect(listOf(night, frame(26, 26))).percent)
    }

    @Test fun barsAreCappedAndThinOnesIgnored() {
        assertEquals(20f, CropBars.detect(listOf(frame(70, 70))).percent)
        assertEquals(Outcome.NONE, CropBars.detect(listOf(frame(1, 1))).outcome, "a row of encoder edge is not a bar")
    }

    @Test fun argbFramesBecomeLuma() {
        assertEquals(0, CropBars.luma(0xFF000000.toInt()))
        assertEquals(255, CropBars.luma(0xFFFFFFFF.toInt()))
        val f = CropBars.Frame.ofArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt()), 2, 2)
        assertEquals(listOf(0, 255, 128, 0), f.luma.toList())
        assertNull(CropBars.barsIn(f), "too small to judge")
    }

    @Test fun stepsLabelsAndShape() {
        assertEquals(0f, CropBars.clamp(-3f))
        assertEquals(20f, CropBars.clamp(40f))
        assertEquals(4.5f, CropBars.clamp(4.4f))
        assertEquals(5f, CropBars.step(4f, 1, CropBars.TV_STEP))
        assertEquals(3.5f, CropBars.step(4f, -1, CropBars.PHONE_STEP))
        assertEquals(0f, CropBars.step(0f, -1, CropBars.TV_STEP))
        assertEquals("Off", CropBars.label(0f))
        assertEquals("4 %", CropBars.label(4f))
        assertEquals("4.5 %", CropBars.label(4.5f))
        assertNull(CropBars.saved(0f), "no crop is forgotten, not saved as 0")
        assertEquals(12.5f, CropBars.saved(12.5f))
        assertEquals(0.75f, CropBars.visible(12.5f), 1e-6f)
        assertEquals(16f / 9f / 0.75f, CropBars.aspect(16f / 9f, 12.5f), 1e-5f)
        assertEquals(1f, CropBars.scaleY(0f))
    }

    @Test fun captionsStayInsideTheVisiblePicture() {
        // Frame = screen, no band: the user's own position.
        assertEquals(0.08f, CaptionSpace.bottomFraction(0.08f, 0, 1000, 0, 1000, 0, 0), 1e-6f)
        // Band over the picture: just above it (as before).
        assertEquals(0.208f, CaptionSpace.bottomFraction(0.08f, 0, 1000, 0, 1000, 200, 8), 1e-6f)
        // A filled picture whose frame runs 100 px past the screen's bottom: captions move up by that.
        assertEquals((100 + 0.08f * 1000) / 1200, CaptionSpace.bottomFraction(0.08f, -100, 1100, 0, 1000, 0, 0), 1e-6f)
        // A small picture in the middle (portrait) with the band below it: the band doesn't push captions.
        assertEquals(0.08f, CaptionSpace.bottomFraction(0.08f, 300, 500, 0, 800, 150, 8), 1e-6f)
    }
}
