package com.kirtanya.reel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import com.kirtanya.reel.core.player.TitleSources
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.ui.tv.TvSourcePick
import com.kirtanya.reel.ui.tv.TvSourceRows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hold OK on a TV source opens its options; it never starts a download by itself. Download in the menu does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenRenders.TV)
@OptIn(ExperimentalTestApi::class)
class TvSourceOptionsTest {
    @get:Rule val compose = createComposeRule()

    private val played = mutableListOf<Source>()
    private val downloaded = mutableListOf<Source>()
    private val first = FocusRequester()

    private fun setUp() {
        val sources = Fake.sources(3)
        val page = TitleSources.page(Fake.manySources, sources, expanded = true)
        val req = SourceRequest(Fake.film.id, null, Fake.film.type, "Dune", 2021)
        compose.setContent {
            androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            val pick = TvSourcePick(page, expanded = true, expand = {}, rescan = {}, play = { played += it }, download = { downloaded += it }, req = req, label = "Dune")
            com.kirtanya.reel.ui.theme.ReelTheme {
                TvSourceRows(pick, Modifier.fillMaxSize().testTag("rows"), first, onPick = { played += it })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { first.requestFocus() }
    }

    private fun holdOk() = compose.onNodeWithTag("rows").performKeyInput { keyDown(Key.DirectionCenter); advanceEventTime(1_500); keyUp(Key.DirectionCenter) }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onNodeWithTag(tag: String) =
        onNode(androidx.compose.ui.test.hasTestTag(tag))

    @Test fun holdingOkOpensTheMenuAndDoesNotDownload() {
        setUp()
        holdOk()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Download file").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, downloaded.size); assertEquals(0, played.size) }
    }

    @Test fun downloadInTheMenuDownloadsAndPlayPlays() {
        setUp()
        holdOk()
        compose.onNodeWithText("Download file").performClick()
        compose.runOnIdle { assertEquals(1, downloaded.size); assertEquals(0, played.size) }
    }

    @Test fun playInTheMenuPlaysWithoutDownloading() {
        setUp()
        holdOk()
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(1, played.size); assertEquals(0, downloaded.size) }
    }
}
