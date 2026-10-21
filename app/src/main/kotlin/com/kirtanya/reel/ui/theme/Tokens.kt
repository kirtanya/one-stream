package com.kirtanya.reel.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

        // Canvas and surface steps (depth from tonal steps + hairlines; the floating tab bar is the
        // one thing with a shadow, since it hovers over scrolling content).
        val canvas: Color get() = c.surface
        val surface1: Color get() = c.surfaceContainer
        val surface2: Color get() = c.surfaceContainerHigh
        val surface3: Color get() = c.surfaceContainerHighest
        /** Controls sitting on images. */
        val glass: Color get() = c.surfaceContainerHigh.copy(alpha = 0.72f)
        /**
         * The floating tab bar: [glass] made more opaque, because it always sits over moving rows and
         * art (no backdrop blur here, so 72 % would let row titles read through it).
         */
        val barGlass: Color get() = c.surfaceContainerHigh.copy(alpha = 0.9f)
        /** Behind the selected tab's icon: a soft wash of the accent. */
        val barIndicator: Color get() = c.primary.copy(alpha = 0.16f)
        /** The floating bar's soft shadow (Material scrim, i.e. black, faded). */
        val barShadow: Color get() = c.scrim.copy(alpha = 0.32f)
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
        val warn: Color get() = if (isDark) Color(0xFFFBBF24) else Color(0xFF9A4507) // light: 4.5:1 on every light surface
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

        // Scrims over full-bleed images (the cinematic hero): text sits on the canvas colour, so it
        // reads in light and dark alike.
        /** Bottom of a hero: clear at the top, solid canvas where the text sits. */
        val heroBottom: Brush get() = Brush.verticalGradient(
            0.30f to Color.Transparent, 0.62f to Colors.canvas.copy(alpha = 0.55f), 0.86f to Colors.canvas.copy(alpha = 0.92f), 1f to Colors.canvas,
        )
        /** Top of a hero, behind the status bar and floating buttons. */
        val heroTop: Brush get() = Brush.verticalGradient(0f to Colors.canvas.copy(alpha = 0.72f), 1f to Color.Transparent)
        /** Behind a page heading (grids, person): the picture as a dim wash, so any text reads on it. */
        val backdropDim: Brush get() = Brush.verticalGradient(
            0f to Colors.canvas.copy(alpha = 0.5f), 0.5f to Colors.canvas.copy(alpha = 0.78f), 1f to Colors.canvas,
        )
        /** TV: text sits on the left of the picture. */
        val heroStart: Brush get() = Brush.horizontalGradient(0f to Colors.canvas, 0.38f to Colors.canvas.copy(alpha = 0.82f), 0.72f to Color.Transparent)
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
        /** Gap between the floating tab bar and the gesture / navigation bar under it. */
        val barGap = 12.dp
        /**
         * Room the floating tab bar takes at the bottom (system bar + gap + bar), so the last row
         * can scroll clear of it. Screens add their own breathing space on top.
         */
        val bottomBarClearance: Dp
            @Composable get() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + barGap + Size.barHeight
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
        /** Floating tab bar (phone): one tab slot (a 48 dp touch height) and the bar's inner padding. */
        val barTabWidth = 56.dp
        val barTabHeight = 48.dp
        val barPadding = 6.dp
        val barHeight = barTabHeight + barPadding * 2
        val barIcon = 24.dp
        val barElevation = 10.dp
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
        /** Posters and stills on TV: a bigger lift, since they're the main thing being picked. */
        const val posterFocusScale = 1.1f
        const val focusMs = 160
        const val stateMs = 220
        const val ambientMs = 600
        /** Screen to screen: a cross-fade with a slight zoom. */
        const val pageMs = 320
        const val pageZoom = 0.96f
        /** Hero images: how long each backdrop stays, the cross-fade, and the slow zoom (phone only). */
        const val slideMs = 8_000
        const val slideFadeMs = 900
        const val kenBurnsScale = 1.08f
    }

    /** The cinematic layer: heroes, title logos, the meta line. */
    object Cinema {
        /** Phone hero (Home) and title header, as a share of the screen height, with limits. */
        const val heroShare = 0.66f
        val heroMin = 460.dp
        val heroMax = 620.dp
        val titleHeader = 440.dp
        val sourcesHeader = 230.dp
        /** Title logos (TMDB artwork) are fitted inside this box. */
        val logoMaxWidthPhone = 260.dp
        val logoMaxHeightPhone = 96.dp
        val logoMaxWidthTv = 380.dp
        val logoMaxHeightTv = 110.dp
        /** TV Home: the info block above the rows. */
        val tvInfoHeight = 196.dp
        /** Netflix-style action tiles on the phone title page (icon over a short label). */
        val actionTileHeight = 64.dp
        val thumbWidthPhone = 220.dp
        val thumbWidthTv = 260.dp
    }
}
