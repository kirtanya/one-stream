package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.AudioBoost
import com.kirtanya.reel.core.player.VolumeBoost
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioBoostTest {
    private fun tone(frames: Int, channels: Int, amp: Float, hz: Double = 1_000.0, pattern: (Int) -> Float = { 1f }) =
        FloatArray(frames * channels) { i -> (amp * sin(2 * PI * hz * (i / channels) / 48_000)).toFloat() * pattern(i % channels) }

    private fun peak(s: FloatArray, channel: Int, channels: Int, from: Int = 0) =
        (from until s.size / channels).maxOf { abs(s[it * channels + channel]) }

    @Test fun offLeavesTheSoundAlone() {
        val s = tone(4_800, 2, 0.5f)
        val before = s.copyOf()
        AudioBoost(48_000, 2).process(s, 4_800)
        assertTrue(s.contentEquals(before))
    }

    @Test fun volumeBoostMakesQuietSoundLouder() {
        val s = tone(4_800, 2, 0.2f)
        AudioBoost(48_000, 2).apply { volume = VolumeBoost.Medium }.process(s, 4_800)
        // +6 dB ≈ ×2, and 0.4 is under the limiter's knee: exact.
        assertEquals(0.4f, peak(s, 0, 2), 0.01f)
        assertEquals(1.41f, VolumeBoost.Low.gain, 0.01f)
    }

    @Test fun loudSoundNeverClips() {
        val s = tone(4_800, 2, 0.95f)
        AudioBoost(48_000, 2).apply { volume = VolumeBoost.High; dialogue = true }.process(s, 4_800)
        assertTrue(s.all { abs(it) <= 1f })
        assertEquals(0.5f, AudioBoost.softLimit(0.5f))
        assertTrue(AudioBoost.softLimit(5f) <= 1f && AudioBoost.softLimit(-5f) >= -1f)
        assertTrue(AudioBoost.softLimit(0.9f) < 0.9f)
    }

    @Test fun dialogueBoostLiftsTheCentreAndLowersTheSides() {
        // Speech-like: the same in left and right (the centre).
        val centre = tone(9_600, 2, 0.2f, hz = 2_500.0)
        AudioBoost(48_000, 2).apply { dialogue = true }.process(centre, 9_600)
        assertTrue(peak(centre, 0, 2, from = 4_800) > 0.4f, "centre louder")
        // Music-like: opposite in left and right (only the sides).
        val side = tone(9_600, 2, 0.2f) { c -> if (c == 0) 1f else -1f }
        AudioBoost(48_000, 2).apply { dialogue = true }.process(side, 9_600)
        assertEquals(0.14f, peak(side, 0, 2, from = 4_800), 0.01f)
    }

    @Test fun dialogueBoostOnFivePointOneUsesTheCentreChannel() {
        val s = tone(9_600, 6, 0.2f, hz = 2_500.0)
        AudioBoost(48_000, 6).apply { dialogue = true }.process(s, 9_600)
        assertTrue(peak(s, 2, 6, from = 4_800) > 0.45f, "centre channel lifted")
        assertEquals(0.14f, peak(s, 0, 6, from = 4_800), 0.01f)
    }
}
