package com.kirtanya.reel.design

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.core.design.Box
import com.kirtanya.reel.core.design.DesignBaseline
import com.kirtanya.reel.core.design.FocusDiff
import com.kirtanya.reel.core.design.IconContrast
import com.kirtanya.reel.core.design.TextContrast
import com.kirtanya.reel.core.design.TextRules
import com.kirtanya.reel.core.design.TouchArea
import com.kirtanya.reel.ui.theme.Tokens
import org.robolectric.Shadows.shadowOf
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The design rules on a settled Compose screen, read from its semantics tree (what is pressable,
 * text layouts, scroll containers) and its rendered pixels. Only the top window counts (a dialog
 * or sheet when one is open). Each check returns baseline-format lines (`screen | element | value`).
 */
internal class ComposeChecks(private val compose: ComposeContentTestRule, private val where: String) {

    /** One capture of the top window: its semantics (unmerged) and pixels. */
    inner class Shot(val root: SemanticsNode, val pixels: IntArray, val width: Int, val height: Int) {
        val density = root.layoutInfo.density.density
        // Only what is laid out on screen: a lazy list keeps some rows composed but unplaced after a scroll
        // (ready for reuse); they report a stale position and must not count as neighbours or content.
        val nodes: List<SemanticsNode> = buildList {
            fun walk(n: SemanticsNode) { if (!n.layoutInfo.isPlaced) return; add(n); n.children.forEach(::walk) }
            walk(root)
        }
        val screenPx = Rect(0f, 0f, width.toFloat(), height.toFloat())
        fun dp(px: Float) = px / density
        fun box(r: Rect) = Box(dp(r.left).toDouble(), dp(r.top).toDouble(), dp(r.right).toDouble(), dp(r.bottom).toDouble())
    }

    fun shot(): Shot {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val nodes = roots.fetchSemanticsNodes()
        val i = nodes.lastIndex
        val image = roots[i].captureToImage()
        val px = IntArray(image.width * image.height)
        image.readPixels(px)
        return Shot(nodes[i], px, image.width, image.height)
    }

    // ─── Rule 1: touch areas ────────────────────────────────────────────────────────────────

    class Result(val checked: Int, val found: List<String>, val skipped: List<String> = emptyList())

    fun touch(s: Shot): Result {
        val targets = s.nodes.filter { it.isTouchTarget() }
        val others = targets.map { s.box(it.boundsInRoot) }
        val found = ArrayList<String>()
        val skipped = ArrayList<String>()
        var checked = 0
        val bar = floatingBar(s)
        for (n in targets) {
            // Partly scrolled out of view: its size can't be read from what shows (it's checked where it shows fully).
            if (!n.fullyShown(s)) continue
            // Scrolling content that happens to sit at (or under) the floating tab bar: the bar takes the touches
            // there only at this scroll position; the same row is checked in renders where it sits clear of it.
            if (bar != null && n.inScroller(horizontal = false) && !n.isIconTab() && s.box(n.unclipped()).atLeast(TouchArea.MIN_DP).intersects(s.box(bar))) {
                skipped += "${n.label()}: next to the floating tab bar at this scroll position"
                continue
            }
            checked++
            val own = s.box(n.unclipped())
            val area = TouchArea.effective(own, others, TouchArea.MIN_DP, s.box(s.screenPx))
            if (!TouchArea.isBigEnough(area)) {
                found += DesignBaseline.line(where, n.label(), "touch area ${fmt(area.width)} × ${fmt(area.height)} dp (drawn ${fmt(own.width)} × ${fmt(own.height)} dp)")
            }
        }
        return Result(checked, found, skipped)
    }

    /** The phone's floating tab bar (its icon-only tabs plus the capsule's padding), when it is on screen. */
    private fun floatingBar(s: Shot): Rect? {
        val tabs = s.nodes.filter { it.isIconTab() }.map { it.unclipped() }
        if (tabs.isEmpty()) return null
        val pad = Tokens.Size.barPadding.value * s.density
        return Rect(tabs.minOf { it.left } - pad, tabs.minOf { it.top } - pad, tabs.maxOf { it.right } + pad, tabs.maxOf { it.bottom } + pad)
    }

    /** A tab whose only label is its icon (the floating tab bar's tabs). */
    private fun SemanticsNode.isIconTab(): Boolean {
        if (config.getOrNull(SemanticsProperties.Role) != Role.Tab) return false
        val inside = buildList { fun walk(n: SemanticsNode) { add(n); n.children.forEach(::walk) }; walk(this@isIconTab) }
        return inside.none { it.config.contains(SemanticsProperties.Text) }
    }

    private fun SemanticsNode.isTouchTarget(): Boolean {
        val c = config
        if (c.contains(SemanticsProperties.Disabled)) return false
        return c.contains(SemanticsActions.OnClick) || c.contains(SemanticsActions.OnLongClick) ||
            c.contains(SemanticsActions.SetProgress) || c.contains(SemanticsActions.SetText)
    }

    // ─── Rule 2: clipping ───────────────────────────────────────────────────────────────────

    fun clipping(s: Shot): Result {
        val found = ArrayList<String>()
        var checked = 0
        val reported = HashSet<Int>()
        for (n in s.nodes) {
            if (n.isRoot) continue
            val r = n.unclipped()
            if (r.width <= 0f || r.height <= 0f) continue
            // Content (words, a picture with a description, something to press) past the side of the screen,
            // unless it sits in something that scrolls sideways (rows, pagers). Plain shapes are decoration:
            // full-bleed art that zooms, skeleton tiles.
            val over = maxOf(r.right - s.width, -r.left)
            if (over > s.density && n.hasContent() && !n.inScroller(horizontal = true) && n.ancestors().none { it.id in reported }) {
                reported += n.id
                found += DesignBaseline.line(where, n.label(), "runs ${fmt(s.dp(over).toDouble())} dp past the screen edge")
            }
            val layout = n.textLayout() ?: continue
            checked++
            val full = layout.layoutInput.text.text
            val element = "text \"${full.short()}\""
            val ellipsis = layout.layoutInput.overflow.let { it == TextOverflow.Ellipsis || it == TextOverflow.StartEllipsis || it == TextOverflow.MiddleEllipsis }
            val ellipsized = ellipsis && (layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            // What the glyphs need against the box they got (multiParagraph.width is the width offered, not used).
            val p = layout.multiParagraph
            val needW = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
            val lostLines = p.didExceedMaxLines
            val tooTall = p.height > layout.size.height + 1
            val tooWide = needW > layout.size.width + 1 || (0 until layout.lineCount).any { layout.getLineLeft(it) < -1 }
            when {
                ellipsized -> if (TextRules.ellipsisIsViolation(full, n.interactive())) found += DesignBaseline.line(where, element, "short label cut off with …")
                layout.layoutInput.overflow == TextOverflow.Clip && (lostLines || tooTall || tooWide) -> found += DesignBaseline.line(
                    where, element,
                    when {
                        tooWide -> "text cut off (needs ${fmt(s.dp(needW).toDouble())} dp wide, has ${fmt(s.dp(layout.size.width.toFloat()).toDouble())} dp)"
                        tooTall -> "text cut off (needs ${fmt(s.dp(p.height).toDouble())} dp tall, has ${fmt(s.dp(layout.size.height.toFloat()).toDouble())} dp)"
                        else -> "text cut off after ${layout.lineCount} line(s)"
                    },
                )
            }
            // The text's box cut by a parent that doesn't scroll (a fixed-height card, a row too narrow).
            val shown = n.boundsInRoot
            val cut = maxOf(shown.left - r.left, shown.top - r.top, r.right - shown.right, r.bottom - shown.bottom)
            val inScreen = r.left >= 0 && r.top >= 0 && r.right <= s.width && r.bottom <= s.height
            if (cut > s.density && inScreen && !n.inScroller(horizontal = true) && !n.inScroller(horizontal = false)) {
                found += DesignBaseline.line(where, element, "cut by its container (${fmt(s.dp(cut).toDouble())} dp hidden)")
            }
        }
        return Result(checked, found)
    }

    // ─── Rule 3: contrast ───────────────────────────────────────────────────────────────────

    fun contrast(s: Shot): Result {
        val found = ArrayList<String>()
        val skipped = ArrayList<String>()
        var checked = 0
        val inner = ceil(s.density).toInt()
        val outer = (TextContrast.RING_DP * s.density).roundToInt()
        val bar = floatingBar(s)
        for (n in s.nodes) {
            val layout = n.textLayout() ?: continue
            val full = layout.layoutInput.text.text
            if (full.isBlank()) continue
            val element = "text \"${full.short()}\""
            if (n.disabled()) { skipped += "$element: disabled (WCAG exempts inactive controls)"; continue }
            if (!n.fullyShown(s)) continue
            // Partly under the floating tab bar: the bar's glass would count as its background (fully under it is
            // caught below as covered). The same row is checked where it sits clear of the bar.
            if (bar != null && n.unclipped().overlaps(bar)) { skipped += "$element: partly under the floating tab bar"; continue }
            val m = measure(s, n, layout, inner, outer)
            if (m == null) { skipped += "$element: too little of it on screen"; continue }
            val declared = layout.layoutInput.style.color
            if (declared.isSpecified) {
                val shows = TextContrast.visibility(declared.toArgb(), m.text, m.background)
                if (shows == null || shows < 0.35) {
                    skipped += "$element: covered by something drawn over it (shows ${hex(m.text)} on ${hex(m.background)}, asked ${hex(declared.toArgb())})"
                    continue
                }
            }
            checked++
            val sp = layout.layoutInput.style.fontSize.let { if (it.isSp) it.value else 14f }
            val need = TextRules.requiredContrast(sp)
            if (m.ratio < need) {
                found += DesignBaseline.line(where, element, "%.2f:1, needs %.1f:1 (%s on %s, %.0f sp)".format(m.ratio, need, hex(m.text), hex(m.worstBackground), sp))
            }
        }
        checked += iconTabs(s, found, skipped)
        return Result(checked, found, skipped)
    }

    /**
     * Tabs whose only label is an icon (the phone's floating tab bar): the icon must stand out from
     * what is right around it by [IconContrast.REQUIRED]:1 (WCAG non-text contrast), selected or not.
     */
    private fun iconTabs(s: Shot, found: MutableList<String>, skipped: MutableList<String>): Int {
        var checked = 0
        val outer = (TextContrast.RING_DP * s.density).roundToInt()
        for (tab in s.nodes) {
            if (!tab.isIconTab()) continue
            val inside = buildList { fun walk(n: SemanticsNode) { add(n); n.children.forEach(::walk) }; walk(tab) }
            val icon = inside.firstOrNull { it.config.getOrNull(SemanticsProperties.Role) == Role.Image && it.config.contains(SemanticsProperties.ContentDescription) } ?: continue
            val element = "tab icon \"${icon.config[SemanticsProperties.ContentDescription].joinToString(" ").short()}\""
            if (tab.disabled()) { skipped += "$element: disabled (WCAG exempts inactive controls)"; continue }
            if (!icon.fullyShown(s)) continue
            val r = icon.unclipped()
            val x0 = (r.left.roundToInt() - outer).coerceAtLeast(0); val y0 = (r.top.roundToInt() - outer).coerceAtLeast(0)
            val x1 = (r.right.roundToInt() + outer).coerceAtMost(s.width); val y1 = (r.bottom.roundToInt() + outer).coerceAtMost(s.height)
            val rw = x1 - x0; val rh = y1 - y0
            if (rw <= 0 || rh <= 0) continue
            val px = IntArray(rw * rh) { s.pixels[(y0 + it / rw) * s.width + x0 + it % rw] }
            val m = IconContrast.measure(
                px, rw, rh, r.left.roundToInt() - x0, r.top.roundToInt() - y0, r.right.roundToInt() - x0, r.bottom.roundToInt() - y0,
                haloInner = ceil(s.density).toInt(), haloOuter = outer,
            )
            if (m == null) { skipped += "$element: too little of it on screen"; continue }
            checked++
            if (m.ratio < IconContrast.REQUIRED) {
                found += DesignBaseline.line(where, element, "%.2f:1, needs %.1f:1 (%s on %s, icon)".format(m.ratio, IconContrast.REQUIRED, hex(m.text), hex(m.worstBackground)))
            }
        }
        return checked
    }

    private fun measure(s: Shot, n: SemanticsNode, layout: TextLayoutResult, inner: Int, outer: Int): TextContrast.Result? {
        val w = layout.size.width; val h = layout.size.height
        if (w <= 0 || h <= 0) return null
        // The text's glyphs alone, as the layout draws them (coverage = alpha).
        val glyphs = ImageBitmap(w, h)
        layout.multiParagraph.paint(Canvas(glyphs), color = Color.Black, shadow = Shadow.None, decoration = TextDecoration.None)
        val g = IntArray(w * h).also { glyphs.readPixels(it) }
        val origin = n.positionInRoot
        val x0 = (origin.x.roundToInt() - outer).coerceAtLeast(0)
        val y0 = (origin.y.roundToInt() - outer).coerceAtLeast(0)
        val x1 = (origin.x.roundToInt() + w + outer).coerceAtMost(s.width)
        val y1 = (origin.y.roundToInt() + h + outer).coerceAtMost(s.height)
        val rw = x1 - x0; val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null
        val px = IntArray(rw * rh)
        val cov = FloatArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            px[y * rw + x] = s.pixels[(y0 + y) * s.width + x0 + x]
            val gx = x0 + x - origin.x.roundToInt(); val gy = y0 + y - origin.y.roundToInt()
            if (gx in 0 until w && gy in 0 until h) cov[y * rw + x] = (g[gy * w + gx] ushr 24) / 255f
        }
        return TextContrast.measure(px, cov, rw, rh, inner, outer)
    }

    // ─── Rule 5: TV focus ───────────────────────────────────────────────────────────────────

    /**
     * Every focusable element (up to [perGroup] per row / list, so a row of 20 identical posters
     * costs 3) is focused, captured, unfocused and captured again; its area (plus a margin for the
     * ring and the lift) must change visibly.
     */
    fun tvFocus(focusManager: FocusManager, perGroup: Int = 3): Result {
        val first = shot()
        val perParent = HashMap<Int, Int>()
        val ids = first.nodes.filter {
            it.config.contains(SemanticsProperties.Focused) && it.config.contains(SemanticsActions.RequestFocus) && !it.disabled() && it.fullyShown(first)
        }.filter { perParent.merge(it.parent?.id ?: -1, 1, Int::plus)!! <= perGroup }.map { it.id to it.label() }
        val found = ArrayList<String>()
        val skipped = ArrayList<String>()
        var checked = 0
        for ((id, label) in ids) {
            val matcher = SemanticsMatcher("node $id") { it.id == id }
            if (compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { skipped += "$label: gone after an earlier focus move"; continue }
            compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
            advance(900)
            val on = shot()
            val node = on.nodes.firstOrNull { it.id == id } ?: run { skipped += "$label: gone after focusing"; null } ?: continue
            if (node.config.getOrNull(SemanticsProperties.Focused) != true) { skipped += "$label: doesn't take focus"; continue }
            // The element plus what lies near it: a ring, a glow or the lift (posters grow 10 %) may be drawn
            // around it, sometimes by a parent (a search field's pill outline around the text field).
            val own = node.unclipped().intersect(on.screenPx)
            val area = node.unclipped().let { r ->
                val mx = FocusDiff.NEAR_DP * on.density + 0.06f * r.width; val my = FocusDiff.NEAR_DP * on.density + 0.06f * r.height
                Rect(r.left - mx, r.top - my, r.right + mx, r.bottom + my).intersect(on.screenPx)
            }
            focusManager.clearFocus(force = true)
            advance(900)
            var off = shot()
            if (off.nodes.firstOrNull { it.id == id }?.config?.getOrNull(SemanticsProperties.Focused) == true) {
                // Something put focus straight back on it: compare with another element focused instead.
                val other = ids.map { it.first }.firstOrNull { o -> o != id && off.nodes.firstOrNull { it.id == o }?.let { !it.unclipped().overlaps(area) } == true }
                if (other == null) { skipped += "$label: focus can't be moved off it"; continue }
                compose.onNode(SemanticsMatcher("node $other") { it.id == other }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
                advance(900)
                off = shot()
            }
            val moved = off.nodes.firstOrNull { it.id == id }?.unclipped()
            if (moved == null || moved.topLeft.minus(node.unclipped().topLeft).getDistance() > 2f) { skipped += "$label: moved when focus left it"; continue }
            checked++
            val share = FocusDiff.share(crop(on, area), crop(off, area), (own.width * own.height).roundToInt())
            if (!FocusDiff.isVisible(share)) { found += DesignBaseline.line(where, label, "focus changes %.1f%% of its area".format(share * 100)); continue }
            // The change sits around it, not on it (a parent's ring): it must still look different from a
            // focusable neighbour having focus (the ✕ in a search field lights the same pill as the field).
            val ownArea = node.unclipped().let { r -> Rect(r.left - 4 * on.density, r.top - 4 * on.density, r.right + 4 * on.density, r.bottom + 4 * on.density).intersect(on.screenPx) }
            val onItself = FocusDiff.share(crop(on, ownArea), crop(off, ownArea), (own.width * own.height).roundToInt())
            if (FocusDiff.isVisible(onItself)) continue
            val near = off.nodes.firstOrNull { n ->
                n.id != id && n.config.contains(SemanticsProperties.Focused) && n.config.contains(SemanticsActions.RequestFocus) && !n.disabled() &&
                    n.unclipped().overlaps(area) && n.fullyShown(off)
            } ?: continue
            val nearLabel = near.label()
            compose.onNode(SemanticsMatcher("node ${near.id}") { it.id == near.id }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
            advance(900)
            val other = shot()
            val apart = FocusDiff.share(crop(on, area), crop(other, area), (own.width * own.height).roundToInt())
            if (!FocusDiff.isVisible(apart)) {
                found += DesignBaseline.line(where, label, "focus looks the same as on $nearLabel (%.1f%% differs)".format(apart * 100))
            }
        }
        return Result(checked, found, skipped)
    }

    private fun crop(s: Shot, r: Rect): IntArray {
        val x0 = r.left.roundToInt().coerceIn(0, s.width); val x1 = r.right.roundToInt().coerceIn(0, s.width)
        val y0 = r.top.roundToInt().coerceIn(0, s.height); val y1 = r.bottom.roundToInt().coerceIn(0, s.height)
        val out = IntArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (y in y0 until y1) for (x in x0 until x1) out[i++] = s.pixels[y * s.width + x]
        return out
    }

    private fun advance(ms: Long) {
        var t = 0L
        while (t < ms) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            t += 100
        }
    }

    // ─── Semantics helpers ──────────────────────────────────────────────────────────────────

    private fun SemanticsNode.unclipped() = Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    /** Not cut by any parent and inside the window. */
    private fun SemanticsNode.fullyShown(s: Shot): Boolean {
        val u = unclipped(); val b = boundsInRoot
        val tol = 1f
        return u.width > 0 && u.height > 0 && Math.abs(u.left - b.left) < tol && Math.abs(u.top - b.top) < tol &&
            Math.abs(u.right - b.right) < tol && Math.abs(u.bottom - b.bottom) < tol &&
            u.left >= -tol && u.top >= -tol && u.right <= s.width + tol && u.bottom <= s.height + tol
    }

    private fun SemanticsNode.hasContent() = config.contains(SemanticsProperties.Text) || config.contains(SemanticsProperties.ContentDescription) ||
        config.contains(SemanticsProperties.EditableText) || isTouchTarget()

    private fun SemanticsNode.ancestors() = generateSequence(parent) { it.parent }

    private fun SemanticsNode.inScroller(horizontal: Boolean) = ancestors().any {
        it.config.contains(if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange)
    }

    private fun SemanticsNode.disabled() = config.contains(SemanticsProperties.Disabled) || ancestors().any { it.config.contains(SemanticsProperties.Disabled) }

    /** Inside (or itself) something you press: a button, chip, tab, switch or clickable row. */
    private fun SemanticsNode.interactive() = (sequenceOf(this) + ancestors()).any {
        it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsProperties.ToggleableState) ||
            it.config.getOrNull(SemanticsProperties.Role).let { r -> r == Role.Button || r == Role.Tab || r == Role.Checkbox || r == Role.Switch || r == Role.RadioButton || r == Role.DropdownList }
    }

    /**
     * The text laid out in the box it was given, as drawn. (The semantics action lays text out against
     * the parent's constraints, so centred text in a narrower box reports its lines elsewhere.)
     */
    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val out = ArrayList<TextLayoutResult>()
        val asked = (if (action(out)) out.firstOrNull() else null) ?: return null
        val i = asked.layoutInput
        if (i.constraints.maxWidth == asked.size.width) return asked
        return TextMeasurer(i.fontFamilyResolver, i.density, i.layoutDirection, cacheSize = 0).measure(
            i.text, i.style, i.overflow, i.softWrap, i.maxLines, i.placeholders,
            Constraints(maxWidth = asked.size.width), i.layoutDirection, i.density, i.fontFamilyResolver,
        )
    }

    /** "Button \"Play\"": role (or kind) and the words a person would read on it. */
    private fun SemanticsNode.label(): String {
        val words = ArrayList<String>()
        fun walk(n: SemanticsNode, depth: Int) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { words += it }
            n.config.getOrNull(SemanticsProperties.Text)?.let { t -> words += t.map { it.text } }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { words += it.text }
            if (depth < 8 && words.sumOf { it.length } < 60) n.children.forEach { walk(it, depth + 1) }
        }
        walk(this, 0)
        val kind = config.getOrNull(SemanticsProperties.Role)?.toString() ?: when {
            config.contains(SemanticsActions.SetText) -> "Text field"
            config.contains(SemanticsActions.SetProgress) -> "Slider"
            config.contains(SemanticsProperties.ToggleableState) -> "Toggle"
            config.contains(SemanticsActions.OnClick) -> "Clickable"
            config.contains(SemanticsProperties.Focused) -> "Focusable"
            config.contains(SemanticsProperties.Text) -> "text"
            else -> "Element"
        }
        val text = words.filter { it.isNotBlank() }.joinToString(" ").short()
        return if (text.isEmpty()) kind else "$kind \"$text\""
    }

    private fun String.short() = replace('\n', ' ').trim().let { if (it.length > 40) it.take(39) + "…" else it }

    private fun fmt(v: Double) = if (Math.abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else "%.1f".format(v)
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

}
