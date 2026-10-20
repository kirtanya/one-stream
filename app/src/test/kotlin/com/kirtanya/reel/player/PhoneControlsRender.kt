package com.kirtanya.reel.player

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
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
        val scene = PlayerScenes.phone(panel)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
