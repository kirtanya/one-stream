package com.kirtanya.reel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun ReelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tokens.Colors.accent,
            onPrimary = Tokens.Colors.onAccent,
            background = Tokens.Colors.bg,
            onBackground = Tokens.Colors.text,
            surface = Tokens.Colors.surface,
            onSurface = Tokens.Colors.text,
            surfaceVariant = Tokens.Colors.surfaceHigh,
            onSurfaceVariant = Tokens.Colors.textMuted,
            outline = Tokens.Colors.outline,
            error = Tokens.Colors.danger,
        ),
        content = content,
    )
}
