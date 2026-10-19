package com.kirtanya.reel.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

enum class ButtonKind { Primary, Secondary, Ghost }

/**
 * A button that works with touch and with a TV remote: focus = white fill, slight scale.
 */
@Composable
fun ReelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) Tokens.Motion.focusScale else 1f, tween(Tokens.Motion.focusMs), label = "focus")
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    val (bg, fg) = when {
        focused -> Tokens.Colors.focus to Tokens.Colors.onFocus
        kind == ButtonKind.Primary -> Tokens.Colors.accent to Tokens.Colors.onAccent
        kind == ButtonKind.Secondary -> Tokens.Colors.surfaceHigh to Tokens.Colors.text
        else -> Color.Transparent to Tokens.Colors.text
    }
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.Ghost && !focused) Modifier.border(1.dp, Tokens.Colors.outline, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, onClick = onClick)
            .heightIn(min = Tokens.Size.touchTarget)
            .padding(horizontal = Tokens.Space.l, vertical = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        if (text.isNotEmpty()) Text(text, style = Tokens.Type.label, color = fg)
    }
}
