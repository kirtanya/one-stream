package com.kirtanya.reel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every colour, size and motion value in Reel comes from here. Screens never hard-code. */
object Tokens {
    object Colors {
        val bg = Color(0xFF0D0E11)
        val surface = Color(0xFF17191E)
        val surfaceHigh = Color(0xFF22252C)
        val outline = Color(0xFF2E323B)
        val text = Color(0xFFF2F3F5)
        val textMuted = Color(0xFF9AA0AB)
        val accent = Color(0xFF8BF0A8)
        val onAccent = Color(0xFF0D0E11)
        val focus = Color(0xFFFFFFFF)
        val onFocus = Color(0xFF0D0E11)
        val good = Color(0xFF8BF0A8)
        val warn = Color(0xFFFFC857)
        val danger = Color(0xFFFF6B6B)
        val scrim = Color(0xCC000000)
    }

    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        /** TV overscan-safe margins (10-foot UI). */
        val tvSafeH = 48.dp
        val tvSafeV = 27.dp
        /** Phone side gutter. */
        val phoneGutter = 16.dp
    }

    object Radius {
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val pill = 999.dp
    }

    object Size {
        val touchTarget = 48.dp
        val progressBar = 4.dp
        val focusBorder = 2.dp
    }

    object Type {
        val display = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        val heading = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
        val label = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    }

    object Motion {
        const val focusScale = 1.06f
        const val focusMs = 150
    }
}
