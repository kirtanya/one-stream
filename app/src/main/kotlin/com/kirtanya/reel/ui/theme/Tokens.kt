package com.kirtanya.reel.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtanya.reel.R

/**
 * "Afterglow" design tokens (docs/DESIGN_STYLE.md). Every colour, size and motion value in
 * Reel comes from here; screens never hard-code.
 */
object Tokens {
    object Colors {
        // Night canvas and surface steps (depth from steps + hairlines, never shadows).
        val canvas = Color(0xFF08080C)
        val surface1 = Color(0xFF111116)
        val surface2 = Color(0xFF18181F)
        val surface3 = Color(0xFF23232C)
        val glass = Color(0xB318181F)
        val hairline = Color(0x12FFFFFF)
        val hairlineStrong = Color(0x1FFFFFFF)

        val text = Color(0xFFF4F4F6)
        val textSecondary = Color(0xFFA1A1AA)
        val textTertiary = Color(0xFF6B6B75)

        // The one accent: violet light.
        val accent = Color(0xFF8B5CF6)
        val accentBright = Color(0xFFA78BFA)
        val accentDeep = Color(0xFF6D28D9)
        val onAccent = Color(0xFFFFFFFF)
        val ringStart = Color(0xFFC4B5FD)
        val ringEnd = Color(0xFF7C3AED)

        val good = Color(0xFF4ADE80)
        val warn = Color(0xFFFBBF24)
        val danger = Color(0xFFF87171)
        val scrim = Color(0xCC08080C)

        // Older names, kept so existing screens map onto Afterglow.
        val bg = canvas
        val surface = surface1
        val surfaceHigh = surface2
        val outline = hairlineStrong
        val textMuted = textSecondary
        val focus = accentBright
        val onFocus = text
    }

    object Gradients {
        val brand = Brush.linearGradient(listOf(Colors.accentBright, Colors.accentDeep))
        val ring = Brush.linearGradient(listOf(Colors.ringStart, Colors.ringEnd))
        val progress = Brush.horizontalGradient(listOf(Colors.accentDeep, Colors.accentBright))
    }

    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        val xxxl = 48.dp
        /** TV overscan-safe margins (10-foot UI). */
        val tvSafeH = 48.dp
        val tvSafeV = 27.dp
        /** Phone side gutter. */
        val phoneGutter = 20.dp
        /** Room the floating bottom bar takes (bar + gap) so content can scroll clear of it. */
        val bottomBarClearance = 112.dp
    }

    object Radius {
        val xs = 8.dp
        val s = 12.dp
        val m = 16.dp
        val media = 20.dp
        val l = 24.dp
        val xl = 32.dp
        val pill = 999.dp
    }

    object Size {
        val touchTarget = 48.dp
        val iconButton = 48.dp
        val progressBar = 4.dp
        val focusBorder = 2.dp
        val ring = 2.dp
        val hairline = 1.dp
    }

    val fontFamily = FontFamily(
        Font(R.font.instrument_sans_regular, FontWeight.Normal),
        Font(R.font.instrument_sans_medium, FontWeight.Medium),
        Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
        Font(R.font.instrument_sans_semibold, FontWeight.Bold),
    )

    object Type {
        private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) =
            TextStyle(fontFamily = fontFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

        val hero = style(40, 44, FontWeight.SemiBold, -0.8f)
        val display = style(32, 38, FontWeight.SemiBold, -0.5f)
        val title = style(22, 28, FontWeight.SemiBold, -0.2f)
        val heading = style(18, 24, FontWeight.SemiBold)
        val body = style(15, 22, FontWeight.Normal)
        val label = style(14, 18, FontWeight.Medium)
        val caption = style(12, 16, FontWeight.Medium, 0.1f)
    }

    object Motion {
        const val focusScale = 1.06f
        const val focusMs = 160
        const val stateMs = 220
        const val ambientMs = 600
    }
}
