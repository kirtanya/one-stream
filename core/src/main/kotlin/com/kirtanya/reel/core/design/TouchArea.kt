package com.kirtanya.reel.core.design

/** An axis-aligned box in dp (or any one unit), left/top inclusive. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(o: Box) = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
    fun intersects(o: Box) = o.left < right && o.right > left && o.top < bottom && o.bottom > top
    fun inside(o: Box) = Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    /** Grown (around its centre) to at least [min] in each direction. */
    fun atLeast(min: Double): Box {
        val dx = ((min - width) / 2).coerceAtLeast(0.0)
        val dy = ((min - height) / 2).coerceAtLeast(0.0)
        return Box(left - dx, top - dy, right + dx, bottom + dy)
    }
}

/**
 * Rule 1 (48 dp touch areas on the phone). The size that matters is the area that really takes the
 * touch, not the drawn size:
 * - Compose gives anything smaller than 48 dp a 48 dp touch area when nothing else is hit there
 *   (a touch inside another target's own bounds goes to that target; where two grown areas overlap
 *   the closer target wins). Views get no such help: their bounds are the touch area.
 * - Nested targets (an icon button on a clickable card) and overlapping siblings don't shrink each
 *   other here: the one on top takes its own bounds, the other keeps the rest.
 */
object TouchArea {
    const val MIN_DP = 48.0
    /** Rounding in layout (px → dp) never fails a target. */
    const val TOLERANCE_DP = 0.5

    /** [expandTo] = 48 for Compose (its hit-test minimum), 0 for Views. */
    fun effective(target: Box, others: List<Box>, expandTo: Double, screen: Box): Box {
        val grown = target.atLeast(expandTo)
        var l = grown.left; var t = grown.top; var r = grown.right; var b = grown.bottom
        for (o in others) {
            if (o == target || o.contains(target) || target.contains(o)) continue
            val og = o.atLeast(expandTo)
            if (!og.intersects(Box(l, t, r, b))) continue
            val gapX = maxOf(o.left - target.right, target.left - o.right)
            val gapY = maxOf(o.top - target.bottom, target.top - o.bottom)
            if (gapX < 0 && gapY < 0) continue
            val oGrowsX = o.width < expandTo
            val oGrowsY = o.height < expandTo
            if (gapX >= gapY) {
                if (o.left >= target.right) r = minOf(r, maxOf(target.right, if (oGrowsX) (target.right + o.left) / 2 else o.left))
                else l = maxOf(l, minOf(target.left, if (oGrowsX) (o.right + target.left) / 2 else o.right))
            } else {
                if (o.top >= target.bottom) b = minOf(b, maxOf(target.bottom, if (oGrowsY) (target.bottom + o.top) / 2 else o.top))
                else t = maxOf(t, minOf(target.top, if (oGrowsY) (o.bottom + target.top) / 2 else o.bottom))
            }
        }
        return Box(l, t, r, b).inside(screen)
    }

    fun isBigEnough(area: Box) = area.width >= MIN_DP - TOLERANCE_DP && area.height >= MIN_DP - TOLERANCE_DP
}

/** Rule 5 (TV focus always visible): how much of an element changes when it takes focus. */
object FocusDiff {
    /** A pixel counts as changed when any channel moves by more than this (0..255). */
    const val PIXEL_THRESHOLD = 24
    /**
     * Pixels that change within [NEAR_DP] of the element (a ring or glow may sit around it, on a
     * parent) must add up to at least this share of the element's own area.
     */
    const val MIN_CHANGED = 0.02
    const val NEAR_DP = 16f

    /** Changed pixels near the element ([focused] / [unfocused] cover the near area) per pixel of the element itself. */
    fun share(focused: IntArray, unfocused: IntArray, elementPixels: Int, threshold: Int = PIXEL_THRESHOLD): Double =
        if (elementPixels <= 0) 0.0 else changed(focused, unfocused, threshold) * focused.size / elementPixels

    fun changed(focused: IntArray, unfocused: IntArray, threshold: Int = PIXEL_THRESHOLD): Double {
        require(focused.size == unfocused.size)
        if (focused.isEmpty()) return 0.0
        var n = 0
        for (i in focused.indices) {
            val a = focused[i]; val b = unfocused[i]
            if (Math.abs(Wcag.red(a) - Wcag.red(b)) > threshold || Math.abs(Wcag.green(a) - Wcag.green(b)) > threshold ||
                Math.abs(Wcag.blue(a) - Wcag.blue(b)) > threshold
            ) n++
        }
        return n.toDouble() / focused.size
    }

    fun isVisible(share: Double) = share >= MIN_CHANGED
}
