package com.kirtanya.reel.ui.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Phone-only motion helpers (docs/SMOOTH_UI_PLAN.md §2–3): everything animates in the draw / layout phase
// (graphicsLayer, drawWithContent, Canvas lambdas), never by recomposing.

// TODO(merge): move to Tokens.Motion (mirrors the shared emphasised-decelerate curve and the 120 ms press / 260 ms slide times).
internal val EmphasisedDecelerate = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val PRESS_MS = 120
internal const val SLIDE_MS = 260

/**
 * Press feedback: the element shrinks a touch while a finger is down, and springs back on release or once
 * the finger starts to drag (so scrolling a list never leaves a card pinched). Observed in the Initial pass:
 * it never consumes, so the element's own click still works. Draw phase only.
 */
internal fun Modifier.pressScale(pressed: Float = 0.96f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    this
        .pointerInput(Unit) {
            coroutineScope {
                val scope = this
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    scope.launch { scale.animateTo(pressed, tween(PRESS_MS, easing = EmphasisedDecelerate)) }
                    var held = true
                    while (held) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        held = change != null && change.pressed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                    }
                    scope.launch { scale.animateTo(1f, tween(PRESS_MS, easing = EmphasisedDecelerate)) }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

// ─── Skeleton shimmer ──────────────────────────────────────────────────────────────────────

/** The sweep position (0..1) shared by every placeholder of one skeleton; null = static placeholders (TV). */
private val LocalShimmer = compositionLocalOf<State<Float>?> { null }

/**
 * Wraps a loading screen's placeholders so they share one sweep. One infinite transition runs while this is
 * composed (only while loading); [animate] false (TV: nothing animates) leaves them still.
 */
@Composable
internal fun SkeletonHost(animate: Boolean = true, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
    val phase = rememberInfiniteTransition(label = "skeleton").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    CompositionLocalProvider(LocalShimmer provides phase) { content() }
}

/** A placeholder block: the raised surface with a soft highlight sweeping across it (drawn, never recomposed). */
@Composable
internal fun Modifier.skeleton(radius: Dp = Tokens.Radius.m, base: Color = Tokens.Colors.surface2): Modifier {
    val phase = LocalShimmer.current
    val glint = Tokens.Colors.text
    return this
        .clip(RoundedCornerShape(radius))
        .background(base)
        .then(
            if (phase == null) Modifier else Modifier.drawWithContent {
                drawContent()
                val band = size.width * 0.6f
                val x = -band + (size.width + band) * phase.value
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, glint.copy(alpha = 0.07f), Color.Transparent),
                        startX = x, endX = x + band,
                    ),
                )
            },
        )
}

/** A hero-shaped placeholder: fades into the canvas at the bottom (where the text sits), like a real hero's scrim. */
@Composable
internal fun Modifier.skeletonHero(): Modifier {
    val canvas = Tokens.Colors.canvas
    return skeleton(0.dp, Tokens.Colors.surface1).drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to canvas))
    }
}

@Composable
internal fun SkeletonBlock(width: Dp?, height: Dp, modifier: Modifier = Modifier, radius: Dp = Tokens.Radius.s, base: Color = Tokens.Colors.surface3) {
    Box((if (width != null) modifier.width(width) else modifier).height(height).skeleton(radius, base))
}

// ─── Hero pager dots ───────────────────────────────────────────────────────────────────────

/**
 * Page dots that follow the finger: the long dot slides from page to page with the swipe. One Canvas that
 * reads the pager in its draw lambda, so a swipe redraws 6 shapes and recomposes nothing.
 */
@Composable
internal fun PagerDots(pager: PagerState, modifier: Modifier = Modifier) {
    val count = pager.pageCount
    val density = LocalDensity.current
    val dot = with(density) { 6.dp.toPx() }
    val long = with(density) { 18.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val active = Tokens.Colors.accent
    val rest = Tokens.Colors.surface3
    val total = long + (count - 1) * (dot + gap)
    Canvas(modifier.width(with(density) { total.toDp() }).height(6.dp)) {
        val position = pager.currentPage + pager.currentPageOffsetFraction
        var x = 0f
        for (i in 0 until count) {
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            val w = dot + (long - dot) * near
            drawRoundRect(lerp(rest, active, near), Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
            x += w + gap
        }
    }
}
