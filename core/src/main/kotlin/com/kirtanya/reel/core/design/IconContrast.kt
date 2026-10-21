package com.kirtanya.reel.core.design

import kotlin.math.sqrt

/**
 * Contrast of an icon with no words next to it (the phone's icon-only tab bar), measured from the
 * rendered screen like [TextContrast]. A vector icon can't be painted alone the way a text layout
 * can, so its coverage is read from the pixels: inside the icon's box, how far each pixel's colour
 * is from the background just outside the box (0 = background, 1 = the most different pixel).
 * Then the same ink / ring measure as text runs on that coverage.
 *
 * WCAG 1.4.11 (non-text contrast): an icon that is the only label of a control needs [REQUIRED]:1.
 */
object IconContrast {
    const val REQUIRED = 3.0

    /** Colour changes smaller than this share of the strongest one are antialiasing / noise, not icon. */
    private const val NOISE = 0.2f

    /**
     * [pixels] is a region ([width] × [height]) that holds the icon's box ([left], [top], [right],
     * [bottom], exclusive ends, in region pixels) plus at least [haloOuter] pixels around it.
     */
    fun measure(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        haloInner: Int,
        haloOuter: Int,
    ): TextContrast.Result? {
        require(pixels.size == width * height)
        if (right <= left || bottom <= top) return null
        // What surrounds the icon: a frame just outside its box (the bar, or the selected tab's pill).
        val frame = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            val inBox = x in left until right && y in top until bottom
            val near = x in (left - haloOuter) until (right + haloOuter) && y in (top - haloOuter) until (bottom + haloOuter)
            if (near && !inBox) frame += y * width + x
        }
        if (frame.isEmpty()) return null
        val bg = median(pixels, frame)

        val dist = FloatArray(pixels.size)
        var strongest = 0f
        for (y in top until bottom) for (x in left until right) {
            val i = y * width + x
            dist[i] = distance(pixels[i], bg)
            if (dist[i] > strongest) strongest = dist[i]
        }
        // Nothing in the box differs from what is around it: the icon doesn't show at all.
        if (strongest < 1f) return TextContrast.Result(1.0, bg, bg, bg, 0, frame.size)
        val coverage = FloatArray(pixels.size) { i -> (dist[i] / strongest).let { if (it < NOISE) 0f else it } }
        return TextContrast.measure(pixels, coverage, width, height, haloInner, haloOuter)
    }

    private fun distance(a: Int, b: Int): Float {
        val dr = (Wcag.red(a) - Wcag.red(b)).toFloat()
        val dg = (Wcag.green(a) - Wcag.green(b)).toFloat()
        val db = (Wcag.blue(a) - Wcag.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun median(pixels: IntArray, at: List<Int>): Int {
        fun med(ch: (Int) -> Int): Int {
            val v = IntArray(at.size) { ch(pixels[at[it]]) }
            v.sort()
            return v[v.size / 2]
        }
        return Wcag.argb(255, med(Wcag::red), med(Wcag::green), med(Wcag::blue))
    }
}
