package com.kirtanya.reel.core.playback

import kotlinx.serialization.Serializable

/**
 * What the player remembers per film / show ([titleKey] "tv:1399", so every episode shares it):
 * the audio and subtitle language picked last, the picture mode and the speed. Null = not chosen,
 * use the app-wide default.
 */
@Serializable
data class TitlePrefs(
    val titleKey: String,
    /** ISO 639 language of the audio track picked ("hi", "en"…). */
    val audioLanguage: String? = null,
    /** Subtitle language picked, or [SUBTITLES_OFF] when the user turned them off for this title. */
    val subtitleLanguage: String? = null,
    /** The subtitle track's label when it matters (two English tracks: "SDH" vs "Forced"). */
    val subtitleLabel: String? = null,
    /** PictureMode.name */
    val picture: String? = null,
    val speed: Float? = null,
    val changedAt: Long = 0,
) {
    val isEmpty: Boolean get() = audioLanguage == null && subtitleLanguage == null && subtitleLabel == null && picture == null && speed == null

    companion object {
        const val SUBTITLES_OFF = "off"
        const val MAX_TITLES = 400
    }
}
