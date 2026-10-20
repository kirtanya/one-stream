package com.kirtanya.reel.core.design

import kotlin.math.sqrt

/**
 * Text contrast measured from a rendered screen, not from declared colours: what the user sees,
 * including scrims, translucent text, images and anything else under the text.
 *
 * Inputs are one region of the screen: its pixels, plus the coverage (0..1) of the text's own
 * glyphs drawn alone into a same-sized bitmap. Glyph cores ("ink") give the text colour; pixels in
 * a thin ring around the glyphs (not touching them, so antialiased edges don't count) give the
 * background. Backgrounds can be uneven (poster art, gradients), so the ratio is taken against
 * the worst [worst] share of the ring: text must read over the busiest part, not the average.
 */
object TextContrast {
    /**
     * The background ring ends this far (dp) from the glyphs: what sits right next to the letters,
     * not an icon or a border further away (the "10" inside the skip arrows). It starts 1 dp out,
     * past the antialiased edge.
     */
    const val RING_DP = 2.5f
    class Result(
        /** Contrast of the text colour against the worst part of the background. */
        val ratio: Double,
        val text: Int,
        /** The background pixel the ratio was measured against. */
        val worstBackground: Int,
        /** Typical background (per-channel median of the ring). */
        val background: Int,
        val inkPixels: Int,
        val backgroundPixels: Int,
    )

    fun measure(
        pixels: IntArray,
        coverage: FloatArray,
        width: Int,
        height: Int,
        /** Ring around the glyphs, in pixels (chessboard distance): starts after [haloInner], ends at [haloOuter]. */
        haloInner: Int,
        haloOuter: Int,
        worst: Double = 0.1,
        minInk: Int = 6,
        minBackground: Int = 12,
        /** Pixels that are neither text nor background (an icon drawn with the text). */
        ignore: BooleanArray? = null,
    ): Result? {
        require(pixels.size == width * height && coverage.size == pixels.size)
        var ink = indices(coverage) { it >= 0.85f }
        if (ink.size < minInk) ink = indices(coverage) { it >= 0.5f }
        if (ink.size < minInk) return null
        val text = median(pixels, ink)

        val dist = distanceToGlyphs(coverage, width, height)
        val ring = ArrayList<Int>()
        for (i in pixels.indices) if (coverage[i] <= 0.02f && dist[i] > haloInner && dist[i] <= haloOuter && ignore?.get(i) != true) ring += i
        if (ring.size < minBackground) return null

        val textLum = Wcag.luminance(text)
        val contrasts = DoubleArray(ring.size) { Wcag.contrast(textLum, Wcag.luminance(pixels[ring[it]])) }
        val order = ring.indices.sortedBy { contrasts[it] }
        val worstPixel = pixels[ring[order[(order.size * worst).toInt().coerceIn(0, order.size - 1)]]]
        return Result(
            ratio = Wcag.contrast(textLum, Wcag.luminance(worstPixel)),
            text = text,
            worstBackground = worstPixel,
            background = median(pixels, ring),
            inkPixels = ink.size,
            backgroundPixels = ring.size,
        )
    }

    /**
     * How much of the text's [declared] colour (maybe translucent) shows in the [rendered] ink over
     * [background]: 1 = drawn as asked, about 0 = not there (covered, e.g. scrolled under the
     * navigation bar), in between = faded (alpha, a scrim over it). Null when the rendered ink is no
     * mix of the two at all: something else is drawn over the text, so its contrast can't be read.
     */
    fun visibility(declared: Int, rendered: Int, background: Int): Double? {
        val asked = Wcag.over(declared, background)
        val v = doubleArrayOf(
            (Wcag.red(asked) - Wcag.red(background)).toDouble(),
            (Wcag.green(asked) - Wcag.green(background)).toDouble(),
            (Wcag.blue(asked) - Wcag.blue(background)).toDouble(),
        )
        val w = doubleArrayOf(
            (Wcag.red(rendered) - Wcag.red(background)).toDouble(),
            (Wcag.green(rendered) - Wcag.green(background)).toDouble(),
            (Wcag.blue(rendered) - Wcag.blue(background)).toDouble(),
        )
        val vv = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
        // Asked for (almost) the background colour itself: nothing to separate, measure it as it is.
        if (vv < 8.0 * 8.0) return 1.0
        val t = (w[0] * v[0] + w[1] * v[1] + w[2] * v[2]) / vv
        val r = sqrt((0..2).sumOf { (w[it] - t * v[it]).let { d -> d * d } })
        return if (r > 48.0) null else t.coerceIn(0.0, 1.5)
    }

    private inline fun indices(coverage: FloatArray, keep: (Float) -> Boolean): List<Int> {
        val out = ArrayList<Int>()
        for (i in coverage.indices) if (keep(coverage[i])) out += i
        return out
    }

    private fun median(pixels: IntArray, at: List<Int>): Int {
        fun med(ch: (Int) -> Int): Int {
            val v = IntArray(at.size) { ch(pixels[at[it]]) }
            v.sort()
            return v[v.size / 2]
        }
        return Wcag.argb(255, med(Wcag::red), med(Wcag::green), med(Wcag::blue))
    }

    /** Chessboard distance (pixels) from every pixel to the nearest pixel any glyph touches. */
    internal fun distanceToGlyphs(coverage: FloatArray, width: Int, height: Int): IntArray {
        val far = width + height + 1
        val d = IntArray(coverage.size) { if (coverage[it] > 0.02f) 0 else far }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var m = d[i]
            if (x > 0) m = minOf(m, d[i - 1] + 1)
            if (y > 0) {
                m = minOf(m, d[i - width] + 1)
                if (x > 0) m = minOf(m, d[i - width - 1] + 1)
                if (x < width - 1) m = minOf(m, d[i - width + 1] + 1)
            }
            d[i] = m
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            var m = d[i]
            if (x < width - 1) m = minOf(m, d[i + 1] + 1)
            if (y < height - 1) {
                m = minOf(m, d[i + width] + 1)
                if (x < width - 1) m = minOf(m, d[i + width + 1] + 1)
                if (x > 0) m = minOf(m, d[i + width - 1] + 1)
            }
            d[i] = m
        }
        return d
    }
}
