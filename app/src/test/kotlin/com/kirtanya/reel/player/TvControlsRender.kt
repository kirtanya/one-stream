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

    @Test fun renderExitChip() = render("tv_exit_chip", PlayerScenes.BRIGHT, exitChip = true)

    /** Change source inside the player: the button's page (list, still searching, nothing found) and the Settings row's page. */
    @Test fun renderSources() = render("tv_sources", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.LIST)
    @Test fun renderSourcesSearching() = render("tv_sources_searching", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.SEARCHING)
    @Test fun renderSourcesEmpty() = render("tv_sources_empty", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.EMPTY)
    @Test fun renderSourcesViaSettings() = render("tv_sources_via_settings", PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.LIST, sourcesViaRow = true)

    private fun render(
        name: String, picture: IntArray, panel: Boolean = false, panelEnd: Boolean = false, file: Boolean = false,
        overlay: PlayerScenes.Overlay? = null, slider: String? = null, audio: Boolean = true, next: Boolean = true, audioPanel: Boolean = false,
        section: String? = null, exitChip: Boolean = false, logo: Boolean = false, sources: PlayerScenes.Sources? = null, sourcesViaRow: Boolean = false,
    ) {
        val scene = PlayerScenes.tv(picture, panel, file = file, panelEnd = panelEnd, overlay = overlay, slider = slider, audio = audio, next = next, audioPanel = audioPanel, section = section, exitChip = exitChip, logo = logo, sources = sources, sourcesViaRow = sourcesViaRow)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }
}
