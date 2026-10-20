package com.kirtanya.reel.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubtitleSize(val label: String, val fraction: Float) {
    SMALL("Small", 0.045f), MEDIUM("Medium", 0.0533f), LARGE("Large", 0.065f)
}

data class PlaybackPrefs(
    val autoplayNext: Boolean = true,
    /** ISO 639-1 code to show subtitles in, or null for off. */
    val subtitleLanguage: String? = null,
    val subtitleSize: SubtitleSize = SubtitleSize.MEDIUM,
    /** Cinema mode: how strongly the bars around a video glow with its colours (player/AmbientGlow). */
    val cinemaGlow: com.kirtanya.reel.core.playback.Ambient.Level = com.kirtanya.reel.core.playback.Ambient.Level.SUBTLE,
)

/** Player preferences (SharedPreferences: small, read once per playback). */
class PlaybackSettings(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PlaybackPrefs> = _state.asStateFlow()

    fun update(change: (PlaybackPrefs) -> PlaybackPrefs) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit {
            putBoolean("autoplayNext", next.autoplayNext)
            putString("subtitleLanguage", next.subtitleLanguage)
            putString("subtitleSize", next.subtitleSize.name)
            putString("cinemaGlow", next.cinemaGlow.name)
        }
    }

    private fun read() = PlaybackPrefs(
        autoplayNext = prefs.getBoolean("autoplayNext", true),
        subtitleLanguage = prefs.getString("subtitleLanguage", null),
        subtitleSize = runCatching { SubtitleSize.valueOf(prefs.getString("subtitleSize", null)!!) }.getOrDefault(SubtitleSize.MEDIUM),
        cinemaGlow = com.kirtanya.reel.core.playback.Ambient.Level.of(prefs.getString("cinemaGlow", null)),
    )

    companion object {
        /** Languages offered in Settings (others still work if an addon returns them). */
        val LANGUAGES = listOf("en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam",
            "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "es" to "Spanish", "fr" to "French", "de" to "German",
            "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic")
    }
}
