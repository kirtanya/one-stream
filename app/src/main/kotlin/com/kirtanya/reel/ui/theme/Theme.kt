package com.kirtanya.reel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Material 3 mapped onto Afterglow, so stock components (fields, switches, sheets) match. */
@Composable
fun ReelTheme(content: @Composable () -> Unit) {
    val c = Tokens.Colors
    // Stock components follow where the live accent is heading (not every animation frame).
    val target = AccentState.target
    val a = remember(target) { AccentTones.from(target) }
    AccentHost()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = a.base,
            onPrimary = c.onAccent,
            primaryContainer = a.deep,
            onPrimaryContainer = c.text,
            secondary = a.bright,
            background = c.canvas,
            onBackground = c.text,
            surface = c.surface1,
            onSurface = c.text,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surface1,
            surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface3,
            surfaceContainerLow = c.surface1,
            outline = c.hairlineStrong,
            outlineVariant = c.hairline,
            error = c.danger,
        ),
        typography = Typography(
            bodyLarge = Tokens.Type.body,
            bodyMedium = Tokens.Type.body,
            bodySmall = Tokens.Type.caption,
            labelLarge = Tokens.Type.label,
            labelMedium = Tokens.Type.caption,
            titleMedium = Tokens.Type.heading,
            titleLarge = Tokens.Type.title,
            headlineMedium = Tokens.Type.display,
        ),
        content = content,
    )
}
