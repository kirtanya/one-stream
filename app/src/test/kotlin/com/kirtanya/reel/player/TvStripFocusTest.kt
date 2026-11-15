package com.kirtanya.reel.player

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The remote's pause key shows the strip without focus (so the picture isn't covered by a focus ring). On the Acer R4
 * the arrows then went nowhere until the strip hid itself; the first arrow must land on play.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = CropRender.TV)
class TvStripFocusTest {
    private val scene = PlayerScenes.tv(PlayerScenes.MID)

    @After fun close() = scene.release()

    private fun idle() = ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun key(code: Int): Boolean {
        val down = scene.controls.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        scene.controls.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
        idle()
        return down
    }

    @Test fun firstArrowAfterPauseKeyFocusesPlay() {
        scene.controls.hide(); idle()
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertNull("pause key shows the strip without focus", scene.controls.view.findFocus())
        assertTrue("arrow is taken", key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Play or pause", scene.controls.view.findFocus()?.contentDescription)
    }
}
