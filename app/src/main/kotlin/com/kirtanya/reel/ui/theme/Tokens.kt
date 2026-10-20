package com.kirtanya.reel.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
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
    /**
     * Colour roles, read from the live Material 3 scheme ([AccentState.scheme]): they follow the
     * seed (system accent, the user's pick, or the poster on screen) and light / dark.
     * Screens use these names; never hard-code a colour.
     */
    object Colors {
        private val c get() = AccentState.scheme

        // Canvas and surface steps (depth from tonal steps + hairlines, never shadows).
        val canvas: Color get() = c.surface
        val surface1: Color get() = c.surfaceContainer
        val surface2: Color get() = c.surfaceContainerHigh
        val surface3: Color get() = c.surfaceContainerHighest
        /** Controls sitting on images. */
        val glass: Color get() = c.surfaceContainerHigh.copy(alpha = 0.72f)
        val hairline: Color get() = c.outlineVariant.copy(alpha = 0.55f)
        val hairlineStrong: Color get() = c.outlineVariant

        val text: Color get() = c.onSurface
        val textSecondary: Color get() = c.onSurfaceVariant
        val textTertiary: Color get() = c.outline

        // Accent = Material primary. "Bright" is primary as text / rings on the canvas.
        val accent: Color get() = c.primary
        val accentBright: Color get() = c.primary
        val accentDeep: Color get() = c.primaryContainer
        val onAccent: Color get() = c.onPrimary
        val tonal: Color get() = c.secondaryContainer
        val onTonal: Color get() = c.onSecondaryContainer
        val ringStart: Color get() = c.primary
        val ringEnd: Color get() = c.tertiary

        private val isDark get() = c.surface.luminance() < 0.5f
        // Status colours, a tone that reads on the current surface (light text-safe in light mode).
        val good: Color get() = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
        val warn: Color get() = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
        val danger: Color get() = c.error
        val scrim: Color get() = c.surface.copy(alpha = 0.8f)

        // Older names, kept so existing screens map onto the scheme.
        val bg: Color get() = canvas
        val surface: Color get() = surface1
        val surfaceHigh: Color get() = surface2
        val outline: Color get() = hairlineStrong
        val textMuted: Color get() = textSecondary
        val focus: Color get() = accentBright
        val onFocus: Color get() = text
    }

    /** The same roles, always dark (the player's overlays sit on video). */
    object Night {
        private val c get() = AccentState.night
        val canvas: Color get() = c.surface
        val glass: Color get() = c.surfaceContainerHigh.copy(alpha = 0.78f)
        val hairline: Color get() = c.outlineVariant
        val text: Color get() = c.onSurface
        val textSecondary: Color get() = c.onSurfaceVariant
        val accent: Color get() = c.primary
        val onAccent: Color get() = c.onPrimary
        val accentDeep: Color get() = c.primaryContainer
    }

    object Gradients {
        val brand: Brush get() = SolidColor(Colors.accent)
        val ring: Brush get() = Brush.linearGradient(listOf(Colors.ringStart, Colors.ringEnd))
        val progress: Brush get() = SolidColor(Colors.accent)
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
        val phoneGutter = 16.dp
        /** Room the bottom navigation bar (80 dp + gesture area) takes, so content can scroll clear of it. */
        val bottomBarClearance = 104.dp
    }

    /** Material 3 shape scale. */
    object Radius {
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val media = 12.dp
        val l = 16.dp
        val xl = 28.dp
        val pill = 999.dp
    }

    object Size {
        val touchTarget = 48.dp
        /** Visible size; the touch area stays 48 dp. */
        val iconButton = 40.dp
        val button = 40.dp
        val buttonLarge = 48.dp
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

        // Material 3 type scale, set in Instrument Sans.
        /** TV hero title (headline large). */
        val hero = style(32, 40, FontWeight.SemiBold, -0.4f)
        /** Page titles (headline small). */
        val display = style(24, 32, FontWeight.SemiBold, -0.2f)
        /** Title large. */
        val title = style(20, 28, FontWeight.SemiBold)
        /** Title medium: section and card titles. */
        val heading = style(16, 24, FontWeight.SemiBold, 0.1f)
        /** Body medium. */
        val body = style(14, 20, FontWeight.Normal, 0.2f)
        /** Label large: buttons, row titles. */
        val label = style(14, 20, FontWeight.Medium, 0.1f)
        /** Body small / label medium. */
        val caption = style(12, 16, FontWeight.Medium, 0.3f)
    }

    object Motion {
        const val focusScale = 1.06f
        const val focusMs = 160
        const val stateMs = 220
        const val ambientMs = 600
    }
}
