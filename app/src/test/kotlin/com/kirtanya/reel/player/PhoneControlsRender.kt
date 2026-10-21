package com.kirtanya.reel.player

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone player controls on the user's Pixel at its display size: landscape 800 × 360 dp and
 * portrait 360 × 800 dp, font 100 % and 130 %. Renders land in build/outputs/roborazzi.
 * Record: ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.player.PhoneControlsRender'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PhoneControlsRender.LAND)
class PhoneControlsRender {
    @Test fun renderPhone() = render("phone_controls", panel = false)
    @Test fun renderPhoneSettings() = render("phone_controls_settings", panel = true)
    @Test fun renderPhoneScrub() = render("phone_controls_scrub", panel = false, scrub = true)
    @Test fun renderPhoneMovie() = render("phone_controls_movie", panel = false, next = false)
    @Test @Config(fontScale = 1.3f) fun renderPhone130() = render("phone_controls_130", panel = false)

    @Test @Config(qualifiers = PORT) fun renderPortrait() = render("phone_controls_portrait", panel = false)
    @Test @Config(qualifiers = PORT) fun renderPortraitMovie() = render("phone_controls_portrait_movie", panel = false, next = false)
    @Test @Config(qualifiers = PORT) fun renderPortraitSettings() = render("phone_controls_portrait_settings", panel = true)
    @Test @Config(qualifiers = PORT, fontScale = 1.3f) fun renderPortrait130() = render("phone_controls_portrait_130", panel = false)

    private fun render(name: String, panel: Boolean, next: Boolean = true, scrub: Boolean = false) {
        val scene = PlayerScenes.phone(panel, withPlayer = panel, next = next, scrubAtMs = if (scrub) PlayerScenes.SCRUB_MS else null)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }

    companion object {
        const val LAND = "w800dp-h360dp-land-xxhdpi"
        const val PORT = "w360dp-h800dp-port-xxhdpi"
    }
}
