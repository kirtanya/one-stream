package com.kirtanya.reel.core.playback

/**
 * Cinema mode: the black bars around a video glow with the video's own colours (like YouTube's
 * ambient mode). The player copies a tiny frame a few times a second; this turns it into the glow:
 * blurred so no shapes show, and dimmed so it never competes with the picture.
 */
object Ambient {
    /** Bars smaller than this share of the screen aren't worth a glow (a near-fit video). */
    const val MIN_BAR_SHARE = 0.03f

    /** How much of the colour the glow keeps (the rest is black): soft, behind the picture. */
    const val BRIGHTNESS = 0.55f

    /**
     * Whether a video of [videoW]×[videoH] fitted (not cropped) into a [viewW]×[viewH] screen leaves
     * bars worth filling.
     */
    fun hasBars(videoW: Int, videoH: Int, viewW: Int, viewH: Int): Boolean {
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return false
        val scale = minOf(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
        val shown = videoW * scale * videoH * scale
        return 1f - shown / (viewW.toFloat() * viewH) >= MIN_BAR_SHARE
    }

    /**
     * The glow from a [w]×[h] ARGB frame: three box-blur passes ([radius] px; together close to a
     * Gaussian) with the edges held, then dimmed to [brightness]. Opaque out.
     */
    fun glow(argb: IntArray, w: Int, h: Int, radius: Int = 4, brightness: Float = BRIGHTNESS): IntArray {
        require(argb.size == w * h) { "frame is ${argb.size} pixels, not $w×$h" }
        var px = argb
        repeat(3) { px = boxBlur(px, w, h, radius) }
        val k = brightness.coerceIn(0f, 1f)
        return IntArray(px.size) { i ->
            val c = px[i]
            val r = ((c shr 16 and 0xFF) * k).toInt()
            val g = ((c shr 8 and 0xFF) * k).toInt()
            val b = ((c and 0xFF) * k).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** One horizontal + one vertical box blur, edge pixels repeated past the border. */
    internal fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return src.copyOf()
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val n = 2 * radius + 1
        for (y in 0 until h) pass(src, tmp, n, radius, w) { i -> y * w + i.coerceIn(0, w - 1) }
        for (x in 0 until w) pass(tmp, out, n, radius, h) { i -> i.coerceIn(0, h - 1) * w + x }
        return out
    }

    /** A running-sum box blur along one line of [len] pixels; [at] maps a position (may be past the ends) to an index. */
    private inline fun pass(src: IntArray, dst: IntArray, n: Int, radius: Int, len: Int, at: (Int) -> Int) {
        var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) {
            val c = src[at(i)]
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
        }
        for (i in 0 until len) {
            dst[at(i)] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val out = src[at(i - radius)]
            val inn = src[at(i + radius + 1)]
            r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
            g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
