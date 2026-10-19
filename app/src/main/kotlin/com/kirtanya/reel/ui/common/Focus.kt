package com.kirtanya.reel.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Clickable for touch, focusable for the TV remote: focused = scale up + white outline.
 * [onFocus] lets TV screens react (e.g. swap the backdrop).
 */
fun Modifier.focusCard(
    shape: Shape = RoundedCornerShape(Tokens.Radius.m),
    scale: Float = Tokens.Motion.focusScale,
    onFocus: (() -> Unit)? = null,
    /** Long-press (touch) or hold OK (remote). */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, tween(Tokens.Motion.focusMs), label = "cardFocus")
    if (onFocus != null) androidx.compose.runtime.LaunchedEffect(focused) { if (focused) onFocus() }
    // Afterglow focus: lift, accent ring and an accent glow (the remote's "you are here").
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focused) Modifier.shadow(24.dp, shape, ambientColor = Tokens.Colors.accent, spotColor = Tokens.Colors.accent) else Modifier)
        .border(Tokens.Size.focusBorder, if (focused) Tokens.Colors.accentBright else Color.Transparent, shape)
        .clip(shape)
        .combinedClickable(interactionSource = interaction, indication = ripple(), onLongClick = onLongClick, onClick = onClick)
}

fun Modifier.roundedClip(radius: Dp) = clip(RoundedCornerShape(radius))

/**
 * Scroll only as far as needed to show the focused item. Compose's TV default "pivots" focused
 * items to ~30 % from the top, which is right for rows but cuts off a page's hero (title page).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
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
