package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Material 3 buttons:
 * - [ButtonKind.Primary] — filled (primary): one per screen (Play, Resume, Add).
 * - [ButtonKind.Secondary] — tonal (secondary container).
 * - [ButtonKind.Ghost] — outlined; also unselected chips.
 * - [ButtonKind.Glass] — for use on images: translucent with hairline.
 * 40 dp tall (48 when [large]) with a 48 dp touch area; on TV a focused button gets a ring (no lift, no glow).
 */
enum class ButtonKind { Primary, Secondary, Ghost, Glass }

@Composable
fun ReelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    // TV: the focused button, whatever its kind, turns solid accent (white) with a ring a little
    // outside it, like the big streaming apps; a ring drawn on the button itself vanishes on a white one.
    val tv = LocalIsTv.current
    val lit = tv && focused
    val fg = when {
        lit -> Tokens.Colors.onAccent
        kind == ButtonKind.Primary -> Tokens.Colors.onAccent
        kind == ButtonKind.Secondary -> Tokens.Colors.onTonal
        else -> Tokens.Colors.text
    }
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .then(if (tv) Modifier.border(Tokens.Size.focusBorder, if (focused) Tokens.Colors.text else Color.Transparent, shape).padding(Tokens.Size.focusGap) else Modifier)
            .clip(shape)
            .then(
                when {
                    lit -> Modifier.background(Tokens.Colors.accent)
                    kind == ButtonKind.Primary -> Modifier.background(Tokens.Colors.accent)
                    kind == ButtonKind.Secondary -> Modifier.background(Tokens.Colors.tonal)
                    kind == ButtonKind.Ghost -> Modifier.background(Color.Transparent)
                    else -> Modifier.background(Tokens.Colors.glass)
                },
            )
            .then(
                when {
                    lit -> Modifier
                    focused -> Modifier.border(Tokens.Size.focusBorder, Tokens.Colors.text, shape)
                    kind == ButtonKind.Ghost || kind == ButtonKind.Glass -> Modifier.border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape)
                    else -> Modifier
                },
            )
            .okGuard()
            // TV: no grey focus wash from the ripple (focus is the white fill and the ring).
            .clickable(interactionSource = interaction, indication = if (tv) null else ripple(), enabled = enabled, onClick = onClick)
            .heightIn(min = if (large) Tokens.Size.buttonLarge else Tokens.Size.button)
            .padding(
                start = if (icon != null && text.isNotEmpty()) Tokens.Space.m else Tokens.Space.l,
                end = if (text.isEmpty()) Tokens.Space.m else Tokens.Space.l,
                top = Tokens.Space.xs,
                bottom = Tokens.Space.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, tint = fg, modifier = Modifier.size(18.dp))
        if (text.isNotEmpty()) Text(text, style = Tokens.Type.label, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
