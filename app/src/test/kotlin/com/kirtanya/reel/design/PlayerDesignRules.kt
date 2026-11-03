package com.kirtanya.reel.design

import android.graphics.Color
import android.view.View
import com.kirtanya.reel.core.player.PhoneLayout
import com.kirtanya.reel.core.player.PhoneLayout.Side
import com.kirtanya.reel.player.PlayerScenes
import com.kirtanya.reel.player.TvControls
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The design rules on the player controls, in the scenes the renders use (PlayerScenes).
 * Phone (landscape 800 × 360 dp and portrait 360 × 800 dp, font 100 % and 130 %): touch areas,
 * clipping, contrast. TV: contrast and focus.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerDesignRules {
    private fun check(where: String, checks: ViewChecks, vararg rules: Rule, focusMove: () -> Unit = {}) {
        val new = ArrayList<String>()
        for (rule in rules) {
            val r = when (rule) {
                Rule.TOUCH -> checks.touch()
                Rule.CLIPPING -> checks.clipping()
                Rule.CONTRAST -> checks.contrast()
                Rule.TV_FOCUS -> checks.tvFocus(focusMove)
                Rule.TOKENS -> error("source rule")
            }
            new += Findings.record(rule, where, r.checked, r.found, r.skipped)
        }
        assertTrue(Findings.failureMessage(where, new), new.isEmpty())
    }

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneControls() {
        val scene = PlayerScenes.phone(panel = false, withPlayer = true)
        check("player/phone-controls", ViewChecks(scene.root, "player/phone-controls"), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSettings() {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true)
        check("player/phone-settings", ViewChecks(scene.root, "player/phone-settings"), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    /** The panel's end: Change source (TMDB title) and the same row off with its note (a file). */
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSettingsSource() = phonePanelEnd("player/phone-settings-source", file = false)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSettingsFile() = phonePanelEnd("player/phone-settings-file", file = true)

    @Test @Config(sdk = [34], qualifiers = PHONE, fontScale = 1.3f)
    fun phoneSettingsFile130() = phonePanelEnd("player/phone-settings-file-130", file = true)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSettingsFile() = phonePanelEnd("player/phone-portrait-settings-file", file = true)

    private fun phonePanelEnd(where: String, file: Boolean) {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, file = file, panelEnd = true)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = PHONE, fontScale = 1.3f)
    fun phoneControls130() = phone("player/phone-controls-130", panel = false)

    /** A finger on the seek bar: thumb, halo and time bubble. */
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneScrubbing() = phone("player/phone-scrub", panel = false, scrub = true)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortrait() = phone("player/phone-portrait", panel = false)

    /** Portrait without a next episode: one row of seven. */
    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitMovie() = phone("player/phone-portrait-movie", panel = false, next = false)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortrait130() = phone("player/phone-portrait-130", panel = false)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSettings() = phone("player/phone-portrait-settings", panel = true)

    // Up-front controls: the audio picker the Audio button opens, a film with one audio track and no next episode, the panel's groups at 130 %.
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneAudioPicker() = phone("player/phone-audio-picker", panel = true, audioPanel = true)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitAudioPicker() = phone("player/phone-portrait-audio-picker", panel = true, audioPanel = true)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSingleAudio() = phone("player/phone-portrait-single-audio", panel = false, next = false, audio = false)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortraitSettings130() = phone("player/phone-portrait-settings-130", panel = true)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSettingsEnd() {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, panelEnd = true)
        check("player/phone-portrait-settings-end", ViewChecks(scene.root, "player/phone-portrait-settings-end"), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    /** Change source inside the player (the panel's page), searching and finished, at 100 % and 130 %. */
    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSources() = phoneSources("player/phone-portrait-sources", PlayerScenes.Sources.LIST)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortraitSources130() = phoneSources("player/phone-portrait-sources-130", PlayerScenes.Sources.SEARCHING)

    private fun phoneSources(where: String, which: PlayerScenes.Sources) {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, sources = which)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    private fun phone(where: String, panel: Boolean, next: Boolean = true, scrub: Boolean = false, audio: Boolean = true, audioPanel: Boolean = false) {
        val scene = PlayerScenes.phone(panel, withPlayer = true, next = next, scrubAtMs = if (scrub) PlayerScenes.SCRUB_MS else null, audio = audio, audioPanel = audioPanel)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    // Edge layout (Settings → Layout): camera hole left / right, a finger on the seek bar, 130 %, the panel.
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneEdgeLeft() = edge("player/phone-edge-left", Side.LEFT)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneEdgeRight() = edge("player/phone-edge-right", Side.RIGHT)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneEdgeScrub() = edge("player/phone-edge-scrub", Side.LEFT, scrub = true)

    @Test @Config(sdk = [34], qualifiers = PHONE, fontScale = 1.3f)
    fun phoneEdge130() = edge("player/phone-edge-130", Side.RIGHT)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneEdgeSettings() = edge("player/phone-edge-settings", Side.LEFT, panel = true)

    private fun edge(where: String, side: Side, scrub: Boolean = false, panel: Boolean = false) {
        val scene = PlayerScenes.phone(panel, withPlayer = true, scrubAtMs = if (scrub) PlayerScenes.SCRUB_MS else null,
            layout = PhoneLayout.EDGE, cutout = side)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    /** The choices one level down (Speed). */
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSpeedChoices() {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true)
        scene.root.findViewWithTag<View>("speed")!!.performClick()
        ShadowLooper.idleMainLooper()
        scene.midFilm()
        check("player/phone-speed", ViewChecks(scene.root, "player/phone-speed"), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    // Roadmap A overlays and the delay sliders.
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSkipChip() = phoneOverlay("player/phone-skip", PlayerScenes.Overlay.SKIP)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSkipOverBand() = phoneOverlay("player/phone-skip-band", PlayerScenes.Overlay.SKIP_OVER_BAND)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitSkipOverBand() = phoneOverlay("player/phone-portrait-skip-band", PlayerScenes.Overlay.SKIP_OVER_BAND)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneStillWatching() = phoneOverlay("player/phone-still-watching", PlayerScenes.Overlay.STILL_WATCHING)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitStillWatching() = phoneOverlay("player/phone-portrait-still-watching", PlayerScenes.Overlay.STILL_WATCHING)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneSleepChip() = phoneOverlay("player/phone-sleep", PlayerScenes.Overlay.SLEEP)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneUpNextCredits() = phoneOverlay("player/phone-up-next-credits", PlayerScenes.Overlay.UP_NEXT_CREDITS)

    // Debrid placeholder: the Not ready card and the chip after switching sources.
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneNotReady() = phoneOverlay("player/phone-not-ready", PlayerScenes.Overlay.NOT_READY)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitNotReady() = phoneOverlay("player/phone-portrait-not-ready", PlayerScenes.Overlay.NOT_READY)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortraitNotReady130() = phoneOverlay("player/phone-portrait-not-ready-130", PlayerScenes.Overlay.NOT_READY)

    @Test @Config(sdk = [34], qualifiers = PHONE, fontScale = 1.3f)
    fun phoneNotReady130() = phoneOverlay("player/phone-not-ready-130", PlayerScenes.Overlay.NOT_READY)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phonePlaceholderSwitch() = phoneOverlay("player/phone-placeholder-switch", PlayerScenes.Overlay.PLACEHOLDER_SWITCH)

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitPlaceholderSwitch() = phoneOverlay("player/phone-portrait-placeholder-switch", PlayerScenes.Overlay.PLACEHOLDER_SWITCH)

    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneAudioDelay() = phoneSlider("player/phone-audio-delay", "Audio delay")

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortraitSubtitleDelay130() = phoneSlider("player/phone-portrait-subtitle-delay-130", "Subtitle delay")

    // Crop bars: the phone's adjust bar (after Auto, so its result line shows) and the TV's page.
    @Test @Config(sdk = [34], qualifiers = PHONE)
    fun phoneCrop() = phoneCrop("player/phone-crop")

    @Test @Config(sdk = [34], qualifiers = PHONE, fontScale = 1.3f)
    fun phoneCrop130() = phoneCrop("player/phone-crop-130")

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phonePortraitCrop() = phoneCrop("player/phone-portrait-crop")

    @Test @Config(sdk = [34], qualifiers = PHONE_PORTRAIT, fontScale = 1.3f)
    fun phonePortraitCrop130() = phoneCrop("player/phone-portrait-crop-130")

    private fun phoneCrop(where: String) {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, cropPercent = 13f, crop = true, cropAuto = true)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvCrop() {
        val scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, cropPercent = 13f, crop = true)
        check("player/tv-crop", ViewChecks(scene.root, "player/tv-crop"), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    private fun phoneOverlay(where: String, overlay: PlayerScenes.Overlay) {
        val scene = PlayerScenes.phone(panel = false, withPlayer = true, overlay = overlay)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    private fun phoneSlider(where: String, slider: String) {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, slider = slider)
        check(where, ViewChecks(scene.root, where), Rule.TOUCH, Rule.CLIPPING, Rule.CONTRAST)
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSkipChip() = tvOverlay("player/tv-skip", PlayerScenes.MID, PlayerScenes.Overlay.SKIP)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvStillWatching() = tvOverlay("player/tv-still-watching", PlayerScenes.MID, PlayerScenes.Overlay.STILL_WATCHING)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvUpNextCredits() = tvOverlay("player/tv-up-next-credits", PlayerScenes.MID, PlayerScenes.Overlay.UP_NEXT_CREDITS)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvNotReady() {
        // The player hides the strip under the card (and the card holds the remote): its own three buttons move focus.
        val scene = PlayerScenes.tv(PlayerScenes.MID, overlay = PlayerScenes.Overlay.NOT_READY, strip = false)
        check("player/tv-not-ready", ViewChecks(scene.root, "player/tv-not-ready"), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvPlaceholderSwitch() = tvOverlay("player/tv-placeholder-switch", PlayerScenes.MID, PlayerScenes.Overlay.PLACEHOLDER_SWITCH)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSleepChip() = tvOverlay("player/tv-sleep", PlayerScenes.DARK, PlayerScenes.Overlay.SLEEP)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvAudioDelay() {
        val scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, slider = "Audio delay")
        check("player/tv-audio-delay", ViewChecks(scene.root, "player/tv-audio-delay"), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    private fun tvOverlay(where: String, picture: IntArray, overlay: PlayerScenes.Overlay) {
        val scene = PlayerScenes.tv(picture, overlay = overlay, strip = true)
        check(where, ViewChecks(scene.root, where), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvOnBrightPicture() = tv("player/tv-bright", PlayerScenes.BRIGHT, panel = false)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvOnDarkPicture() = tv("player/tv-dark", PlayerScenes.DARK, panel = false)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSettings() = tv("player/tv-settings", PlayerScenes.MID, panel = true)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSettingsSource() = tv("player/tv-settings-source", PlayerScenes.MID, panel = true, panelEnd = true)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSettingsFile() = tv("player/tv-settings-file", PlayerScenes.MID, panel = true, panelEnd = true, file = true)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvAudioPicker() = tv("player/tv-audio-picker", PlayerScenes.MID, panel = true, audioPanel = true)

    /** Change source inside the player: the page's rows must show focus (the playing source starts focused). */
    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSources() {
        val scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, sources = PlayerScenes.Sources.LIST)
        check("player/tv-sources", ViewChecks(scene.root, "player/tv-sources"), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    /** A film: no Next chip, one audio track (no Audio button). */
    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvMovieSingleAudio() = tv("player/tv-movie-single-audio", PlayerScenes.MID, panel = false, next = false, audio = false)

    private fun tv(where: String, picture: IntArray, panel: Boolean, panelEnd: Boolean = false, file: Boolean = false, audio: Boolean = true, next: Boolean = true, audioPanel: Boolean = false) {
        val scene = PlayerScenes.tv(picture, panel, file = file, panelEnd = panelEnd, audio = audio, next = next, audioPanel = audioPanel)
        // The strip refreshes from the (empty) player while time passes: keep it mid-film.
        check(where, ViewChecks(scene.root, where), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    companion object {
        const val PHONE = "w800dp-h360dp-land-xxhdpi"
        const val PHONE_PORTRAIT = "w360dp-h800dp-port-xxhdpi"
        const val TV = "w960dp-h540dp-land-television-xhdpi"
    }
}
