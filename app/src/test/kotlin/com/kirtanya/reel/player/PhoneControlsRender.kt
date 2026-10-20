package com.kirtanya.reel.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.ui.PlayerView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone player controls (360 dp tall landscape, like the user's Pixel at its display size). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class PhoneControlsRender {
    @Test fun renderPhone() = render("phone_controls", panel = false)
    @Test fun renderPhoneSettings() = render("phone_controls_settings", panel = true)

    private fun render(name: String, panel: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6E8FB5.toInt(), 0xFF2B3A55.toInt(), 0xFFC98B5E.toInt()))
        val playerView = PlayerView(activity).apply { useController = false; setShutterBackgroundColor(Color.TRANSPARENT); setBackgroundColor(Color.TRANSPARENT) }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = PhoneControls(activity, playerView, "Spider-Man: Into the Spider-Verse", { null }, { listOf("Open in another player" to {}) },
            onBack = {}, onPip = {}, nextLabel = { "Next · S1 E4" }, onNext = {})
        root.addView(c, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        c.show()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        c.preview(2_712_000, 7_020_000, 3_300_000, playing = true)
        if (panel) c.openPanelForPreview()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        c.preview(2_712_000, 7_020_000, 3_300_000, playing = true)
        root.captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
