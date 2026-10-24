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
    /** Audio delay in ms (lip sync off in one release); null = 0. */
    val audioDelayMs: Int? = null,
    /** Subtitle delay in ms (subtitles from another release); null = 0. */
    val subtitleDelayMs: Int? = null,
    /**
     * Black bars baked into the picture: how much to crop from the top and the bottom (the same on
     * both), as a percentage of the video's height per side (0–20). Null = no crop.
     */
    val cropPercent: Float? = null,
    val changedAt: Long = 0,
) {
    val isEmpty: Boolean get() = audioLanguage == null && subtitleLanguage == null && subtitleLabel == null && picture == null && speed == null &&
        audioDelayMs == null && subtitleDelayMs == null && cropPercent == null

    companion object {
        const val SUBTITLES_OFF = "off"
        const val MAX_TITLES = 400
    }
}
