package com.kirtanya.reel.core.player

/**
 * How the phone player lays out its controls (Settings panel → Layout).
 *  - [CLASSIC]: one band along the bottom (the original layout).
 *  - [EDGE]: built around the Pixel's camera hole. Back at the top and back 10 / play / forward 10
 *    stacked in the middle of the strip beside the hole, every other button in one column on the
 *    opposite edge, the seek bar a hairline along the very bottom edge (like the TV).
 * Edge only makes sense sideways: in portrait the phone always shows Classic.
 */
enum class PhoneLayout(val label: String) {
    CLASSIC("Classic"), EDGE("Edge");

    /** Which short edge a column goes on (landscape). */
    enum class Side { LEFT, RIGHT }

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: CLASSIC

        /** What shows: the user's pick, except Edge falls back to Classic in portrait. */
        fun effective(chosen: PhoneLayout, landscape: Boolean) = if (chosen == EDGE && landscape) EDGE else CLASSIC

        /**
         * The side with the camera hole, from the display-cutout insets (px): the transport column
         * goes there. No cutout (or one on a long edge, both 0) → the left, as the user asked.
         */
        fun cutoutSide(cutoutLeft: Int, cutoutRight: Int) = if (cutoutRight > cutoutLeft) Side.RIGHT else Side.LEFT

        /** The camera-side line: its left x, back's top, and the tops of back 10 / play / forward 10 (px). */
        class HoleColumn(val x: Int, val backTop: Int, val tops: IntArray)

        /**
         * Everything on the camera side in ONE line through the hole (the user's arrangement): back at
         * [top], then the hole (nothing on it), then [count] buttons (back 10, play, forward 10) from
         * [gap] under the hole's bounding rect down to [bottom], [gap] apart. The line is centred on the
         * hole's horizontal middle, kept on screen ([minX]..[maxX]: a 48 dp button is wider than the
         * ≈ 40 dp strip). Short screen: the spacing between the buttons shrinks first (down to
         * touching); still too tall, or back doesn't fit above the hole → null (the caller falls back).
         */
        fun holeColumn(
            holeLeft: Int, holeTop: Int, holeRight: Int, holeBottom: Int, count: Int, size: Int, gap: Int,
            minX: Int, maxX: Int, top: Int, bottom: Int,
        ): HoleColumn? {
            if (holeRight <= holeLeft || holeBottom <= holeTop || count <= 0) return null
            val x = ((holeLeft + holeRight) / 2 - size / 2).coerceAtMost(maxX - size).coerceAtLeast(minX)
            if (top + size > holeTop) return null
            val start = holeBottom + gap
            val spare = bottom - start - count * size
            if (spare < 0) return null
            val g = if (count == 1) 0 else minOf(gap, spare / (count - 1))
            return HoleColumn(x, top, IntArray(count) { start + it * (size + g) })
        }

        /** Where the two skip buttons go around the camera hole: left x and the tops (px) above / below it. */
        class Flank(val x: Int, val aboveTop: Int, val belowTop: Int)

        /**
         * Back 10 directly above the camera hole and forward 10 directly below it, both centred on the
         * hole's horizontal middle (moved in just enough to stay on screen, [minX]..[maxX]), [gap] clear
         * of its bounding rect. Neither may overlap the play button beside the hole ([playTop]..[playBottom]),
         * so a button that would is pushed further out. Null when they don't fit inside [top]..[bottom]
         * (the caller keeps the plain stack). The fallback when [holeColumn] doesn't fit.
         */
        fun flankHole(
            holeLeft: Int, holeTop: Int, holeRight: Int, holeBottom: Int, size: Int, gap: Int,
            minX: Int, maxX: Int, playTop: Int, playBottom: Int, top: Int, bottom: Int,
        ): Flank? {
            if (holeRight <= holeLeft || holeBottom <= holeTop) return null
            val x = ((holeLeft + holeRight) / 2 - size / 2).coerceAtMost(maxX - size).coerceAtLeast(minX)
            val aboveTop = minOf(holeTop - gap, playTop) - size
            val belowTop = maxOf(holeBottom + gap, playBottom)
            if (aboveTop < top || belowTop + size > bottom) return null
            return Flank(x, aboveTop, belowTop)
        }

        /**
         * Tops (px) of [count] buttons [size] tall stacked in one column, centred on [centre] but
         * kept inside [top]..[bottom]. They sit [gap] apart when that fits, touching when not; a
         * stack taller than the room still starts at [top] (touch areas never shrink).
         */
        fun stack(count: Int, size: Int, gap: Int, top: Int, bottom: Int, centre: Int): IntArray {
            if (count <= 0) return IntArray(0)
            val room = bottom - top
            val g = if (count * size + (count - 1) * gap <= room) gap else 0
            val total = count * size + (count - 1) * g
            val start = (centre - total / 2).coerceAtMost(bottom - total).coerceAtLeast(top)
            return IntArray(count) { start + it * (size + g) }
        }
    }
}
