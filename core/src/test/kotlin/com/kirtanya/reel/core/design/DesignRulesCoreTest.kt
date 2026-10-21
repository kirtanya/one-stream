package com.kirtanya.reel.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WcagTest {
    @Test fun blackOnWhiteIs21() = assertEquals(21.0, Wcag.contrastOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)

    @Test fun knownGreyPair() {
        // #767676 on white is the classic 4.54:1 boundary grey.
        assertEquals(4.54, Wcag.contrastOf(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test fun translucentOverOpaque() {
        assertEquals(0xFF808080.toInt(), Wcag.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(0xFF123456.toInt(), Wcag.over(0xFF123456.toInt(), 0xFFFFFFFF.toInt()))
    }
}

class TextRulesTest {
    @Test fun largeTextNeedsLess() {
        assertEquals(4.5, TextRules.requiredContrast(14f))
        assertEquals(4.5, TextRules.requiredContrast(17.5f))
        assertEquals(3.0, TextRules.requiredContrast(18f))
        assertEquals(3.0, TextRules.requiredContrast(32f))
    }

    @Test fun ellipsisOnlyBreaksShortLabelsYouPress() {
        assertTrue(TextRules.ellipsisIsViolation("Subtitles", interactive = true))
        assertTrue(TextRules.ellipsisIsViolation("Science Fiction", interactive = true))
        // A long title on a poster card may end in "…".
        assertFalse(TextRules.ellipsisIsViolation("The Lord of the Rings: The Return of the King", interactive = true))
        // Plain text (not a button / chip / tab / row) may end in "…".
        assertFalse(TextRules.ellipsisIsViolation("Drama", interactive = false))
        assertFalse(TextRules.ellipsisIsViolation("two\nlines", interactive = true))
    }
}

class TextContrastTest {
    /** A w×h region: background [bg], a solid 'glyph' block of [fg] in the middle. */
    private fun scene(fg: Int, bg: (x: Int, y: Int) -> Int, w: Int = 40, h: Int = 20): Pair<IntArray, FloatArray> {
        val px = IntArray(w * h)
        val cov = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val glyph = x in 10..29 && y in 6..13
            px[y * w + x] = if (glyph) fg else bg(x, y)
            cov[y * w + x] = if (glyph) 1f else 0f
        }
        return px to cov
    }

    @Test fun whiteOnBlack() {
        val (px, cov) = scene(0xFFFFFFFF.toInt(), { _, _ -> 0xFF000000.toInt() })
        val r = assertNotNull(TextContrast.measure(px, cov, 40, 20, haloInner = 1, haloOuter = 5))
        assertEquals(21.0, r.ratio, 0.01)
        assertEquals(0xFFFFFFFF.toInt(), r.text)
    }

    @Test fun greyOnGrey() {
        val (px, cov) = scene(0xFF888888.toInt(), { _, _ -> 0xFF666666.toInt() })
        val r = assertNotNull(TextContrast.measure(px, cov, 40, 20, 1, 5))
        assertEquals(1.62, r.ratio, 0.01)
    }

    @Test fun worstPartOfAnUnevenBackgroundCounts() {
        // White text; the background is black except a bright band on the right of the ring.
        val (px, cov) = scene(0xFFFFFFFF.toInt(), { x, _ -> if (x >= 32) 0xFFEEEEEE.toInt() else 0xFF000000.toInt() })
        val r = assertNotNull(TextContrast.measure(px, cov, 40, 20, 1, 5))
        assertTrue(r.ratio < 1.2, "bright part should decide, got ${r.ratio}")
    }

    @Test fun antialiasedEdgesAreNotBackground() {
        val (px, cov) = scene(0xFFFFFFFF.toInt(), { _, _ -> 0xFF000000.toInt() })
        // A grey one-pixel fringe around the glyph (partial coverage) must not count as background.
        for (y in 5..14) for (x in 9..30) if (cov[y * 40 + x] == 0f) { cov[y * 40 + x] = 0.4f; px[y * 40 + x] = 0xFF777777.toInt() }
        val r = assertNotNull(TextContrast.measure(px, cov, 40, 20, 1, 5))
        assertEquals(21.0, r.ratio, 0.01)
    }

    @Test fun anIconDrawnWithTheTextIsNotItsBackground() {
        // White digits inside a white arrow (skip buttons) over a dark picture.
        val (px, cov) = scene(0xFFFFFFFF.toInt(), { x, _ -> if (x >= 32) 0xFFFFFFFF.toInt() else 0xFF101010.toInt() })
        val icon = BooleanArray(px.size) { it % 40 >= 32 }
        assertTrue(TextContrast.measure(px, cov, 40, 20, 1, 5)!!.ratio < 1.1)
        assertTrue(TextContrast.measure(px, cov, 40, 20, 1, 5, ignore = icon)!!.ratio > 15)
    }

    @Test fun tooLittleTextIsNotMeasured() {
        val px = IntArray(100) { 0xFF000000.toInt() }
        assertNull(TextContrast.measure(px, FloatArray(100), 10, 10, 1, 4))
    }

    @Test fun visibilitySeesCoveredAndFadedText() {
        val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt()
        assertEquals(1.0, TextContrast.visibility(white, white, black)!!, 0.01)
        assertEquals(0.5, TextContrast.visibility(white, 0xFF808080.toInt(), black)!!, 0.02)
        // Under an opaque bar: the "ink" is just the bar colour.
        assertEquals(0.0, TextContrast.visibility(white, black, black)!!, 0.01)
        // Something red drawn over white-on-black text: not a mix of the two.
        assertNull(TextContrast.visibility(white, 0xFFFF0000.toInt(), black))
    }
}

class TouchAreaTest {
    private val screen = Box(0.0, 0.0, 360.0, 800.0)

    @Test fun lonelySmallTargetGetsComposeExpansion() {
        val t = Box(100.0, 100.0, 132.0, 132.0)
        val e = TouchArea.effective(t, listOf(t), expandTo = 48.0, screen)
        assertEquals(48.0, e.width, 0.01); assertTrue(TouchArea.isBigEnough(e))
        // A View gets no expansion.
        assertFalse(TouchArea.isBigEnough(TouchArea.effective(t, listOf(t), expandTo = 0.0, screen)))
    }

    @Test fun neighboursSplitTheGap() {
        // Two 40 dp buttons, 4 dp apart: each keeps 40 + 2 (half the gap) + 4 on the free side = 46.
        val a = Box(100.0, 100.0, 140.0, 140.0)
        val b = Box(144.0, 100.0, 184.0, 140.0)
        val e = TouchArea.effective(a, listOf(a, b), 48.0, screen)
        assertEquals(46.0, e.width, 0.01)
        assertEquals(48.0, e.height, 0.01)
        assertFalse(TouchArea.isBigEnough(e))
    }

    @Test fun bigNeighbourBlocksAtItsEdge() {
        val a = Box(100.0, 100.0, 140.0, 140.0)
        val wide = Box(142.0, 90.0, 300.0, 150.0)
        // Grown 4 dp to the left, stopped 2 dp to the right at the wide target's edge.
        assertEquals(46.0, TouchArea.effective(a, listOf(a, wide), 48.0, screen).width, 0.01)
    }

    @Test fun nestedTargetsDontShrinkEachOther() {
        val card = Box(0.0, 0.0, 200.0, 120.0)
        val icon = Box(150.0, 10.0, 190.0, 50.0)
        assertTrue(TouchArea.isBigEnough(TouchArea.effective(icon, listOf(card, icon), 48.0, screen)))
        assertTrue(TouchArea.isBigEnough(TouchArea.effective(card, listOf(card, icon), 48.0, screen)))
    }

    @Test fun screenEdgeClips() {
        val t = Box(0.0, 0.0, 30.0, 30.0)
        assertEquals(39.0, TouchArea.effective(t, listOf(t), 48.0, screen).width, 0.01)
    }

    @Test fun focusDiff() {
        val a = IntArray(100) { 0xFF101010.toInt() }
        val b = a.copyOf().also { for (i in 0 until 3) it[i] = 0xFFFFFFFF.toInt() }
        assertEquals(0.03, FocusDiff.changed(a, b), 1e-9)
        assertTrue(FocusDiff.isVisible(0.03))
        // A ring drawn just outside a 50-pixel element: 3 changed pixels near it = 6 % of the element.
        assertEquals(0.06, FocusDiff.share(a, b, elementPixels = 50), 1e-9)
        // A faint shift (10 levels) is not a visible change.
        val c = IntArray(100) { 0xFF1A1A1A.toInt() }
        assertEquals(0.0, FocusDiff.changed(a, c))
    }
}

class DesignBaselineTest {
    @Test fun keyIgnoresTheMeasuredValue() {
        assertEquals("phone/home | text \"Play\"", DesignBaseline.key("phone/home | text \"Play\" | 3.9:1"))
        assertEquals("a | b/", DesignBaseline.key(DesignBaseline.line("a", "b|", "x")))
    }

    @Test fun onlyNewViolationsAreNew() {
        val base = listOf("s | chip | 40dp", "s | chip | 40dp", "s | tab | 30dp", "s | gone | 1")
        val found = listOf("s | chip | 41dp", "s | chip | 40dp", "s | chip | 40dp", "s | tab | 31dp", "s | brand-new | 2")
        val d = DesignBaseline.diff(found, base)
        assertEquals(listOf("s | chip | 40dp", "s | brand-new | 2"), d.new)
        assertEquals(3, d.known.size)
        assertEquals(listOf("s | gone | 1"), d.stale)
    }

    @Test fun parseSkipsComments() {
        assertEquals(listOf("a | b | c"), DesignBaseline.parse("# header\n\na | b | c\n"))
    }
}

class ColorScanTest {
    private fun hits(src: String) = ColorScan.scan("x/A.kt", src).map { it.literal }

    @Test fun findsHardCodedColours() {
        assertEquals(listOf("Color(0xFF112233)"), hits("val a = Color(0xFF112233)"))
        assertEquals(listOf("Color.White"), hits("track = Color.White.copy(alpha = 0.2f)"))
        assertEquals(listOf("Color.WHITE"), hits("setTextColor(Color.WHITE)"))
        assertEquals(listOf("0x99000000"), hits("setColor(0x99000000.toInt())"))
        assertEquals(listOf("Color.parseColor("), hits("Color.parseColor(\"#fff\")"))
        assertEquals(listOf("Color(red = 1f, green = 0f, blue = 0f)"), hits("Color(red = 1f, green = 0f, blue = 0f)"))
    }

    @Test fun ignoresTransparentMasksAndComments() {
        assertEquals(emptyList(), hits("x = Color.Transparent; y = android.graphics.Color.TRANSPARENT; z = Color.Unspecified"))
        assertEquals(emptyList(), hits("internal fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)"))
        assertEquals(emptyList(), hits("// Color.White is not used here\nval a = 1 /* 0xFF000000 */"))
        assertEquals(emptyList(), hits("val q = '\"'; val s = \"// not a comment\"; val c = tokens.color"))
    }

    @Test fun keepsLineNumbers() {
        val h = ColorScan.scan("x/A.kt", "/* a\n b */\nval c = Color.Black")
        assertEquals(3, h.single().line)
        assertEquals("x/A.kt | val c = Color.Black | Color.Black", h.single().entry)
    }

    @Test fun tokenFilesAreExempt() {
        assertTrue(ColorScan.isTokenFile("com/kirtanya/reel/ui/theme/Tokens.kt"))
        assertTrue(ColorScan.isTokenFile("com/kirtanya/reel/player/PlayerColors.kt"))
        assertFalse(ColorScan.isTokenFile("com/kirtanya/reel/player/PlayerKit.kt"))
    }
}

class IconContrastTest {
    /** A 40×40 region of [bg] with a 24×24 icon box at (8, 8); the icon is a ring of [fg] 3 px thick inside it. */
    private fun scene(fg: Int, bg: Int, glyph: (x: Int, y: Int) -> Boolean = { x, y -> x in 10..29 && y in 10..29 && !(x in 13..26 && y in 13..26) }): IntArray =
        IntArray(40 * 40) { i -> val x = i % 40; val y = i / 40; if (glyph(x, y)) fg else bg }

    private fun measure(px: IntArray) = IconContrast.measure(px, 40, 40, 8, 8, 32, 32, haloInner = 1, haloOuter = 5)

    @Test fun lightIconOnDarkBar() {
        val r = assertNotNull(measure(scene(0xFFCAC4D0.toInt(), 0xFF2B2930.toInt())))
        assertTrue(r.ratio > 7, "got ${r.ratio}")
        assertEquals(0xFFCAC4D0.toInt(), r.text)
    }

    @Test fun faintIconFails() {
        val r = assertNotNull(measure(scene(0xFF4A4850.toInt(), 0xFF2B2930.toInt())))
        assertTrue(r.ratio < IconContrast.REQUIRED, "got ${r.ratio}")
    }

    @Test fun invisibleIconIsOneToOne() {
        val r = assertNotNull(measure(scene(0xFF2B2930.toInt(), 0xFF2B2930.toInt())))
        assertEquals(1.0, r.ratio, 0.01)
    }

    @Test fun iconOnAPillIsMeasuredAgainstThePill() {
        // A pill (the selected tab) fills the box and its surroundings; the bar further out is not the icon's background.
        val pill = 0xFF4A4458.toInt(); val bar = 0xFFFFFFFF.toInt(); val icon = 0xFFE8DEF8.toInt()
        val px = IntArray(40 * 40) { i ->
            val x = i % 40; val y = i / 40
            when {
                x in 10..29 && y in 10..29 && !(x in 13..26 && y in 13..26) -> icon
                x in 2..37 && y in 2..37 -> pill
                else -> bar
            }
        }
        val r = assertNotNull(measure(px))
        assertEquals(Wcag.contrastOf(icon, pill), r.ratio, 0.05)
    }
}
