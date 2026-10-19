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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Afterglow buttons:
 * - [ButtonKind.Primary] — gradient pill with an accent glow (one per screen: Play, Resume, Add).
 * - [ButtonKind.Secondary] — tonal: surface2 with accent-bright text ("Follow" in the reference).
 * - [ButtonKind.Ghost] — hairline pill, white text.
 * - [ButtonKind.Glass] — for use on images: translucent with hairline.
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
    val scale by animateFloatAsState(if (focused) Tokens.Motion.focusScale else 1f, tween(Tokens.Motion.focusMs), label = "focus")
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    val fg = when (kind) {
        ButtonKind.Primary -> Tokens.Colors.onAccent
        ButtonKind.Secondary -> Tokens.Colors.accentBright
        else -> Tokens.Colors.text
    }
    val glow = kind == ButtonKind.Primary && enabled
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            // The accent glow behind primary actions (stronger when focused on TV).
            .then(if (glow) Modifier.shadow(if (focused) 28.dp else 16.dp, shape, ambientColor = Tokens.Colors.accent, spotColor = Tokens.Colors.accent) else Modifier)
            .clip(shape)
            .then(
                when (kind) {
                    ButtonKind.Primary -> Modifier.background(Tokens.Gradients.brand)
                    ButtonKind.Secondary -> Modifier.background(Tokens.Colors.surface2)
                    ButtonKind.Ghost -> Modifier.background(Color.Transparent)
                    ButtonKind.Glass -> Modifier.background(Tokens.Colors.glass)
                },
            )
            .border(
                if (focused) Tokens.Size.focusBorder else Tokens.Size.hairline,
                when {
                    focused -> Tokens.Colors.text
                    kind == ButtonKind.Primary -> Color.White.copy(alpha = 0.18f)
                    else -> Tokens.Colors.hairlineStrong
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, onClick = onClick)
            .heightIn(min = if (large) 56.dp else Tokens.Size.touchTarget)
            .padding(horizontal = if (text.isEmpty()) Tokens.Space.m else Tokens.Space.l, vertical = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, tint = fg, modifier = Modifier.size(if (large) 24.dp else 20.dp))
        if (text.isNotEmpty()) Text(text, style = if (large) Tokens.Type.heading.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)) else Tokens.Type.label, color = fg, maxLines = 1, softWrap = false)
    }
}
