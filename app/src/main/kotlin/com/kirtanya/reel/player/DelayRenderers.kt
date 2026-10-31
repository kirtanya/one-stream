package com.kirtanya.reel.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.text.TextOutput

/**
 * Audio and subtitle delay for one player, read on ExoPlayer's playback thread. Media3 1.11 has no
 * audio-offset or subtitle-offset setting, so [DelayRenderersFactory] shifts the clocks itself.
 */
class PlaybackDelays {
    /** + = sound later than the picture. */
    @Volatile var audioUs: Long = 0
    /** + = subtitles later than the speech. */
    @Volatile var subtitlesUs: Long = 0
}

/**
 * ExoPlayer's renderers with the user's delays:
 *  - audio: the audio sink's position is the clock the video follows, so reporting it [PlaybackDelays.audioUs]
 *    ahead shows each frame that much earlier against the sound = the sound plays later;
 *  - subtitles: the text renderer is asked for cues at position − [PlaybackDelays.subtitlesUs].
 * [pcmOnly] (volume / dialogue boost on at start): decode Dolby / DTS here instead of passing it to the
 * TV's receiver, because the boosts ([BoostAudioProcessor]) only work on audio the app itself plays.
 */
@OptIn(UnstableApi::class)
internal class DelayRenderersFactory(context: Context, private val delays: PlaybackDelays, private val pcmOnly: Boolean, private val boost: BoostSettings) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(BoostAudioProcessor(boost)))
        @Suppress("DEPRECATION")
        if (pcmOnly) builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        return DelayedAudioSink(builder.build(), delays)
    }

    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: java.util.ArrayList<Renderer>) {
        val start = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in start until out.size) out[i] = DelayedTextRenderer(out[i], delays)
    }
}

@OptIn(UnstableApi::class)
private class DelayedAudioSink(sink: AudioSink, private val delays: PlaybackDelays) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else p + delays.audioUs
    }
}

@OptIn(UnstableApi::class)
private class DelayedTextRenderer(renderer: Renderer, private val delays: PlaybackDelays) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(positionUs - delays.subtitlesUs, elapsedRealtimeUs)

    override fun resetPosition(positionUs: Long, joining: Boolean) = super.resetPosition(positionUs - delays.subtitlesUs, joining)
}
