package com.kirtanya.reel.ui.tv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** The TV motion numbers (docs/SMOOTH_UI_PLAN.md §3) live in [Tokens.Motion]; this short name reads better here. */
internal typealias TvMotion = com.kirtanya.reel.ui.theme.Tokens.Motion

/**
 * A page's vertical glide: scroll only as far as needed to show the focused item plus [revealPx] under it
 * (the name under an episode still), over [TvMotion.columnGlideMs] with the shared easing. Compose's TV default pivots
 * the item to ~30 % from the top (right for rows, wrong for a hero that must stay put) and springs.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class ColumnGlideSpec(private val revealPx: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(TvMotion.columnGlideMs, easing = TvMotion.easing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size + revealPx
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f -> offset
            // Down: just enough, but never past the item's own top.
            else -> minOf(offset, trailing - containerSize)
        }
    }
}

/**
 * A row's horizontal slide (the Netflix feel): the focused card moves to the row's start line ([startPx], the side
 * padding) and the row slides under it. The scroller clamps at both ends, so the first and last cards just stop.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class RowPivotSpec(private val startPx: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(TvMotion.rowSlideMs, easing = TvMotion.easing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - startPx
}

/** Page glide for the [content] (a `LazyColumn` and the rows inside it, which override it with [TvRowPivot]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvColumnGlide(reveal: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { reveal.toPx() }
    val spec = remember(px) { ColumnGlideSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Row slide for a `LazyRow` composed in [content]: the focused card goes to [start] from the row's edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvRowPivot(start: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { start.toPx() }
    val spec = remember(px) { RowPivotSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
