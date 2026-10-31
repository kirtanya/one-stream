package com.kirtanya.reel.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
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
 * Where Reel's colour comes from when no title is on screen (Settings → Appearance).
 * [System] is the phone's own accent (Material You, from the wallpaper), when it has one.
 */
enum class AccentPreset(val label: String, val seed: Color?) {
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

    var preset by mutableStateOf(AccentPreset.System)
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
        preset = p.getString("accent", null)?.let { name -> AccentPreset.entries.firstOrNull { it.name == name } } ?: AccentPreset.System
        themeMode = p.getString("theme", null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
        // Start on the right scheme, so the first frame doesn't flash the fallback.
        val systemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        scheme = build(seedOf(preset), dark = isDark(systemDark))
        night = build(seedOf(preset), dark = true)
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

    fun build(seed: Color, dark: Boolean): ColorScheme =
        dynamicColorScheme(seedColor = seed, isDark = dark, style = PaletteStyle.TonalSpot)
}

/** Moves the live scheme to the current preset + light / dark (only when one of them changes). */
@Composable
fun AccentHost(dark: Boolean) {
    val seed = AccentState.seedOf(AccentState.preset)
    LaunchedEffect(seed, dark) {
        AccentState.scheme = AccentState.build(seed, dark)
        AccentState.night = AccentState.build(seed, dark = true)
    }
}
