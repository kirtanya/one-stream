package com.kirtanya.reel.player

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The remote on the settings panel's tabs (Playback / Audio / Subtitles / Picture / More): ←/→ on a tab or on a row
 * walks the tabs, ↓ from a tab goes into its rows, ↑ from a tab's first row goes back to the tab, and focus never
 * leaves the panel at the ends. Keys are sent as the remote does: the view's own handler first, then the focus search
 * the window runs for an arrow nobody handled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = CropRender.TV)
class SettingsTabsKeysTest {
    private lateinit var scene: PlayerScenes.Tv
    private lateinit var root: View
    private lateinit var panel: ViewGroup

    @Before fun open() {
        scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, cropPercent = 0f)
        root = scene.root
        // The panel is the ScrollView around the tabs and rows.
        panel = generateSequence(tab("Playback").parent) { it.parent }.first { it is ScrollView } as ViewGroup
        tab("Playback").requestFocusFromTouch()
        idle()
    }

    @After fun close() = scene.release()

    private fun idle() = ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun tab(name: String): View = root.findViewWithTag("tab:$name") ?: error("no tab $name")

    private fun row(tag: String): View? = root.findViewWithTag(tag)

    private fun focusedTag(): String? = root.findFocus()?.tag as? String

    private fun isInPanel(v: View?): Boolean = generateSequence(v) { it.parent as? View }.any { it === panel }

    private fun press(code: Int, stayInPanel: Boolean = true) {
        val focused = root.findFocus() ?: error("nothing has focus")
        val handled = root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        if (!handled) {
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                else -> View.FOCUS_DOWN
            }
            focused.focusSearch(dir)?.requestFocus(dir)
        }
        idle()
        if (stayInPanel) assertTrue("focus left the panel after key $code (on ${focusedTag()})", isInPanel(root.findFocus()))
    }

    @Test fun startsOnThePlaybackTab() {
        assertEquals("tab:Playback", focusedTag())
        assertNotNull("Speed", row("speed"))
        assertNull("Audio rows hidden", row("Audio delay"))
    }

    @Test fun rightFromPlaybackTabShowsAudio() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("tab:Audio", focusedTag())
        assertNotNull("Audio delay", row("Audio delay"))
        assertNotNull("Dialogue boost", row("Dialogue boost"))
        assertNull("Playback rows hidden", row("speed"))
    }

    @Test fun downFromTabGoesToItsFirstRowAndUpComesBack() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("audio", focusedTag())   // Audio track, the Audio tab's first row
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Audio delay", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("audio", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_UP)       // first row → its tab
        assertEquals("tab:Audio", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_UP)       // nothing above the tabs in the panel: stays
        assertEquals("tab:Audio", focusedTag())
    }

    @Test fun rightOnARowSwitchesToTheNextTabsFirstRow() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("speed", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertNotNull("Audio rows show", row("Audio delay"))
        assertNull("Playback rows gone", row("speed"))
        assertEquals("audio", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertNotNull("Subtitle delay", row("Subtitle delay"))
        assertEquals("sub", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertNotNull("Audio rows show again", row("Audio delay"))
        assertEquals("audio", focusedTag())
    }

    @Test fun tabsWalkTheWholeBarAndStopAtBothEnds() {
        val order = listOf("Playback", "Audio", "Subtitles", "Picture", "More")
        press(KeyEvent.KEYCODE_DPAD_LEFT)     // first tab: stays
        assertEquals("tab:Playback", focusedTag())
        for (name in order.drop(1)) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("tab:$name", focusedTag())
        }
        assertNotNull("More rows", row(CHANGE_SOURCE_ROW))
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }   // last tab: stays
        assertEquals("tab:More", focusedTag())
        for (name in order.dropLast(1).reversed()) {
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("tab:$name", focusedTag())
        }
    }

    @Test fun leftAndRightAtTheEndsOfTheRowsStayOnTheRow() {
        // Last tab, its last row: → has no next tab, so focus stays on the row.
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val last = focusedTag()
        assertEquals(REPORT_ROW, last)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(last, focusedTag())
        assertNotNull("More rows still show", row(CHANGE_SOURCE_ROW))
        // First tab's first row: ← has no previous tab, so focus stays too.
        tab("Playback").requestFocusFromTouch(); idle()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("speed", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("speed", focusedTag())
        assertNotNull(row("speed"))
    }

    @Test fun cropBarsKeepsLeftAndRightForItself() {
        for (i in 1..3) press(KeyEvent.KEYCODE_DPAD_RIGHT)   // Picture tab
        assertEquals("tab:Picture", focusedTag())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(CROP_ROW, focusedTag())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("still on Crop bars", CROP_ROW, focusedTag())
        assertNotNull("still the Picture tab", row("Picture"))
        assertNull("Audio rows never showed", row("Audio delay"))
    }
}
