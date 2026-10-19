package com.kirtanya.reel.ui.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** The accent colours the user can pick (Settings → Appearance). Blue is Reel's own. */
enum class AccentPreset(val label: String, val seed: Color) {
    Blue("Blue", Color(0xFF3B82F6)),
    Cyan("Cyan", Color(0xFF06B6D4)),
    Teal("Teal", Color(0xFF14B8A6)),
    Violet("Violet", Color(0xFF8B5CF6)),
    Rose("Rose", Color(0xFFF43F5E)),
    Amber("Amber", Color(0xFFF59E0B)),
}

/** Every accent tone a screen uses, all derived from one seed colour. */
data class AccentTones(
    /** Fills under white text: buttons, selected chips, switches. */
    val base: Color,
    /** Accent as light on the night canvas: text, icons, focus rings. */
    val bright: Color,
    /** The dark end of gradients and progress. */
    val deep: Color,
    val ringStart: Color,
    val ringEnd: Color,
) {
    companion object {
        /**
         * Keeps the seed's hue and fits the rest to Afterglow: [base] dark enough for white text
         * (about 3:1 or better), [bright] light enough to read on the canvas.
         */
        fun from(seed: Color): AccentTones {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
            val h = hsv[0]
            val s = hsv[1]
            var v = 0.95f
            var base = hsv(h, s.coerceIn(0.55f, 0.85f), v)
            while (base.luminance() > 0.26f && v > 0.45f) {
                v -= 0.03f
                base = hsv(h, s.coerceIn(0.55f, 0.85f), v)
            }
            return AccentTones(
                base = base,
                bright = hsv(h, (s * 0.55f).coerceIn(0.3f, 0.55f), 1f),
                deep = hsv(h, (s + 0.1f).coerceIn(0.6f, 0.95f), v * 0.62f),
                ringStart = hsv(h, 0.28f, 1f),
                ringEnd = hsv(h, 0.85f, (v * 0.85f).coerceAtLeast(0.5f)),
            )
        }

        private fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    }
}

/**
 * The live accent. [Tokens.Colors] reads [tones], so every screen (and the player's views) follow
 * it. It is the user's [preset], or, when [adaptive], the colour of the title on screen: each
 * screen showing a poster registers its colour with [MediaAccent]; the newest one wins.
 */
object AccentState {
    private const val PREFS = "appearance"
    private var prefs: android.content.SharedPreferences? = null

    var preset by mutableStateOf(AccentPreset.Blue)
        private set
    var adaptive by mutableStateOf(true)
        private set

    /** What [Tokens.Colors] reads; written by [AccentHost] as it animates. */
    var tones by mutableStateOf(AccentTones.from(AccentPreset.Blue.seed))
        internal set

    private class Slot(color: Color?) {
        var color by mutableStateOf(color)
    }
    private val media = mutableStateListOf<Slot>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        preset = p.getString("accent", null)?.let { name -> AccentPreset.entries.firstOrNull { it.name == name } } ?: AccentPreset.Blue
        adaptive = p.getBoolean("adaptive", true)
        tones = AccentTones.from(target)
    }

    fun choose(value: AccentPreset) {
        preset = value
        prefs?.edit()?.putString("accent", value.name)?.apply()
    }

    fun matchScreen(value: Boolean) {
        adaptive = value
        prefs?.edit()?.putBoolean("adaptive", value)?.apply()
    }

    /** The seed colour the accent is heading for right now. */
    val target: Color
        get() = (if (adaptive) media.lastOrNull { it.color != null }?.color else null) ?: preset.seed

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

/** While this is composed, the app's accent follows [color] (a poster's colour); null = no opinion. */
@Composable
fun MediaAccent(color: Color?) = AccentState.register(color)

/** Animates the live accent towards its target. Put once at the root of each activity's content. */
@Composable
fun AccentHost() {
    val seed by animateColorAsState(AccentState.target, tween(Tokens.Motion.ambientMs), label = "accent")
    SideEffect { AccentState.tones = AccentTones.from(seed) }
}
