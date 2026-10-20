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

    @Test fun brightPictureGetsDarkControls() {
        val c = controls()
        c.decide(FloatArray(200) { if (it < 20) 0.35f else 0.9f })
        assertEquals(TvControls.DARK, c.target)
    }

    @Test fun darkPictureKeepsWhiteControls() {
        val c = controls()
        c.decide(FloatArray(200) { if (it < 20) 0.5f else 0.02f })
        assertEquals(Color.WHITE, c.target)
    }

    @Test fun midGreyDoesNotFlap() {
        val c = controls()
        // ~0.18 luminance: white and black contrast about equally; stay on the current colour.
        c.decide(FloatArray(200) { 0.18f })
        assertEquals(Color.WHITE, c.target)
    }

    @Test fun renderBright() = render("tv_controls_bright", PlayerScenes.BRIGHT, TvControls.DARK)

    @Test fun renderDark() = render("tv_controls_dark", PlayerScenes.DARK, Color.WHITE)

    @Test fun renderSettings() = render("tv_controls_settings", PlayerScenes.MID, Color.WHITE, panel = true)

    private fun controls(): TvControls {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return TvControls(PlayerView(activity), PlayerScenes.TITLE, { null }) { emptyList() }
    }

    private fun render(name: String, picture: IntArray, color: Int, panel: Boolean = false) {
        val scene = PlayerScenes.tv(picture, color, panel)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }
}
