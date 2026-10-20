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

    private fun render(name: String, picture: IntArray, panel: Boolean = false) {
        val scene = PlayerScenes.tv(picture, panel)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }
}
