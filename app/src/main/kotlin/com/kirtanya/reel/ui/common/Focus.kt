package com.kirtanya.reel.ui.common

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.input.OkKeyGuard
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Clickable for touch, focusable for the TV remote.
 *
 * Phone: accent outline when focused (keyboard), ripple on touch.
 * TV: no ripple, no scale, no glow, no composition cost per card. One modifier node holds the focus state and
 * draws, in the draw phase only, a white [Tokens.Size.focusBorder] ring that fades over [Tokens.Motion.focusFadeMs].
 * [outset] = the ring sits [Tokens.Size.focusGap] outside the card (like ReelButton; for cards in rows, which leave
 * room for it), else inside the card's edge (list rows, tiles). Outset cards also dim a little while not focused so
 * the focused one reads at once. [onFocus] lets TV screens react (e.g. swap the backdrop).
 */
@Composable
fun Modifier.focusCard(
    shape: Shape = RoundedCornerShape(Tokens.Radius.m),
    onFocus: (() -> Unit)? = null,
    /** Long-press (touch) or hold OK (remote). */
    onLongClick: (() -> Unit)? = null,
    outset: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    if (LocalIsTv.current) {
        return this
            .then(TvCardElement(shape, outset, onLongClick != null, onFocus))
            .clip(shape)
            .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongClick, onClick = onClick)
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return this
        .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
        .border(Tokens.Size.focusBorder, if (focused) Tokens.Colors.accentBright else Color.Transparent, shape)
        .clip(shape)
        .okGuard(hasLongClick = onLongClick != null)
        .combinedClickable(interactionSource = interaction, indication = ripple(), onLongClick = onLongClick, onClick = onClick)
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)
private val DpadKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/**
 * True while a D-pad key is auto-repeating (held). Rows and the column read it to shorten their slide so a held key
 * keeps up instead of queueing animations. Written by the focused card's key handler, read by the scroll specs.
 */
internal object TvKeyRepeat {
    @Volatile var held = false
}

/** One key event through the OK guard and the held-key tracker; true = swallow it. */
private fun guardKey(e: KeyEvent, guard: OkKeyGuard, hasLongClick: Boolean): Boolean {
    if (e.key in DpadKeys) {
        TvKeyRepeat.held = e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0
        return false
    }
    if (e.key !in OkKeys) return false
    val verdict = when (e.type) {
        KeyEventType.KeyDown -> guard.onDown(e.nativeKeyEvent.repeatCount, hasLongClick)
        KeyEventType.KeyUp -> guard.onUp()
        else -> OkKeyGuard.Verdict.Pass
    }
    return verdict == OkKeyGuard.Verdict.Swallow
}

/**
 * Lets a held remote OK fire exactly one click (see [OkKeyGuard]). Put it right before the clickable.
 * Compose clicks on key-up, so without it the repeats and release of a press that opened this screen
 * click whatever is focused here.
 */
fun Modifier.okGuard(hasLongClick: Boolean = false): Modifier = this.then(OkGuardElement(hasLongClick))

private data class OkGuardElement(val hasLongClick: Boolean) : ModifierNodeElement<OkGuardNode>() {
    override fun create() = OkGuardNode(hasLongClick)
    override fun update(node: OkGuardNode) { node.hasLongClick = hasLongClick }
    override fun InspectorInfo.inspectableProperties() { name = "okGuard" }
}

private class OkGuardNode(var hasLongClick: Boolean) : Modifier.Node(), FocusEventModifierNode, KeyInputModifierNode {
    private val guard = OkKeyGuard()
    override fun onFocusEvent(focusState: FocusState) { if (!focusState.isFocused) guard.reset() }
    override fun onPreKeyEvent(event: KeyEvent) = guardKey(event, guard, hasLongClick)
    override fun onKeyEvent(event: KeyEvent) = false
}

/** Dim of an unfocused outset card (a share of canvas laid over it). */
private const val DIM = 0.22f

private data class TvCardElement(
    val shape: Shape,
    val outset: Boolean,
    val hasLongClick: Boolean,
    val onFocus: (() -> Unit)?,
) : ModifierNodeElement<TvCardNode>() {
    override fun create() = TvCardNode(shape, outset, hasLongClick, onFocus)
    override fun update(node: TvCardNode) {
        node.shape = shape
        node.outset = outset
        node.hasLongClick = hasLongClick
        node.onFocus = onFocus
    }
    override fun InspectorInfo.inspectableProperties() { name = "tvCard" }
}

/**
 * The TV card's focus ring, dim and OK guard in one node: no `composed`, no `remember`, no `LaunchedEffect`, no
 * snapshot state per card. The ring's alpha is a plain float animated by a coroutine that only runs for the two
 * cards that change on a focus move; each frame invalidates this node's draw only (never composition or layout).
 */
private class TvCardNode(
    var shape: Shape,
    var outset: Boolean,
    var hasLongClick: Boolean,
    var onFocus: (() -> Unit)?,
) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode, KeyInputModifierNode {
    private val guard = OkKeyGuard()
    private var focused = false
    private var alpha = 0f
    private var job: Job? = null
    private var outline: Outline? = null
    private var outlineSize = Size.Zero

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (now == focused) return
        focused = now
        if (now) onFocus?.invoke() else guard.reset()
        job?.cancel()
        val from = alpha
        val to = if (now) 1f else 0f
        job = coroutineScope.launch {
            animate(from, to, animationSpec = tween(Tokens.Motion.focusFadeMs, easing = Tokens.Motion.easing)) { v, _ ->
                alpha = v
                invalidateDraw()
            }
        }
    }

    override fun onPreKeyEvent(event: KeyEvent) = guardKey(event, guard, hasLongClick)
    override fun onKeyEvent(event: KeyEvent) = false

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha
        if (outset && a < 1f) {
            drawOutline(outlineFor(size, layoutDirection), Tokens.Colors.canvas, alpha = DIM * (1f - a))
        }
        if (a <= 0.003f) return
        val w = Tokens.Size.focusBorder.toPx()
        val color = Tokens.Colors.text.copy(alpha = a)
        // Stroke centre: half a stroke past the gap (outset) or half a stroke inside the edge.
        val grow = if (outset) Tokens.Size.focusGap.toPx() + w / 2 else -w / 2
        val radius = (shape as? CornerBasedShape)?.topStart?.toPx(size, this)
        if (radius != null) {
            drawRoundRect(
                color, topLeft = Offset(-grow, -grow), size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius((radius + grow).coerceAtLeast(0f)), style = Stroke(w),
            )
        } else {
            // Not a rounded rectangle: the shape's own edge, stroked from the inside (twice as wide, half is clipped by nothing: fine).
            drawOutline(outlineFor(size, layoutDirection), color, style = Stroke(w * 2))
        }
    }

    private fun DrawScope.outlineFor(size: Size, dir: LayoutDirection): Outline {
        val cached = outline
        if (cached != null && outlineSize == size) return cached
        return shape.createOutline(size, dir, this).also {
            outline = it
            outlineSize = size
        }
    }
}

fun Modifier.roundedClip(radius: Dp) = clip(RoundedCornerShape(radius))

// ─── Fixed-focus scrolling (Netflix / Hotstar feel) ────────────────────────────────────────────

/** The slide for a scroll: shorter while a D-pad key is held, so moves don't queue up behind each other. */
private fun scrollSpec(ms: Int): AnimationSpec<Float> =
    tween(if (TvKeyRepeat.held) Tokens.Motion.heldScrollMs else ms, easing = Tokens.Motion.easing)

/**
 * TV row: the focused card stays at the row's start ([startPx] = the side padding) and the row slides under it.
 * Near the end of a row the list can't scroll further, so the ring simply moves along the last cards.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class RowStartSpec(private val startPx: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = scrollSpec(Tokens.Motion.rowSlideMs)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - startPx
}

/**
 * TV Home's column: the focused row's cards settle at a fixed line ([cardLinePx]) just under the info block, so every
 * row sits in the same place. Something short (a row's "See all", under [shortPx] tall) lines up by its own top at
 * [topPx] instead.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class RowLineSpec(private val cardLinePx: Float, private val topPx: Float, private val shortPx: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = scrollSpec(Tokens.Motion.columnGlideMs)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - if (size < shortPx) topPx else cardLinePx
}

/** [RowLineSpec] for TV Home's column, from the row geometry in [Tokens]; [topPad] is the column's top content padding. */
@Composable
fun rememberHomeColumnSpec(topPad: Dp): RowLineSpec {
    val density = LocalDensity.current
    return remember(density, topPad) {
        with(density) { RowLineSpec((topPad + Tokens.Cinema.tvRowCardTop).toPx(), topPad.toPx(), 44.dp.toPx()) }
    }
}

/** Provides [spec] to the scrollables inside [content] (TV Home's column). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WithBringIntoView(spec: androidx.compose.foundation.gestures.BringIntoViewSpec, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Wrap a TV row's LazyRow: the focused card stays at [sidePadding] and the row slides under it. Nothing on the phone.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FixedFocusRow(sidePadding: Dp, content: @Composable () -> Unit) {
    if (!LocalIsTv.current) {
        content()
        return
    }
    val density = LocalDensity.current
    val spec = remember(density, sidePadding) { RowStartSpec(with(density) { sidePadding.toPx() }) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Scroll only as far as needed to show the focused item. Compose's TV default "pivots" focused
 * items to ~30 % from the top, which is right for rows but cuts off a page's hero (title page).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = scrollSpec(Tokens.Motion.columnGlideMs)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

/** Puts the remote's focus on [requester] once it's laid out (retries a few frames). */
@Composable
fun RequestFocusOnce(requester: androidx.compose.ui.focus.FocusRequester, key: Any? = Unit) {
    androidx.compose.runtime.LaunchedEffect(key) {
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            // requestFocus() never throws when the node isn't attached yet; the Boolean overload says if it worked.
            if (runCatching { requester.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/** The first item of an [EnterRowAtStart] row: [rowStart] attaches it, and says while it's composed. */
class RowStart {
    val requester = androidx.compose.ui.focus.FocusRequester()
    internal var composed = 0
}

val LocalRowStart = androidx.compose.runtime.staticCompositionLocalOf<RowStart?> { null }

/** Marks the first item of a row: ↓ into the row lands here (see [EnterRowAtStart]). No-op outside one. */
@Composable
fun Modifier.rowStart(first: Boolean = true): Modifier {
    val start = LocalRowStart.current
    if (!first || start == null) return this
    androidx.compose.runtime.DisposableEffect(start) {
        start.composed++
        onDispose { start.composed-- }
    }
    return this.focusRequester(start.requester)
}

/**
 * TV: a row of a page (episodes, cast, reviews…) that ↓ enters at its first item ([rowStart]), not at
 * whatever sits right below the focused item. If that item isn't laid out (the row is scrolled along),
 * ↓ moves the usual way.
 */
@Composable
fun EnterRowAtStart(content: @Composable () -> Unit) {
    val start = remember { RowStart() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.foundation.layout.Box(
        Modifier
            .focusProperties {
                onEnter = {
                    // Only when the first item is composed (a row scrolled along may have let it go): then ↓ moves as usual.
                    // The move itself goes ahead (cancelling it leaves the key unhandled, and the system then moves ↓ again
                    // from wherever focus is); the first item takes focus on the next frame.
                    if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down && start.composed > 0) {
                        scope.launch { start.requester.requestFocus() }
                    }
                }
            }
            .focusGroup(),
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalRowStart provides start) { content() } }
}
