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

    // Roadmap A: Skip intro (focused), Still watching?, sleep chip, Up next at the credits, delay sliders.
    @Test fun renderSkip() = render("tv_skip_intro", PlayerScenes.BRIGHT, overlay = PlayerScenes.Overlay.SKIP)
    @Test fun renderStillWatching() = render("tv_still_watching", PlayerScenes.MID, overlay = PlayerScenes.Overlay.STILL_WATCHING)
    @Test fun renderSleep() = render("tv_sleep_chip", PlayerScenes.DARK, overlay = PlayerScenes.Overlay.SLEEP)
    @Test fun renderUpNextCredits() = render("tv_up_next_credits", PlayerScenes.MID, overlay = PlayerScenes.Overlay.UP_NEXT_CREDITS)
    @Test fun renderAudioDelay() = render("tv_controls_audio_delay", PlayerScenes.MID, panel = true, slider = "Audio delay")
    @Test fun renderSubtitleDelay() = render("tv_controls_subtitle_delay", PlayerScenes.MID, panel = true, slider = "Subtitle delay")

    private fun render(
        name: String, picture: IntArray, panel: Boolean = false, panelEnd: Boolean = false, file: Boolean = false,
        overlay: PlayerScenes.Overlay? = null, slider: String? = null,
    ) {
        val scene = PlayerScenes.tv(picture, panel, file = file, panelEnd = panelEnd, overlay = overlay, slider = slider)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }
}
