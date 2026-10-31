package com.kirtanya.reel.ui.common

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.core.input.OkKeyGuard
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Clickable for touch, focusable for the TV remote: focused = accent-bright outline, nothing else.
 * No scale and no glow: both cost a re-layer / blurred shadow per focus move, which the weak TV chip shows
 * as lag. [onFocus] lets TV screens react (e.g. swap the backdrop).
 */
fun Modifier.focusCard(
    shape: Shape = RoundedCornerShape(Tokens.Radius.m),
    onFocus: (() -> Unit)? = null,
    /** Long-press (touch) or hold OK (remote). */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (onFocus != null) androidx.compose.runtime.LaunchedEffect(focused) { if (focused) onFocus() }
    this
        .border(Tokens.Size.focusBorder, if (focused) Tokens.Colors.accentBright else Color.Transparent, shape)
        .clip(shape)
        .okGuard(hasLongClick = onLongClick != null)
        .combinedClickable(interactionSource = interaction, indication = ripple(), onLongClick = onLongClick, onClick = onClick)
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

/**
 * Lets a held remote OK fire exactly one click (see [OkKeyGuard]). Put it right before the clickable.
 * Compose clicks on key-up, so without it the repeats and release of a press that opened this screen
 * click whatever is focused here.
 */
fun Modifier.okGuard(hasLongClick: Boolean = false): Modifier = composed {
    val guard = remember { OkKeyGuard() }
    this
        .onFocusChanged { if (!it.isFocused) guard.reset() }
        .onPreviewKeyEvent { e ->
            if (e.key !in OkKeys) return@onPreviewKeyEvent false
            val verdict = when (e.type) {
                KeyEventType.KeyDown -> guard.onDown(e.nativeKeyEvent.repeatCount, hasLongClick)
                KeyEventType.KeyUp -> guard.onUp()
                else -> OkKeyGuard.Verdict.Pass
            }
            verdict == OkKeyGuard.Verdict.Swallow
        }
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
