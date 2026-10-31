package com.kirtanya.reel.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * Where Reel's colour comes from (Settings → Appearance). [Mono] is the default and the TV's only
 * look: pure black (or white) with white (or black) as the accent, so pictures carry the colour.
 * [System] is the phone's own accent (Material You, from the wallpaper), when it has one.
 */
enum class AccentPreset(val label: String, val seed: Color?) {
    Mono("Black & white", null),
    System("System", null),
    Blue("Blue", Color(0xFF3B82F6)),
    Teal("Teal", Color(0xFF14B8A6)),
    Green("Green", Color(0xFF22C55E)),
    Violet("Violet", Color(0xFF8B5CF6)),
    Rose("Rose", Color(0xFFF43F5E)),
    Amber("Amber", Color(0xFFF59E0B)),
}

/** Light or dark: follows the system unless the user picks one (TVs are always dark). */
enum class ThemeMode(val label: String) { System("System"), Dark("Dark"), Light("Light") }

/**
 * Reel's live Material 3 colour scheme. One seed colour becomes the whole scheme (surfaces,
 * containers, text, outlines, primary / secondary / tertiary) with Material's own algorithm,
 * "Tonal spot" like Android: the user's [preset], the system accent by default.
 * [Tokens.Colors] reads [scheme], so every screen follows it; [night] is the same colours, always
 * dark, for the player over video.
 *
 * It no longer follows the poster in focus: that rebuilt two schemes and recomposed the whole
 * screen on every focus move (docs/DECISIONS.md, 2026-10-31).
 */
object AccentState {
    private const val PREFS = "appearance"
    private val FALLBACK = Color(0xFF3B82F6)
    private var prefs: android.content.SharedPreferences? = null

    var preset by mutableStateOf(AccentPreset.Mono)
        private set
    var themeMode by mutableStateOf(ThemeMode.System)
        private set
    /** The system's accent (Android 12+), null where there isn't one (older phones, most TVs). */
    var systemSeed: Color? = null
        private set
    var isTv = false
        private set

    /** What [Tokens.Colors] reads; set by [AccentHost] when the preset or light / dark changes. */
    var scheme by mutableStateOf(build(FALLBACK, dark = true))
        internal set
    /** Same seed, always dark: the player's overlays sit on video. */
    var night by mutableStateOf(scheme)
        internal set

    fun init(context: Context, tv: Boolean) {
        isTv = tv
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (Build.VERSION.SDK_INT >= 31 && !tv) {
            systemSeed = runCatching { Color(context.getColor(android.R.color.system_accent1_500)) }.getOrNull()
        }
        preset = p.getString("accent", null)?.let { name -> AccentPreset.entries.firstOrNull { it.name == name } } ?: AccentPreset.Mono
        themeMode = p.getString("theme", null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
        // Start on the right scheme, so the first frame doesn't flash the fallback.
        val systemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        scheme = schemeFor(preset, dark = isDark(systemDark))
        night = schemeFor(preset, dark = true)
    }

    /** Dark or light for this device right now, given the system's setting. */
    fun isDark(systemDark: Boolean): Boolean = isTv || when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    fun choose(value: AccentPreset) {
        preset = value
        prefs?.edit()?.putString("accent", value.name)?.apply()
    }

    fun chooseTheme(value: ThemeMode) {
        themeMode = value
        prefs?.edit()?.putString("theme", value.name)?.apply()
    }

    /** The colour a preset stands for (System → the phone's accent, or Reel blue without one). */
    fun seedOf(p: AccentPreset): Color = p.seed ?: systemSeed ?: FALLBACK

    /** The look in use: the TV is always [AccentPreset.Mono]. */
    val effective: AccentPreset get() = if (isTv) AccentPreset.Mono else preset
    val isMono: Boolean get() = effective == AccentPreset.Mono

    fun schemeFor(p: AccentPreset, dark: Boolean): ColorScheme =
        if (if (isTv) true else p == AccentPreset.Mono) mono(dark) else build(seedOf(p), dark)

    fun build(seed: Color, dark: Boolean): ColorScheme =
        dynamicColorScheme(seedColor = seed, isDark = dark, style = PaletteStyle.TonalSpot)

    /**
     * Black and white, like the big streaming apps on a TV: a pure black canvas, neutral (untinted)
     * greys for the few raised surfaces, white text and white as the accent (black on white for
     * the main button). Light mode is the same, inverted.
     */
    fun mono(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFD9D9D9), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF2A2A2A), onTertiaryContainer = Color(0xFFFFFFFF),
        background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000), onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFFB3B3B3),
        surfaceTint = Color(0xFFFFFFFF),
        inverseSurface = Color(0xFFFFFFFF), inverseOnSurface = Color(0xFF000000), inversePrimary = Color(0xFF000000),
        outline = Color(0xFF8C8C8C), outlineVariant = Color(0xFF333333), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2E2E2E), surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF262626),
    ) else lightColorScheme(
        primary = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF333333), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEBEBEB), onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF4D4D4D), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE6E6E6), onTertiaryContainer = Color(0xFF000000),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF4D4D4D),
        surfaceTint = Color(0xFF000000),
        inverseSurface = Color(0xFF000000), inverseOnSurface = Color(0xFFFFFFFF), inversePrimary = Color(0xFFFFFFFF),
        outline = Color(0xFF6E6E6E), outlineVariant = Color(0xFFD6D6D6), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDEDEDE),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFEBEBEB), surfaceContainerHighest = Color(0xFFE3E3E3),
    )
}

/** Moves the live scheme to the current preset + light / dark (only when one of them changes). */
@Composable
fun AccentHost(dark: Boolean) {
    val preset = AccentState.preset
    val seed = AccentState.seedOf(preset)
    LaunchedEffect(preset, seed, dark) {
        AccentState.scheme = AccentState.schemeFor(preset, dark)
        AccentState.night = AccentState.schemeFor(preset, dark = true)
    }
}
