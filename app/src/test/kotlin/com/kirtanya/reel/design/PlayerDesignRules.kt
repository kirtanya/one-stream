package com.kirtanya.reel.design

import android.graphics.Color
import android.view.View
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
 * Phone (landscape, 800 × 360 dp): touch areas, clipping, contrast. TV: contrast and focus.
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

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvOnBrightPicture() = tv("player/tv-bright", PlayerScenes.BRIGHT, panel = false)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvOnDarkPicture() = tv("player/tv-dark", PlayerScenes.DARK, panel = false)

    @Test @Config(sdk = [34], qualifiers = TV)
    fun tvSettings() = tv("player/tv-settings", PlayerScenes.MID, panel = true)

    private fun tv(where: String, picture: IntArray, panel: Boolean) {
        val scene = PlayerScenes.tv(picture, panel)
        // The strip refreshes from the (empty) player while time passes: keep it mid-film.
        check(where, ViewChecks(scene.root, where), Rule.CONTRAST, Rule.TV_FOCUS, focusMove = { scene.midFilm() })
        scene.release()
    }

    companion object {
        const val PHONE = "w800dp-h360dp-land-xxhdpi"
        const val TV = "w960dp-h540dp-land-television-xhdpi"
    }
}
