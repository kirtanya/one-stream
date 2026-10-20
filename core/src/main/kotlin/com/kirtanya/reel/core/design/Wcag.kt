package com.kirtanya.reel.core.design

import kotlin.math.pow

/** WCAG 2.x colour maths on packed ARGB ints (Android's layout: 0xAARRGGBB). */
object Wcag {
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // Linearised sRGB per channel value; screens are checked pixel by pixel, so look it up.
    private val linear = DoubleArray(256) {
        val s = it / 255.0
        if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance (0 = black, 1 = white) of an opaque colour; alpha is ignored. */
    fun luminance(c: Int) = 0.2126 * linear[red(c)] + 0.7152 * linear[green(c)] + 0.0722 * linear[blue(c)]

    /** Contrast ratio between two luminances, 1..21. */
    fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    fun contrastOf(c1: Int, c2: Int) = contrast(luminance(c1), luminance(c2))

    /** [fg] (maybe translucent) drawn over an opaque [bg]: what the screen shows. */
    fun over(fg: Int, bg: Int): Int {
        val a = alpha(fg) / 255.0
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a) + 0.5).toInt().coerceIn(0, 255)
        return argb(255, mix(red(fg), red(bg)), mix(green(fg), green(bg)), mix(blue(fg), blue(bg)))
    }
}
