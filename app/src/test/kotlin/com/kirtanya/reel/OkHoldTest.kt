package com.kirtanya.reel

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.common.focusCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A held remote OK fires exactly one click / long press, and never clicks on whatever it opened. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenRenders.TV)
@OptIn(ExperimentalTestApi::class)
class OkHoldTest {
    @get:Rule val compose = createComposeRule()

    private var firstClicks = 0
    private var firstLong = 0
    private var secondClicks = 0
    private val first = FocusRequester()
    private val second = FocusRequester()

    private fun setUp(firstHasLongPress: Boolean) {
        compose.setContent {
            // Clickables only take focus in keyboard (remote) mode.
            androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            Row {
                Row(
                    Modifier.testTag("a").size(40.dp).focusRequester(first)
                        .focusCard(onLongClick = if (firstHasLongPress) ({ firstLong++ }) else null, onClick = { firstClicks++ }),
                ) {}
                Row(Modifier.testTag("b").size(40.dp).focusRequester(second).focusCard(onClick = { secondClicks++ })) {}
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("a").assertIsFocused()
    }

    @Test fun aQuickPressClicksOnce() {
        setUp(firstHasLongPress = false)
        compose.onNodeWithTag("a").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, firstClicks) }
    }

    @Test fun aHeldPressWithoutLongPressClicksOnceOnRelease() {
        setUp(firstHasLongPress = false)
        compose.onNodeWithTag("a").performKeyInput { keyDown(Key.DirectionCenter); advanceEventTime(1_500); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, firstClicks) }
    }

    @Test fun aHeldPressWithLongPressDoesTheLongPressAndNoClick() {
        setUp(firstHasLongPress = true)
        compose.onNodeWithTag("a").performKeyInput { keyDown(Key.DirectionCenter); advanceEventTime(1_500); keyUp(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, firstLong)
            assertEquals(0, firstClicks)
        }
    }

    @Test fun theRepeatsAndReleaseOfAPressThatMovedFocusDoNotClickTheNewElement() {
        setUp(firstHasLongPress = false)
        compose.onNodeWithTag("a").performKeyInput {
            keyDown(Key.DirectionCenter)
            // The click handler of the first card opened "a new page": focus lands on the next element mid-hold.
            compose.runOnIdle { second.requestFocus() }
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle { assertEquals("the new element never saw the press begin", 0, secondClicks) }
    }
}
