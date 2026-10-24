package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

/** Surface card: surface container, radius 16, hairline. */
@Composable
fun ReelCard(modifier: Modifier = Modifier, padding: Dp = Tokens.Space.l, spacing: Dp = Tokens.Space.m, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Column(
        modifier
            .clip(shape)
            .background(Tokens.Colors.surface1)
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Canvas fade behind the status bar and a floating back button, so scrolled content
 * slides under it instead of colliding with it.
 */
@Composable
fun TopFade(modifier: Modifier = Modifier, extra: Dp = 80.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = extra)))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Tokens.Colors.canvas.copy(alpha = 0.92f), 0.6f to Tokens.Colors.canvas.copy(alpha = 0.6f), 1f to Color.Transparent)),
    )
}

/** Small meta chip: "2019", "2h 19m", "★ 8.4", "4K". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Boolean = false, onGlass: Boolean = false) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Row(
        modifier
            .clip(shape)
            .background(if (accent) Tokens.Colors.accent else if (onGlass) Tokens.Colors.glass else Tokens.Colors.surface2)
            .then(if (accent) Modifier else Modifier.border(Tokens.Size.hairline, Tokens.Colors.hairline, shape))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        if (icon != null) Icon(icon, null, tint = if (accent) Tokens.Colors.onAccent else Tokens.Colors.textSecondary, modifier = Modifier.size(12.dp))
        Text(text, style = Tokens.Type.caption, color = if (accent) Tokens.Colors.onAccent else Tokens.Colors.text, maxLines = 1)
    }
}

/** Round glass icon button (40 dp, 48 dp touch area): on images and in headers. Focus ring on TV. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(Tokens.Size.iconButton)
            .focusCard(shape = CircleShape, onClick = onClick)
            .background(Tokens.Colors.glass)
            .border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Tokens.Colors.text, modifier = Modifier.size(20.dp))
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).clip(CircleShape).background(Tokens.Colors.danger))
    }
}

/**
 * Circle image with the accent gradient ring (cast, providers, quick picks), label below.
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
        if (sublabel != null) Text(sublabel, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** 4 dp track with a primary fill. */
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

/** Empty state: icon in a tonal circle, title, line of help, optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Tokens.Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Tokens.Colors.tonal),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Tokens.Colors.onTonal, modifier = Modifier.size(26.dp)) }
        Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text, textAlign = TextAlign.Center)
        Text(body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** The One Stream app mark (same artwork as the launcher icon), as a rounded tile. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.kirtanya.reel.R.drawable.ic_launcher_bg), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        // The foreground is drawn on a 108 dp canvas with the mark in the middle 66 dp: scale it to fill the tile.
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.kirtanya.reel.R.drawable.ic_launcher_fg), null,
            Modifier.fillMaxSize().graphicsLayer { scaleX = 1.45f; scaleY = 1.45f },
        )
    }
}
