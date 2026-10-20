package com.kirtanya.reel.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer
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

    @Test fun renderBright() = render("tv_controls_bright", intArrayOf(0xFFBFD9F2.toInt(), 0xFFF4EBD9.toInt()), TvControls.DARK)

    @Test fun renderDark() = render("tv_controls_dark", intArrayOf(0xFF1B2433.toInt(), 0xFF0B0B10.toInt()), Color.WHITE)

    @Test fun renderSettings() = render("tv_controls_settings", intArrayOf(0xFF3C5A7A.toInt(), 0xFF11161F.toInt()), Color.WHITE, panel = true)

    private fun controls(): TvControls {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return TvControls(PlayerView(activity), "Spider-Man: Into the Spider-Verse", { null }) { emptyList() }
    }

    private fun render(name: String, picture: IntArray, color: Int, panel: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, picture)
        val playerView = PlayerView(activity).apply { useController = false; setShutterBackgroundColor(Color.TRANSPARENT); setBackgroundColor(Color.TRANSPARENT) }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = TvControls(playerView, "Spider-Man: Into the Spider-Verse", { null }) { listOf("Open in another player" to {}) }
        root.addView(c.view, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val exo = ExoPlayer.Builder(activity).build()
        c.attach(exo)
        c.show()
        if (panel) c.openPanel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // No video surface here: set the colour the sampler would pick for this picture.
        c.applyColor(color)
        root.captureRoboImage("build/outputs/roborazzi/$name.png")
        exo.release()
    }
}
