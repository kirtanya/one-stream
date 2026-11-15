package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.PictureMode
import com.kirtanya.reel.core.player.PlayerTweaks
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTweaksTest {
    @Test fun `nothing changed lists nothing`() {
        assertEquals(emptyList(), PlayerTweaks().labels())
    }

    @Test fun `every change is listed, picture first`() {
        val t = PlayerTweaks(
            picture = PictureMode.STRETCH, cropPercent = 8f, volumeBoost = 2, dialogueBoost = true,
            audioTrack = "Hindi", audioDelayMs = 150, subtitleDelayMs = -1250, speed = 1.25f,
        )
        assertEquals(
            listOf("Stretch", "Crop 8 %", "Volume +6 dB", "Dialogue boost", "Audio: Hindi", "Audio delay +150 ms", "Subtitle delay −1.25 s", "Speed 1.25×"),
            t.labels(),
        )
    }

    @Test fun `fill is short and a forced shape says its ratio`() {
        assertEquals(listOf("Fill"), PlayerTweaks(picture = PictureMode.FILL).labels())
        assertEquals(listOf("21:9"), PlayerTweaks(picture = PictureMode.CINEMA_21_9).labels())
    }
}
