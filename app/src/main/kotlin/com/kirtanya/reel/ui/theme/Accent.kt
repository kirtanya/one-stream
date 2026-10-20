package com.kirtanya.reel.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlinx.coroutines.delay

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
 * containers, text, outlines, primary / secondary / tertiary) with Material's own algorithm:
 *  - a poster's colour while a screen shows a title ([MediaAccent], "Content" style: stays true
 *    to the image), when "Match what's on screen" is on;
 *  - otherwise the user's [preset]: the system accent by default ("Tonal spot", like Android).
 * [Tokens.Colors] reads [scheme], so every screen follows it; [night] is the same colours, always
 * dark, for the player over video.
 */
object AccentState {
    private const val PREFS = "appearance"
    private val FALLBACK = Color(0xFF3B82F6)
    private var prefs: android.content.SharedPreferences? = null

    var preset by mutableStateOf(AccentPreset.System)
        private set
    var adaptive by mutableStateOf(true)
        private set
    var themeMode by mutableStateOf(ThemeMode.System)
        private set
    /** The system's accent (Android 12+), null where there isn't one (older phones, most TVs). */
    var systemSeed: Color? = null
        private set
    var isTv = false
        private set

    /** What [Tokens.Colors] reads; animated by [AccentHost]. */
    var scheme by mutableStateOf(build(FALLBACK, dark = true, media = false))
        internal set
    /** Same seed, always dark: the player's overlays sit on video. */
    var night by mutableStateOf(scheme)
        internal set

    private class Slot(color: Color?) {
        var color by mutableStateOf(color)
    }
    private val media = mutableStateListOf<Slot>()

    fun init(context: Context, tv: Boolean) {
        isTv = tv
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (Build.VERSION.SDK_INT >= 31 && !tv) {
            systemSeed = runCatching { Color(context.getColor(android.R.color.system_accent1_500)) }.getOrNull()
        }
        preset = p.getString("accent", null)?.let { name -> AccentPreset.entries.firstOrNull { it.name == name } } ?: AccentPreset.System
        adaptive = p.getBoolean("adaptive", true)
        themeMode = p.getString("theme", null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
        // Start on the right scheme, so the first frame doesn't flash the fallback.
        val systemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        scheme = build(seedOf(preset), dark = isDark(systemDark), media = false)
        night = build(seedOf(preset), dark = true, media = false)
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

    fun matchScreen(value: Boolean) {
        adaptive = value
        prefs?.edit()?.putBoolean("adaptive", value)?.apply()
    }

    fun chooseTheme(value: ThemeMode) {
        themeMode = value
        prefs?.edit()?.putString("theme", value.name)?.apply()
    }

    /** The colour a preset stands for (System → the phone's accent, or Reel blue without one). */
    fun seedOf(p: AccentPreset): Color = p.seed ?: systemSeed ?: FALLBACK

    /** The poster colour in charge right now, if any (newest screen wins). */
    val mediaSeed: Color?
        get() = if (adaptive) media.lastOrNull { it.color != null }?.color else null

    fun build(seed: Color, dark: Boolean, media: Boolean): ColorScheme =
        dynamicColorScheme(seedColor = seed, isDark = dark, style = if (media) PaletteStyle.Content else PaletteStyle.TonalSpot)

    @Composable
    internal fun register(color: Color?) {
        val slot = remember { Slot(color) }
        DisposableEffect(slot) {
            media.add(slot)
            onDispose { media.remove(slot) }
        }
        if (slot.color != color) SideEffect { slot.color = color }
    }
}

/** While this is composed, Reel takes [color] (a poster's colour) as its seed; null = no opinion. */
@Composable
fun MediaAccent(color: Color?) = AccentState.register(color)

/**
 * Moves the live scheme to its target: waits a moment (so scrolling past posters doesn't flash
 * through every colour), then cross-fades every role.
 */
@Composable
fun AccentHost(dark: Boolean) {
    val mediaSeed = AccentState.mediaSeed
    val seed = mediaSeed ?: AccentState.seedOf(AccentState.preset)
    val target = remember(seed, dark, mediaSeed != null) { AccentState.build(seed, dark, media = mediaSeed != null) }
    val night = remember(seed, mediaSeed != null) { AccentState.build(seed, dark = true, media = mediaSeed != null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        delay(120)
        val from = AccentState.scheme
        progress.snapTo(0f)
        progress.animateTo(1f, tween(Tokens.Motion.ambientMs)) { AccentState.scheme = mix(from, target, value) }
        AccentState.scheme = target
        AccentState.night = night
    }
}

/** Every role of [a] moved [t] of the way to [b]. */
private fun mix(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = b.copy(
    primary = lerp(a.primary, b.primary, t),
    onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    secondary = lerp(a.secondary, b.secondary, t),
    onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t),
    onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t),
    onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
    outline = lerp(a.outline, b.outline, t),
    outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
)
