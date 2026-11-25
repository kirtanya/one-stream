package com.kirtanya.reel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.settings.SettingsPage
import com.kirtanya.reel.ui.settings.TvSettingsPanel
import com.kirtanya.reel.ui.settings.homeListIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back from a settings page lower down the list (Phone & TV) puts the remote on that row, inside the panel. It used to land
 * on the rail behind the panel: the row wasn't composed yet (lazy list), so every focus request missed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenRenders.TV)
class TvSettingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backFromPhoneAndTvLandsOnItsRowNotTheRail() {
        compose.setContent {
            androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
                com.kirtanya.reel.ui.common.LocalIsTv provides true,
            ) {
                com.kirtanya.reel.ui.theme.ReelTheme {
                    Box(Modifier.fillMaxSize()) {
                        // Stands in for the rail: the first focusable of the window, where Android puts a lost remote.
                        Box(Modifier.size(48.dp).testTag("rail").focusable())
                        TvSettingsPanel(onClose = {}, Modifier.align(Alignment.CenterEnd), start = listOf(SettingsPage.PhoneTv))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNode(hasTestTag("rail")).assertIsNotFocused()
        compose.onNode(isFocused()).assert(hasText("Phone & TV", substring = true))
    }

    @Test fun theRootListOpensOnTheRowsGroup() {
        assertEquals(0, homeListIndex(null, isTv = true))
        assertEquals(1, homeListIndex(SettingsPage.Playback, isTv = true))
        assertEquals(3, homeListIndex(SettingsPage.PhoneTv, isTv = true))
    }
}
