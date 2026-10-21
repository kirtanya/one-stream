package com.kirtanya.reel.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.core.design.Box
import com.kirtanya.reel.core.design.DesignBaseline
import com.kirtanya.reel.core.design.FocusDiff
import com.kirtanya.reel.core.design.TextContrast
import com.kirtanya.reel.core.design.TextRules
import com.kirtanya.reel.core.design.TouchArea
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The design rules on the player's View-based controls (PhoneControls, TvControls, the settings
 * panel): the same rules as [ComposeChecks], read from the view tree and its drawing. Views get no
 * automatic touch-area growth, so their bounds are their touch areas.
 */
internal class ViewChecks(private val root: View, private val where: String) {
    private val density = root.resources.displayMetrics.density
    private val scaledDensity = density * root.resources.configuration.fontScale

    class Shot(val pixels: IntArray, val width: Int, val height: Int)

    fun shot(): Shot {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return Shot(px, root.width, root.height)
    }

    private val shown: List<View>
        get() = buildList {
            fun walk(v: View) {
                if (!v.isShown || v.alpha <= 0f || v.width <= 0 || v.height <= 0) return
                add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }

    private fun rectOf(v: View): Rect {
        val a = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        return Rect(a[0] - r[0], a[1] - r[1], a[0] - r[0] + v.width, a[1] - r[1] + v.height)
    }

    private fun box(r: Rect) = Box(r.left / density.toDouble(), r.top / density.toDouble(), r.right / density.toDouble(), r.bottom / density.toDouble())

    /**
     * Not cut by a scrolling list's edge (the settings panel scrolls in landscape). A row partly
     * scrolled out can't be read from what shows; it's checked where it shows fully, as ComposeChecks does.
     */
    private fun fullyInScroller(v: View): Boolean {
        val r = rectOf(v)
        return generateSequence(v.parent) { it.parent }.filterIsInstance<ScrollView>().all { sv -> rectOf(sv).let { it.contains(r) } }
    }

    private fun View.isTarget() = isEnabled && (isClickable || isLongClickable || (isFocusable && this !is ViewGroup)) && this !is PlayerView

    // ─── Rule 1 ─────────────────────────────────────────────────────────────────────────────

    fun touch(): ComposeChecks.Result {
        val all = shown.filter { it.isTarget() }
        val targets = all.filter(::fullyInScroller)
        val skipped = (all - targets.toSet()).map { "${label(it)}: partly scrolled out of view" }
        val others = all.map { box(rectOf(it)) }
        val screen = Box(0.0, 0.0, root.width / density.toDouble(), root.height / density.toDouble())
        val found = targets.mapNotNull { v ->
            val own = box(rectOf(v))
            val area = TouchArea.effective(own, others, expandTo = 0.0, screen)
            if (TouchArea.isBigEnough(area)) null
            else DesignBaseline.line(where, label(v), "touch area ${fmt(area.width)} × ${fmt(area.height)} dp")
        }
        return ComposeChecks.Result(targets.size, found, skipped)
    }

    // ─── Rule 2 ─────────────────────────────────────────────────────────────────────────────

    fun clipping(): ComposeChecks.Result {
        val found = ArrayList<String>()
        var checked = 0
        for (v in shown) {
            val r = rectOf(v)
            val over = maxOf(r.right - root.width, -r.left)
            if (over > density && generateSequence(v.parent) { it.parent }.none { it is android.widget.HorizontalScrollView }) {
                found += DesignBaseline.line(where, label(v), "runs ${fmt(over / density.toDouble())} dp past the screen edge")
            }
            if (v !is TextView || v.text.isNullOrBlank()) continue
            val layout = v.layout ?: continue
            checked++
            val full = v.text.toString()
            val element = "text \"${full.short()}\""
            val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
            val roomH = v.height - v.totalPaddingTop - v.totalPaddingBottom
            val roomW = v.width - v.totalPaddingLeft - v.totalPaddingRight
            val needW = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
            when {
                ellipsized -> if (TextRules.ellipsisIsViolation(full, v.interactive())) found += DesignBaseline.line(where, element, "short label cut off with …")
                layout.height > roomH + 1 -> found += DesignBaseline.line(where, element, "text cut off (needs ${fmt(layout.height / density.toDouble())} dp tall, has ${fmt(roomH / density.toDouble())} dp)")
                needW > roomW + 1 -> found += DesignBaseline.line(where, element, "text cut off (needs ${fmt(needW / density.toDouble())} dp wide, has ${fmt(roomW / density.toDouble())} dp)")
            }
        }
        return ComposeChecks.Result(checked, found)
    }

    // ─── Rule 3 ─────────────────────────────────────────────────────────────────────────────

    fun contrast(s: Shot = shot()): ComposeChecks.Result {
        val found = ArrayList<String>()
        val skipped = ArrayList<String>()
        var checked = 0
        val inner = ceil(density).toInt()
        val outer = (TextContrast.RING_DP * density).roundToInt()
        for (v in shown) {
            if (v !is TextView || v.text.isNullOrBlank() || v.layout == null) continue
            val element = "text \"${v.text.toString().short()}\""
            if (!fullyInScroller(v)) { skipped += "$element: partly scrolled out of view"; continue }
            val m = measure(s, v, inner, outer) ?: run { skipped += "$element: too little of it on screen"; null } ?: continue
            // Under the settings panel (or anything else drawn over it): not readable, not measurable.
            val alpha = generateSequence(v as View) { it.parent as? View }.fold(1f) { a, x -> a * x.alpha }
            val declared = (v.currentTextColor and 0xFFFFFF) or (((v.currentTextColor ushr 24) * alpha).roundToInt() shl 24)
            val shows = TextContrast.visibility(declared, m.text, m.background)
            if (shows == null || shows < 0.35) { skipped += "$element: covered by something drawn over it (shows ${hex(m.text)} on ${hex(m.background)})"; continue }
            checked++
            val sp = v.textSize / scaledDensity
            val need = TextRules.requiredContrast(sp)
            if (m.ratio < need) found += DesignBaseline.line(where, element, "%.2f:1, needs %.1f:1 (%s on %s, %.0f sp)".format(m.ratio, need, hex(m.text), hex(m.worstBackground), sp))
        }
        return ComposeChecks.Result(checked, found, skipped)
    }

    private fun measure(s: Shot, v: TextView, inner: Int, outer: Int): TextContrast.Result? {
        val r = rectOf(v)
        // The glyphs alone: the view's own layout, drawn black without its shadow.
        val glyphs = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val paint = v.paint
        val color = paint.color
        paint.clearShadowLayer()
        paint.color = Color.BLACK
        Canvas(glyphs).apply { translate(v.totalPaddingLeft.toFloat(), v.totalPaddingTop.toFloat()); v.layout.draw(this) }
        paint.color = color
        if (v.shadowRadius > 0f) paint.setShadowLayer(v.shadowRadius, v.shadowDx, v.shadowDy, v.shadowColor)
        val g = IntArray(v.width * v.height).also { glyphs.getPixels(it, 0, v.width, 0, 0, v.width, v.height) }
        glyphs.recycle()
        val x0 = (r.left - outer).coerceAtLeast(0); val y0 = (r.top - outer).coerceAtLeast(0)
        val x1 = (r.right + outer).coerceAtMost(s.width); val y1 = (r.bottom + outer).coerceAtMost(s.height)
        val rw = x1 - x0; val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null
        val px = IntArray(rw * rh)
        val cov = FloatArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            px[y * rw + x] = s.pixels[(y0 + y) * s.width + x0 + x]
            val gx = x0 + x - r.left; val gy = y0 + y - r.top
            if (gx in 0 until v.width && gy in 0 until v.height) cov[y * rw + x] = (g[gy * v.width + gx] ushr 24) / 255f
        }
        // An icon drawn with the text (the arrow around the skip buttons' "10") is part of the same
        // mark, not its background: the background is what shows between them.
        val ignore = BooleanArray(rw * rh)
        val parent = v.parent as? ViewGroup
        if (parent != null) for (i in 0 until parent.childCount) {
            val icon = parent.getChildAt(i) as? android.widget.ImageView ?: continue
            if (!icon.isShown || icon.width == 0) continue
            val ir = rectOf(icon)
            if (!Rect.intersects(ir, Rect(x0, y0, x1, y1))) continue
            val bmp = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
            icon.draw(Canvas(bmp))
            val ip = IntArray(icon.width * icon.height).also { bmp.getPixels(it, 0, icon.width, 0, 0, icon.width, icon.height) }
            bmp.recycle()
            for (y in 0 until rh) for (x in 0 until rw) {
                val ix = x0 + x - ir.left; val iy = y0 + y - ir.top
                if (ix in 0 until icon.width && iy in 0 until icon.height && (ip[iy * icon.width + ix] ushr 24) > 5) ignore[y * rw + x] = true
            }
        }
        return TextContrast.measure(px, cov, rw, rh, inner, outer, ignore = ignore)
    }

    // ─── Rule 5 ─────────────────────────────────────────────────────────────────────────────

    /** Focus each focusable control, then another one far from it: its area must change visibly. */
    fun tvFocus(afterMove: () -> Unit = {}): ComposeChecks.Result {
        val targets = shown.filter { it.isFocusable && it !is PlayerView && it !is ScrollView && !(it is ViewGroup && it.hasFocusableChild()) }
        val found = ArrayList<String>()
        val skipped = ArrayList<String>()
        var checked = 0
        for (v in targets) {
            val r = rectOf(v)
            // The control plus what lies near it: its ring and lift (RoundButton grows 12 %: 6 % per side).
            val mx = (FocusDiff.NEAR_DP * density + 0.06f * r.width()).roundToInt(); val my = (FocusDiff.NEAR_DP * density + 0.06f * r.height()).roundToInt()
            val area = Rect(r.left - mx, r.top - my, r.right + mx, r.bottom + my).apply { intersect(0, 0, root.width, root.height) }
            if (!focus(v, afterMove)) { skipped += "${label(v)}: doesn't take focus"; continue }
            val on = crop(shot(), area)
            val other = targets.firstOrNull { it !== v && !Rect.intersects(rectOf(it), area) }
            if (other == null || !focus(other, afterMove) || v.isFocused) { skipped += "${label(v)}: no other control to move focus to"; continue }
            val off = crop(shot(), area)
            checked++
            val share = FocusDiff.share(on, off, r.width() * r.height())
            if (!FocusDiff.isVisible(share)) found += DesignBaseline.line(where, label(v), "focus changes %.1f%% of its area".format(share * 100))
        }
        return ComposeChecks.Result(checked, found, skipped)
    }

    private fun focus(v: View, afterMove: () -> Unit): Boolean {
        // As a remote key press does: leave touch mode, then focus.
        val ok = v.requestFocusFromTouch()
        // Focus animations (lift, track width) run ≤ 220 ms.
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        afterMove()
        return ok && v.isFocused
    }

    private fun ViewGroup.hasFocusableChild(): Boolean = (0 until childCount).any { getChildAt(it).let { c -> c.isFocusable || (c is ViewGroup && c.hasFocusableChild()) } }

    private fun crop(s: Shot, r: Rect): IntArray {
        val out = IntArray(r.width() * r.height())
        var i = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) out[i++] = s.pixels[y * s.width + x]
        return out
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────────────────

    private fun View.interactive() = generateSequence(this as View?) { it.parent as? View }.any { it.isClickable }

    private fun label(v: View): String {
        val words = ArrayList<String>()
        fun walk(x: View) {
            x.contentDescription?.let { words += it.toString() }
            if (x is TextView && !x.text.isNullOrBlank()) words += x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) x.getChildAt(i).takeIf { it.isShown }?.let(::walk)
        }
        walk(v)
        val kind = when {
            v is TextView -> "text"
            v.isClickable -> "Button"
            v.isFocusable -> v.javaClass.simpleName
            else -> v.javaClass.simpleName
        }
        val text = words.joinToString(" ").short()
        return if (text.isEmpty()) kind else "$kind \"$text\""
    }

    private fun String.short() = replace('\n', ' ').trim().let { if (it.length > 40) it.take(39) + "…" else it }
    private fun fmt(v: Double) = if (Math.abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else "%.1f".format(v)
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

}
