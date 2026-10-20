package com.kirtanya.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.phone.PhoneApp
import com.kirtanya.reel.ui.theme.ReelTheme
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.tv.TvApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real screens on the Mac at the user's device sizes, to check layouts without a device:
 *   ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'  (PNGs land in app/build/screens)
 * TMDB data and posters load over the network, so renders wait a few seconds for them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenders {
    @get:Rule val compose = createComposeRule()

    private fun render(name: String, waitMs: Long = 6_000, content: @Composable () -> Unit) {
        // Home's hero pages by itself forever: drive the clock by hand so the test never waits on it.
        compose.mainClock.autoAdvance = false
        compose.setContent { ReelTheme { Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) { content() } } }
        settle(waitMs)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    /** Lets network loads finish while advancing Compose's clock (animations, image crossfades). */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    @Test @Config(qualifiers = PHONE_DARK)
    fun phoneHome() = render("phone-home") { PhoneApp(incomingMagnet = null, onIncomingHandled = {}, openDest = null, onOpenHandled = {}) }

    @Test @Config(qualifiers = PHONE_DARK)
    fun phoneSettings() = render("phone-settings", 1_500) { SettingsScreen(isTv = false) }

    @Test @Config(qualifiers = PHONE)
    fun phoneSettingsLight() = render("phone-settings-light", 1_500) { SettingsScreen(isTv = false) }

    @Test @Config(qualifiers = TV)
    fun tvHome() = render("tv-home") { TvApp(incomingMagnet = null, onIncomingHandled = {}, openDest = null, onOpenHandled = {}) }

    @Test @Config(qualifiers = TV)
    fun tvSettings() = render("tv-settings", 1_500) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.padding(top = com.kirtanya.reel.ui.tv.TvTopBarHeight)) { SettingsScreen(isTv = true) }
            }
        }
    }

    companion object {
        /** Pixel 8 as the user has it: 1080 × 2400 px, display size raised to 480 dpi → 360 × 800 dp. */
        const val PHONE = "w360dp-h800dp-port-notnight-xxhdpi"
        const val PHONE_DARK = "w360dp-h800dp-port-night-xxhdpi"
        /** Acer R4 Google TV: 1920 × 1080 px at 320 dpi. */
        const val TV = "w960dp-h540dp-land-television-night-xhdpi"
    }
}
