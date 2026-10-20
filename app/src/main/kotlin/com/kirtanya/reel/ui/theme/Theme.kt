package com.kirtanya.reel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.sp

/** Reel's live Material 3 scheme (see [AccentState]) and type scale, so stock components match. */
@Composable
fun ReelTheme(content: @Composable () -> Unit) {
    val dark = AccentState.isDark(isSystemInDarkTheme())
    AccentHost(dark)
    MaterialTheme(
        colorScheme = AccentState.scheme,
        typography = Typography(
            bodyLarge = Tokens.Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = Tokens.Type.body,
            bodySmall = Tokens.Type.caption,
            labelLarge = Tokens.Type.label,
            labelMedium = Tokens.Type.caption,
            labelSmall = Tokens.Type.caption.copy(fontSize = 11.sp),
            titleSmall = Tokens.Type.label,
            titleMedium = Tokens.Type.heading,
            titleLarge = Tokens.Type.title,
            headlineSmall = Tokens.Type.display,
            headlineMedium = Tokens.Type.display.copy(fontSize = 28.sp, lineHeight = 36.sp),
            headlineLarge = Tokens.Type.hero,
        ),
        content = content,
    )
}
