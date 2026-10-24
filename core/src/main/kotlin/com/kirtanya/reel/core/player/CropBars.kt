package com.kirtanya.reel.core.player

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Crop bars: black bars baked into the picture itself (a 2.4:1 film released as 16:9). The player
 * cuts the same share off the top and the bottom ([percent] of the video's height, per side), then
 * the picture mode works on what's left — Fit shows the film at its true shape, Fill / Stretch fill
 * the screen with picture instead of the baked-in black.
 */
object CropBars {
    const val MAX_PERCENT = 20f
    /** The phone slider and −/+ (fine), the TV remote's ←/→ (coarse: fewer presses from the sofa). */
    const val PHONE_STEP = 0.5f
    const val TV_STEP = 1f

    /** Into 0…20 % on the 0.5 % grid (a saved / detected value that is off the grid snaps to it). */
    fun clamp(percent: Float): Float {
        if (percent.isNaN()) return 0f
        return ((percent.coerceIn(0f, MAX_PERCENT) * 2).roundToInt() / 2f)
    }

    /** One step up (+1) or down (−1). */
    fun step(percent: Float, direction: Int, step: Float): Float = clamp(percent + direction * step)

    /** What TitlePrefs keeps: null for no crop (so a title without one is forgotten). */
    fun saved(percent: Float): Float? = clamp(percent).takeIf { it > 0f }

    /** "Off", "4 %", "4.5 %". */
    fun label(percent: Float): String {
        val p = clamp(percent)
        if (p == 0f) return "Off"
        return (if (p % 1f == 0f) p.toInt().toString() else p.toString()) + " %"
    }

    /** Share of the video's height still shown. */
    fun visible(percent: Float): Float = 1f - 2f * clamp(percent) / 100f

    /** How much the video surface grows vertically so that only the middle [visible] share fills its frame. */
    fun scaleY(percent: Float): Float = 1f / visible(percent)

    /** The shape of what's left: the video's own (or forced) shape, made wider by the crop. */
    fun aspect(videoAspect: Float, percent: Float): Float = videoAspect / visible(percent)

    // ---------------------------------------------------------------- Auto: find the bars

    /** One sampled frame as luma 0–255, row by row. */
    class Frame(val luma: IntArray, val width: Int, val height: Int) {
        init { require(luma.size == width * height) }

        companion object {
            /** From ARGB pixels (a PixelCopy of the video). */
            fun ofArgb(argb: IntArray, width: Int, height: Int) = Frame(IntArray(argb.size) { luma(argb[it]) }, width, height)
        }
    }

    /** BT.601 luma of an ARGB pixel, 0–255. */
    fun luma(argb: Int): Int {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    enum class Outcome {
        /** Bars on both sides: crop [Detection.percent]. */
        FOUND,
        /** The picture reaches the top or the bottom (or the bars are too thin to matter). */
        NONE,
        /** Every frame was too dark to tell bars from the film. */
        UNSURE,
    }

    data class Detection(val outcome: Outcome, val percent: Float, val perFrame: List<Float?>)

    /** A pixel this dark counts as black (video black is 16/255 in the file; noise and scaling add a little). */
    const val DARK = 28
    /** Bars are flat: the black pixels of a bar row average at most this… */
    const val BAR_MEAN = 20
    /** …and spread at most this much (film grain in a dark scene spreads more). */
    const val BAR_SPREAD = 6.0
    /** A plain bar row: this share of its pixels black. */
    const val CLEAN_SHARE = 0.97f
    /** A bar row with burnt-in subtitle text: half black at least, both ends black. */
    const val TEXT_SHARE = 0.5f
    const val TEXT_ENDS = 0.12f
    /** The film between the bars averages less than this: too dark to tell (a night scene). */
    const val DARK_SCENE = 24
    /** Thinner bars than this aren't bars (encoder edges). */
    const val MIN_PERCENT = 1f

    /**
     * Looks for baked-in bars in a few frames ([frames], a moment apart). Per frame: count black rows
     * in from the top and from the bottom — a row is a bar row when its black is flat (no grain) and
     * covers the row, or covers both ends and half of it (burnt-in subtitles sitting in the bar) —
     * and keep the smaller side (both bars must be there; a dark sky alone is not a bar). A frame
     * whose film is too dark to judge is skipped. Across frames the smallest wins: a dark scene can
     * only make bars look bigger, never smaller, so the smallest is the real bar. Rounded up to the
     * 0.5 % grid (a sliver of picture lost is invisible; a sliver of black left is not), at most 20 %.
     */
    fun detect(frames: List<Frame>): Detection {
        val per = frames.map { barsIn(it) }
        val seen = per.filterNotNull()
        if (seen.isEmpty()) return Detection(Outcome.UNSURE, 0f, per)
        val smallest = seen.min()
        if (smallest < MIN_PERCENT) return Detection(Outcome.NONE, 0f, per)
        val percent = (ceil(smallest * 2 - 1e-4f) / 2f).coerceAtMost(MAX_PERCENT)
        return Detection(Outcome.FOUND, percent, per)
    }

    /** The bar share (percent, the smaller of top / bottom) in one frame; null = too dark to tell. */
    fun barsIn(f: Frame): Float? {
        val h = f.height
        if (h < 4 || f.width < 4) return null
        val kinds = Array(h) { rowKind(f, it) }
        fun run(from: Int, dir: Int): Int {
            var n = 0
            var clean = 0
            var y = from
            while (y in 0 until h && n < h / 2 && kinds[y] != Row.PICTURE) {
                if (kinds[y] == Row.CLEAN) clean++
                n++
                y += dir
            }
            // Mostly text-like rows with hardly any plain black: a dark picture, not a bar.
            return if (clean * 2 >= n) n else 0
        }
        val top = run(0, 1)
        val bottom = run(h - 1, -1)
        // The film between the bars (or the whole frame when there are none): too dark to judge?
        val from = top
        val to = h - bottom
        if (to - from < h / 4) return null
        var sum = 0L
        for (y in from until to) for (x in 0 until f.width) sum += f.luma[y * f.width + x]
        val mean = sum / ((to - from).toLong() * f.width)
        if (mean < DARK_SCENE) return null
        return 100f * minOf(top, bottom) / h
    }

    private enum class Row { CLEAN, TEXT, PICTURE }

    private fun rowKind(f: Frame, y: Int): Row {
        val w = f.width
        val base = y * w
        var dark = 0
        var sum = 0L
        var sq = 0L
        for (x in 0 until w) {
            val l = f.luma[base + x]
            if (l <= DARK) { dark++; sum += l; sq += l.toLong() * l }
        }
        if (dark == 0) return Row.PICTURE
        val mean = sum.toDouble() / dark
        val spread = sqrt((sq.toDouble() / dark - mean * mean).coerceAtLeast(0.0))
        if (mean > BAR_MEAN || spread > BAR_SPREAD) return Row.PICTURE
        val share = dark.toFloat() / w
        if (share >= CLEAN_SHARE) return Row.CLEAN
        if (share < TEXT_SHARE) return Row.PICTURE
        val end = (w * TEXT_ENDS).toInt().coerceAtLeast(1)
        for (x in 0 until end) if (f.luma[base + x] > DARK || f.luma[base + w - 1 - x] > DARK) return Row.PICTURE
        return Row.TEXT
    }
}

/**
 * Where captions sit when the video's frame isn't the screen: a cropped / filled picture whose frame
 * runs past the screen's bottom, or a bottom band of controls over it. Media3 places captions at a
 * fraction of the SubtitleView's height from its bottom; this gives the fraction that keeps them
 * [style] (the user's position) above the visible bottom, and clear of [coveredPx] (+ [marginPx]).
 */
object CaptionSpace {
    fun bottomFraction(style: Float, viewTop: Int, viewBottom: Int, screenTop: Int, screenBottom: Int, coveredPx: Int, marginPx: Int): Float {
        val h = viewBottom - viewTop
        if (h <= 0) return style
        val visTop = maxOf(viewTop, screenTop)
        val visBottom = minOf(viewBottom, screenBottom)
        if (visBottom <= visTop) return style
        // The user's position, measured inside the part that shows.
        val styled = (viewBottom - visBottom) + style * (visBottom - visTop)
        // Above the band, when the band reaches over the picture.
        val bandTop = if (coveredPx > 0) screenBottom - coveredPx - marginPx else screenBottom
        val clear = (viewBottom - minOf(visBottom, bandTop)).toFloat()
        return (maxOf(styled, clear) / h).coerceIn(0f, 0.9f)
    }
}
