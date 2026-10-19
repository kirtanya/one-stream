package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

/** Afterglow surface card: surface1, radius 24, hairline. */
@Composable
fun ReelCard(modifier: Modifier = Modifier, padding: Dp = Tokens.Space.l, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Column(
        modifier
            .clip(shape)
            .background(Tokens.Colors.surface1)
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        content = content,
    )
}

/** Small meta chip: "2019", "2h 19m", "★ 8.4", "4K". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Boolean = false, onGlass: Boolean = false) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Row(
        modifier
            .clip(shape)
            .background(if (accent) Tokens.Colors.accent.copy(alpha = 0.18f) else if (onGlass) Tokens.Colors.glass else Tokens.Colors.surface2)
            .border(Tokens.Size.hairline, if (accent) Tokens.Colors.accent.copy(alpha = 0.45f) else Tokens.Colors.hairline, shape)
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        if (icon != null) Icon(icon, null, tint = if (accent) Tokens.Colors.accentBright else Tokens.Colors.textSecondary, modifier = Modifier.size(14.dp))
        Text(text, style = Tokens.Type.caption, color = if (accent) Tokens.Colors.accentBright else Tokens.Colors.text, maxLines = 1)
    }
}

/** Round glass button (48 dp): on images and in headers. Focus ring + glow on TV. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    Box(
        modifier
            .size(Tokens.Size.iconButton)
            .focusCard(shape = CircleShape, onClick = onClick)
            .background(Tokens.Colors.glass)
            .border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Tokens.Colors.text, modifier = Modifier.size(22.dp))
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(11.dp).size(8.dp).clip(CircleShape).background(Tokens.Colors.danger))
    }
}

/**
 * Circle image with the violet gradient ring (cast, providers, quick picks), label below.
 * [placeholder] shows when there's no image (e.g. initials).
 */
@Composable
fun RingAvatar(
    imageUrl: String?,
    label: String?,
    size: Dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    placeholder: String? = null,
    ring: Boolean = true,
) {
    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        val inner = Modifier
            .size(size)
            .then(if (onClick != null) Modifier.focusCard(shape = CircleShape, onClick = onClick) else Modifier)
            .then(if (ring) Modifier.border(Tokens.Size.ring, Tokens.Gradients.ring, CircleShape) else Modifier.border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, CircleShape))
            .padding(if (ring) 4.dp else 0.dp)
            .clip(CircleShape)
            .background(Tokens.Colors.surface2)
        Box(inner, contentAlignment = Alignment.Center) {
            if (imageUrl != null) RemoteImage(imageUrl, Modifier.fillMaxWidth().fillMaxHeight())
            else if (placeholder != null) Text(placeholder, style = Tokens.Type.heading, color = Tokens.Colors.text)
        }
        if (label != null) Text(label, style = Tokens.Type.caption, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (sublabel != null) Text(sublabel, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** 4 dp track with the brand-gradient fill. */
@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = Tokens.Size.progressBar, track: Color = Tokens.Colors.surface3) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(Tokens.Gradients.progress))
    }
}

/** Row heading with an optional tonal action on the right ("See all"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = Tokens.Type.label,
                color = Tokens.Colors.accentBright,
                modifier = Modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = onAction).padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xs),
            )
        }
    }
}

/** Big empty state: icon in a glowing ring, title, line of help, optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Tokens.Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Tokens.Colors.accent.copy(alpha = 0.14f)).border(Tokens.Size.ring, Tokens.Gradients.ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Tokens.Colors.accentBright, modifier = Modifier.size(30.dp)) }
        Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text, textAlign = TextAlign.Center)
        Text(body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
