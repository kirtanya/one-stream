package com.kirtanya.reel.data

import android.content.Context
import androidx.core.content.edit
import com.kirtanya.reel.core.playback.CaptionStyle
import com.kirtanya.reel.core.sources.SubtitleLanguagePref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackPrefs(
    val autoplayNext: Boolean = true,
    /**
     * ISO 639-1 code to show subtitles in, or null for Off. English unless the user chose otherwise
     * (stored through SubtitleLanguagePref, where Off is its own value).
     */
    val subtitleLanguage: String? = SubtitleLanguagePref.DEFAULT,
    /** Size, colours, outline, font and position of subtitles (Settings → Subtitles). */
    val caption: CaptionStyle = CaptionStyle(),
    /** Cinema mode: how strongly the bars around a video glow with its colours (player/AmbientGlow). */
    val cinemaGlow: com.kirtanya.reel.core.playback.Ambient.Level = com.kirtanya.reel.core.playback.Ambient.Level.SUBTLE,
    /** How the picture fills the screen (Fit, Fill, Stretch, forced shapes); remembered between videos. */
    val picture: com.kirtanya.reel.core.playback.PictureMode = com.kirtanya.reel.core.playback.PictureMode.FIT,
    /** Phone player layout (Classic bottom band, or Edge around the camera hole); Classic until the user picks. */
    val phoneLayout: com.kirtanya.reel.core.player.PhoneLayout = com.kirtanya.reel.core.player.PhoneLayout.CLASSIC,
    /** TV player: the title's logo artwork (true) or its name as plain text (false) in the control strip. */
    val titleLogo: Boolean = true,
) {
    /** Older callers (the player before caption styles) only knew the size. */
    val subtitleSize: CaptionStyle.Size get() = caption.size
}

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
            putString("subtitleLanguage", SubtitleLanguagePref.encode(next.subtitleLanguage))
            putString("caption", next.caption.encode())
            putString("cinemaGlow", next.cinemaGlow.name)
            putString("picture", next.picture.name)
            putString("phoneLayout", next.phoneLayout.name)
            putBoolean("titleLogo", next.titleLogo)
        }
    }

    private fun read() = PlaybackPrefs(
        autoplayNext = prefs.getBoolean("autoplayNext", true),
        subtitleLanguage = SubtitleLanguagePref.decode(prefs.getString("subtitleLanguage", null)),
        caption = CaptionStyle.decode(prefs.getString("caption", null), legacySize = prefs.getString("subtitleSize", null)),
        cinemaGlow = com.kirtanya.reel.core.playback.Ambient.Level.of(prefs.getString("cinemaGlow", null)),
        picture = com.kirtanya.reel.core.playback.PictureMode.of(prefs.getString("picture", null)),
        phoneLayout = com.kirtanya.reel.core.player.PhoneLayout.of(prefs.getString("phoneLayout", null)),
        titleLogo = prefs.getBoolean("titleLogo", true),
    )

    companion object {
        /** Languages offered in Settings (others still work if an addon returns them). */
        val LANGUAGES = listOf("en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam",
            "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "es" to "Spanish", "fr" to "French", "de" to "German",
            "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic")
    }
}
