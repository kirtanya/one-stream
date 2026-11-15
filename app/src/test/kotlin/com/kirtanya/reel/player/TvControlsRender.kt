package com.kirtanya.reel.player

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.media3.ui.PlayerView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The TV player strip over a bright and a dark picture (renders in build/outputs/roborazzi), and
 * the contrast choice. Record: ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.player.TvControlsRender'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvControlsRender {

    @Test fun renderBright() = render("tv_controls_bright", PlayerScenes.BRIGHT)

    @Test fun renderDark() = render("tv_controls_dark", PlayerScenes.DARK)

    @Test fun renderSettings() = render("tv_controls_settings", PlayerScenes.MID, panel = true)

    /** The panel's end: Change source, and the same row off (with its note) for a file without TMDB info. */
    @Test fun renderSettingsSource() = render("tv_controls_settings_source", PlayerScenes.MID, panel = true, panelEnd = true)
    @Test fun renderSettingsFile() = render("tv_controls_settings_file", PlayerScenes.MID, panel = true, panelEnd = true, file = true)

    // Up-front controls: Change source, Audio (several audio tracks), the Next chip above the title; the audio picker; a film with neither.
    @Test fun renderAudioPicker() = render("tv_controls_audio_picker", PlayerScenes.MID, panel = true, audioPanel = true)
    @Test fun renderMovieSingleAudio() = render("tv_controls_movie_single_audio", PlayerScenes.MID, next = false, audio = false)

    // Roadmap A: Skip intro (focused), Still watching?, sleep chip, Up next at the credits, delay sliders.
    @Test fun renderSkip() = render("tv_skip_intro", PlayerScenes.BRIGHT, overlay = PlayerScenes.Overlay.SKIP)
    @Test fun renderStillWatching() = render("tv_still_watching", PlayerScenes.MID, overlay = PlayerScenes.Overlay.STILL_WATCHING)
    @Test fun renderSleep() = render("tv_sleep_chip", PlayerScenes.DARK, overlay = PlayerScenes.Overlay.SLEEP)
    @Test fun renderUpNextCredits() = render("tv_up_next_credits", PlayerScenes.MID, overlay = PlayerScenes.Overlay.UP_NEXT_CREDITS)
    @Test fun renderNotReady() = render("tv_not_ready", PlayerScenes.MID, overlay = PlayerScenes.Overlay.NOT_READY)
    @Test fun renderPlaceholderSwitch() = render("tv_placeholder_switch", PlayerScenes.MID, overlay = PlayerScenes.Overlay.PLACEHOLDER_SWITCH)
    @Test fun renderAudioDelay() = render("tv_controls_audio_delay", PlayerScenes.MID, panel = true, slider = "Audio delay")
    @Test fun renderSubtitleDelay() = render("tv_controls_subtitle_delay", PlayerScenes.MID, panel = true, slider = "Subtitle delay")
    /** The Subtitles button: tracks first, then style, delay, search again. */
    @Test fun renderSubtitlePage() = render("tv_controls_subtitle_page", PlayerScenes.MID, panel = true, section = "sub")
    /** Back with the controls hidden: "Press Back again to exit". */
    @Test fun renderLogo() = render("tv_controls_logo", PlayerScenes.MID, logo = true)

    /** A coloured logo keeps its colours; a black wordmark is drawn white; the chips for what is changed sit above the logo. */
    @Test fun renderLogoRed() = render("tv_controls_logo_red", PlayerScenes.MID, logo = true, logoColor = 0xFFE50914.toInt())
    @Test fun renderLogoBlack() = render("tv_controls_logo_black", PlayerScenes.DARK, logo = true, logoColor = 0xFF111111.toInt())
    @Test fun renderTweaksLogo() = render("tv_controls_tweaks_logo", PlayerScenes.MID, logo = true, logoColor = 0xFFE50914.toInt(), tweaks = TWEAKS)
    @Test fun renderTweaksTitle() = render("tv_controls_tweaks_title", PlayerScenes.BRIGHT, tweaks = TWEAKS)
    /** Every tweak at once (the real labels): does the row still fit one line over the strip? */
    @Test fun renderTweaksMax() = render("tv_controls_tweaks_max", PlayerScenes.MID, logo = true, logoColor = 0xFFE50914.toInt(), tweaks = MAX_TWEAKS)
    @Test fun renderTweaksFew() = render("tv_controls_tweaks_few", PlayerScenes.MID, tweaks = listOf("Stretch"))

    /** The settings panel's tabs: the Audio and Picture tab shown (the tab focused, as ←/→ does). */
    @Test fun renderSettingsAudioTab() = render("tv_controls_settings_audio_tab", PlayerScenes.MID, panel = true, tab = "Audio")
    @Test fun renderSettingsPictureTab() = render("tv_controls_settings_picture_tab", PlayerScenes.MID, panel = true, tab = "Picture")
    @Test fun renderSettingsSubtitlesTab() = render("tv_controls_settings_subtitles_tab", PlayerScenes.MID, panel = true, tab = "Subtitles")
    @Test fun renderSettingsMoreTab() = render("tv_controls_settings_more_tab", PlayerScenes.MID, panel = true, tab = "More")

    @Test fun renderExitChip() = render("tv_exit_chip", PlayerScenes.BRIGHT, exitChip = true)

    /** Change source inside the player: the button's page (list, still searching, nothing found) and the Settings row's page. */
    @Test fun renderSources() = render("tv_sources", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.LIST)
    @Test fun renderSourcesSearching() = render("tv_sources_searching", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.SEARCHING)
    @Test fun renderSourcesEmpty() = render("tv_sources_empty", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.EMPTY)
    @Test fun renderSourcesViaSettings() = render("tv_sources_via_settings", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.LIST, sourcesViaRow = true)

    private fun render(
        name: String, picture: IntArray, panel: Boolean = false, panelEnd: Boolean = false, file: Boolean = false,
        overlay: PlayerScenes.Overlay? = null, slider: String? = null, audio: Boolean = true, next: Boolean = true, audioPanel: Boolean = false,
        section: String? = null, exitChip: Boolean = false, logo: Boolean = false, logoColor: Int = 0xFFFFFFFF.toInt(),
        tweaks: List<String> = emptyList(), tab: String? = null, sources: PlayerScenes.Sources? = null, sourcesViaRow: Boolean = false,
    ) {
        val scene = PlayerScenes.tv(picture, panel, file = file, panelEnd = panelEnd, overlay = overlay, slider = slider, audio = audio, next = next, audioPanel = audioPanel, section = section, exitChip = exitChip, logo = logo, logoColor = logoColor, tweaks = tweaks, tab = tab, sources = sources, sourcesViaRow = sourcesViaRow)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }

    private companion object {
        /** Every chip PlayerTweaks.labels can show at once (the widest case). */
        val MAX_TWEAKS = com.kirtanya.reel.core.player.PlayerTweaks(
            picture = com.kirtanya.reel.core.playback.PictureMode.STRETCH, cropPercent = 8f, volumeBoost = 2, dialogueBoost = true,
            audioTrack = "Hindi", audioDelayMs = 150, subtitleDelayMs = -250, speed = 1.5f,
        ).labels()
        val TWEAKS = listOf("Stretch", "Crop 8 %", "Volume +6 dB", "Dialogue boost", "Audio: Hindi")
    }
}
