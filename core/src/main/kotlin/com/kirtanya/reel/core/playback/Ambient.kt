package com.kirtanya.reel.core.playback

/**
 * Cinema mode: the black bars around a video glow with the video's own colours (like YouTube's
 * ambient mode). The player copies a tiny frame a few times a second; this turns it into the glow:
 * blurred so no shapes show, and dimmed so it never competes with the picture.
 */
object Ambient {
    /** Bars smaller than this share of the screen aren't worth a glow (a near-fit video). */
    const val MIN_BAR_SHARE = 0.03f

    /** How much of the colour the glow keeps before the falloff (the falloff does the dimming). */
    const val BRIGHTNESS = 1f

    /** Cinema mode strength: how bright the glow is right at the video's edge (it fades to 0 at the screen edge). */
    enum class Level(val label: String, val edge: Float) {
        OFF("Off", 0f), SUBTLE("Subtle", 0.28f), MEDIUM("Medium", 0.45f), STRONG("Strong", 0.65f);

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SUBTLE
        }
    }

    /** A black shade over the glow along one bar: [colors] (ARGB) at [positions] 0 (video edge) … 1 (screen edge). */
    class Shade(val colors: IntArray, val positions: FloatArray)

    /**
     * The fade across a bar, as black laid over the glow: at the video's edge the glow shows at
     * [Level.edge] brightness, then dies away on an ease-out curve (light thins quickly near its
     * source, then lingers faintly) to nothing at the screen's edge.
     */
    fun falloff(level: Level, steps: Int = 8): Shade {
        val positions = FloatArray(steps + 1) { it / steps.toFloat() }
        val colors = IntArray(steps + 1) { i ->
            val t = positions[i]
            val glow = level.edge * (1f - t) * (1f - t) // brightness left at t
            val black = (1f - glow).coerceIn(0f, 1f)
            ((black * 255).toInt() shl 24) // black with that alpha
        }
        return Shade(colors, positions)
    }

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
