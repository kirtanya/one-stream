package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.playback.PictureMode
import kotlin.math.abs

/**
 * What is changed from how the file plays by default: shown with the player controls (small chips on the TV,
 * after "Ends at" on the phone), so a resumed title's earlier changes (a stretched picture, boosted dialogue)
 * are never a mystery. Nothing listed = plays as the file is.
 */
data class PlayerTweaks(
    val picture: PictureMode = PictureMode.FIT,
    val cropPercent: Float = 0f,
    /** [VolumeBoost] ordinal. */
    val volumeBoost: Int = 0,
    val dialogueBoost: Boolean = false,
    /** The audio track's name when it isn't the file's default one; null = the default. */
    val audioTrack: String? = null,
    val audioDelayMs: Int = 0,
    val subtitleDelayMs: Int = 0,
    val speed: Float = 1f,
) {
    /** Picture first, then sound, then timing: "Stretch", "Crop 8 %", "Volume +6 dB", "Dialogue boost", "Audio: Hindi", … */
    fun labels(): List<String> = buildList {
        when (picture) {
            PictureMode.FIT -> Unit
            PictureMode.FILL -> add("Fill")
            else -> add(picture.label)
        }
        if (CropBars.clamp(cropPercent) > 0f) add("Crop " + CropBars.label(cropPercent))
        VolumeBoost.of(volumeBoost).takeIf { it != VolumeBoost.Off }?.let { add("Volume +${it.db.toInt()} dB") }
        if (dialogueBoost) add("Dialogue boost")
        audioTrack?.takeIf { it.isNotBlank() }?.let { add("Audio: $it") }
        if (audioDelayMs != 0) add("Audio delay " + Delay.AUDIO.label(audioDelayMs))
        if (subtitleDelayMs != 0) add("Subtitle delay " + Delay.SUBTITLES.label(subtitleDelayMs))
        if (abs(speed - 1f) > 0.01f) add("Speed " + Speeds.label(speed))
    }
}
