package com.kirtanya.reel.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kirtanya.reel.core.player.AudioBoost
import com.kirtanya.reel.core.player.VolumeBoost
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the panel set, read on ExoPlayer's playback thread. */
class BoostSettings {
    @Volatile var volume: VolumeBoost = VolumeBoost.Off
    @Volatile var dialogue: Boolean = false
}

/**
 * Volume and dialogue boost ([AudioBoost]) inside ExoPlayer's audio sink, on 16-bit or float PCM. Works on
 * any device (Android's audio effects were refused on the TV); not on Dolby / DTS passed through to the TV
 * as is, which is why the player decodes those itself when a boost is on at the start.
 */
@OptIn(UnstableApi::class)
internal class BoostAudioProcessor(private val settings: BoostSettings) : BaseAudioProcessor() {
    private var boost: AudioBoost? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        boost = AudioBoost(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        // Always active (in the chain), so switching a boost on mid-film needs no reconfigure; off copies through.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val b = boost
        val fmt = inputAudioFormat
        b?.volume = settings.volume
        b?.dialogue = settings.dialogue
        if (b == null || !b.active) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val count = size / if (float) 4 else 2
        if (scratch.size < count) scratch = FloatArray(count)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until count) scratch[i] = src.getFloat()
        else for (i in 0 until count) scratch[i] = src.getShort() / 32768f
        b.process(scratch, count / fmt.channelCount)
        if (float) for (i in 0 until count) out.putFloat(scratch[i])
        else for (i in 0 until count) out.putShort((scratch[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
    }

    override fun onReset() {
        boost = null
    }
}
