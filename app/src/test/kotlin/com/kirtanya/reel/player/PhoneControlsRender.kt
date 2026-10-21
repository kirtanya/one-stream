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
    /** The panel's end: Change source, and the same row off (with its note) for a file without TMDB info. */
    @Test fun renderPhoneSettingsSource() = render("phone_controls_settings_source", panel = true, panelEnd = true)
    @Test fun renderPhoneSettingsFile() = render("phone_controls_settings_file", panel = true, panelEnd = true, file = true)
    @Test @Config(qualifiers = PORT) fun renderPortraitSettingsFile() = render("phone_controls_portrait_settings_file", panel = true, panelEnd = true, file = true)
    @Test fun renderPhoneScrub() = render("phone_controls_scrub", panel = false, scrub = true)
    @Test fun renderPhoneMovie() = render("phone_controls_movie", panel = false, next = false)
    @Test @Config(fontScale = 1.3f) fun renderPhone130() = render("phone_controls_130", panel = false)

    @Test @Config(qualifiers = PORT) fun renderPortrait() = render("phone_controls_portrait", panel = false)
    @Test @Config(qualifiers = PORT) fun renderPortraitMovie() = render("phone_controls_portrait_movie", panel = false, next = false)
    @Test @Config(qualifiers = PORT) fun renderPortraitSettings() = render("phone_controls_portrait_settings", panel = true)
    @Test @Config(qualifiers = PORT, fontScale = 1.3f) fun renderPortrait130() = render("phone_controls_portrait_130", panel = false)

    // Roadmap A: Skip intro (controls hidden / above the band), Still watching?, sleep chip, Up next at the credits, delay sliders.
    @Test fun renderPhoneSkip() = render("phone_skip_intro", panel = false, overlay = PlayerScenes.Overlay.SKIP)
    @Test fun renderPhoneSkipOverBand() = render("phone_skip_intro_band", panel = false, overlay = PlayerScenes.Overlay.SKIP_OVER_BAND)
    @Test @Config(qualifiers = PORT) fun renderPortraitSkipOverBand() = render("phone_portrait_skip_intro_band", panel = false, overlay = PlayerScenes.Overlay.SKIP_OVER_BAND)
    @Test fun renderPhoneStillWatching() = render("phone_still_watching", panel = false, overlay = PlayerScenes.Overlay.STILL_WATCHING)
    @Test fun renderPhoneSleep() = render("phone_sleep_chip", panel = false, overlay = PlayerScenes.Overlay.SLEEP)
    @Test fun renderPhoneUpNextCredits() = render("phone_up_next_credits", panel = false, overlay = PlayerScenes.Overlay.UP_NEXT_CREDITS)
    // Debrid placeholder: the Not ready card (landscape, portrait, 130 %) and the chip after switching sources.
    @Test fun renderPhoneNotReady() = render("phone_not_ready", panel = false, overlay = PlayerScenes.Overlay.NOT_READY)
    @Test @Config(qualifiers = PORT) fun renderPortraitNotReady() = render("phone_portrait_not_ready", panel = false, overlay = PlayerScenes.Overlay.NOT_READY)
    @Test @Config(qualifiers = PORT, fontScale = 1.3f) fun renderPortraitNotReady130() = render("phone_portrait_not_ready_130", panel = false, overlay = PlayerScenes.Overlay.NOT_READY)
    @Test fun renderPhonePlaceholderSwitch() = render("phone_placeholder_switch", panel = false, overlay = PlayerScenes.Overlay.PLACEHOLDER_SWITCH)
    @Test @Config(qualifiers = PORT) fun renderPortraitPlaceholderSwitch() = render("phone_portrait_placeholder_switch", panel = false, overlay = PlayerScenes.Overlay.PLACEHOLDER_SWITCH)
    @Test fun renderPhoneAudioDelay() = render("phone_controls_audio_delay", panel = true, slider = "Audio delay")
    @Test @Config(qualifiers = PORT) fun renderPortraitSubtitleDelay() = render("phone_controls_portrait_subtitle_delay", panel = true, slider = "Subtitle delay")

    private fun render(
        name: String, panel: Boolean, next: Boolean = true, scrub: Boolean = false, panelEnd: Boolean = false, file: Boolean = false,
        overlay: PlayerScenes.Overlay? = null, slider: String? = null,
    ) {
        val scene = PlayerScenes.phone(panel, withPlayer = panel, next = next, scrubAtMs = if (scrub) PlayerScenes.SCRUB_MS else null, file = file, panelEnd = panelEnd, overlay = overlay, slider = slider)
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }

    companion object {
        const val LAND = "w800dp-h360dp-land-xxhdpi"
        const val PORT = "w360dp-h800dp-port-xxhdpi"
    }
}
