package com.kirtanya.reel.core.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Volume boost in the player's panel: how much louder than the source, before the soft limiter. */
enum class VolumeBoost(val label: String, val db: Float) {
    Off("Off", 0f), Low("Low", 3f), Medium("Medium", 6f), High("High", 9f);

    val gain: Float get() = 10f.pow(db / 20f)

    companion object {
        fun of(ordinal: Int) = entries.getOrElse(ordinal) { Off }
    }
}

/**
 * The player's own sound boosts, done on the decoded audio (interleaved float samples, −1..1), so they work
 * on any device: Android's audio effects (DynamicsProcessing) were refused on the TV.
 *  - Volume boost: a gain ([VolumeBoost]), then a soft limiter so loud scenes round off instead of clipping.
 *  - Dialogue boost: speech sits in the centre: the centre channel (5.1) or what left and right share (stereo,
 *    "mid") is lifted, with a presence lift around 2.5 kHz, and the rest (music, effects) turned down a little.
 * Channel order is Android's: FL, FR, FC, LFE, BL, BR (…).
 */
class AudioBoost(private val sampleRate: Int, private val channels: Int) {
    @Volatile var volume: VolumeBoost = VolumeBoost.Off
    @Volatile var dialogue: Boolean = false

    val active: Boolean get() = volume != VolumeBoost.Off || dialogue

    private val presence = Peaking(sampleRate, PRESENCE_HZ, PRESENCE_Q, PRESENCE_DB)

    /** In place: [frames] frames of [channels] samples each. */
    fun process(samples: FloatArray, frames: Int) {
        if (!active) return
        val gain = volume.gain
        val talk = dialogue
        for (f in 0 until frames) {
            val i = f * channels
            if (talk) when {
                channels == 1 -> samples[i] = presence.next(samples[i]) * MID_GAIN
                channels == 2 -> {
                    val l = samples[i]; val r = samples[i + 1]
                    val mid = presence.next((l + r) * 0.5f) * MID_GAIN
                    val side = (l - r) * 0.5f * SIDE_GAIN
                    samples[i] = mid + side
                    samples[i + 1] = mid - side
                }
                else -> {
                    for (c in 0 until channels) {
                        samples[i + c] = if (c == CENTRE) presence.next(samples[i + c]) * CENTRE_GAIN else samples[i + c] * SIDE_GAIN
                    }
                }
            }
            for (c in 0 until channels) samples[i + c] = softLimit(samples[i + c] * gain)
        }
    }

    companion object {
        private const val CENTRE = 2
        const val MID_GAIN = 1.5f
        const val CENTRE_GAIN = 1.6f
        const val SIDE_GAIN = 0.7f
        const val PRESENCE_HZ = 2_500.0
        const val PRESENCE_Q = 0.9
        const val PRESENCE_DB = 5.0
        private const val KNEE = 0.8f

        /** Untouched below [KNEE]; above it, rounds off towards (never past) full scale. */
        fun softLimit(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val over = (a - KNEE) / (1f - KNEE)
            val y = KNEE + (1f - KNEE) * tanh(over.toDouble()).toFloat()
            return if (x < 0) -y else y
        }
    }
}

/** RBJ peaking EQ: [db] at [hz], one channel's state. */
internal class Peaking(sampleRate: Int, hz: Double, q: Double, db: Double) {
    private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    init {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * hz / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = ((1 + alpha * a) / a0).toFloat()
        b1 = ((-2 * cos(w0)) / a0).toFloat()
        b2 = ((1 - alpha * a) / a0).toFloat()
        a1 = ((-2 * cos(w0)) / a0).toFloat()
        a2 = ((1 - alpha / a) / a0).toFloat()
    }

    fun next(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}
