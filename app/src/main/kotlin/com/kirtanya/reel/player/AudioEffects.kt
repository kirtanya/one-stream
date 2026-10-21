package com.kirtanya.reel.player

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * Night mode and voice boost on the player's audio session, with Android's DynamicsProcessing
 * (API 28+, both devices; media3 has no compressor of its own):
 *  - night mode: one full-range compressor band (4:1 above −28 dB) with make-up gain, so loud scenes
 *    come down and quiet speech comes up;
 *  - voice boost: a pre-EQ lifting 1–4 kHz (where speech is clear) and trimming the low end;
 *  - either on: a limiter at −1 dB keeps the extra gain from clipping.
 * Rebuilt whenever a switch or the session changes (cheap; a short click at most). Some TV firmware
 * refuses the effect: [apply] reports that, and the panel says "Not available on this device".
 */
internal class AudioEffects(private val stat: (String, Map<String, Any?>) -> Unit) {
    private var fx: DynamicsProcessing? = null
    private var session = 0
    private var night = false
    private var voice = false

    /** False after the device refused the effect (then the switches show as off with a note). */
    var available = true
        private set

    /** Sets the effects for [audioSession]; returns whether they're running as asked. */
    fun apply(audioSession: Int, nightMode: Boolean, voiceBoost: Boolean): Boolean {
        if (audioSession == session && nightMode == night && voiceBoost == voice && (fx != null) == (nightMode || voiceBoost)) return true
        release()
        session = audioSession
        night = nightMode
        voice = voiceBoost
        if (!nightMode && !voiceBoost || audioSession <= 0) return true
        return runCatching {
            fx = DynamicsProcessing(0, audioSession, config(nightMode, voiceBoost)).apply { enabled = true }
            stat("audioFx", mapOf("ok" to true, "night" to nightMode, "voice" to voiceBoost))
            true
        }.getOrElse { t ->
            available = false
            Log.w("ReelPlayer", "audio effects refused", t)
            stat("audioFx", mapOf("ok" to false, "night" to nightMode, "voice" to voiceBoost, "error" to t.javaClass.simpleName))
            false
        }
    }

    fun release() {
        runCatching { fx?.release() }
        fx = null
    }

    private fun config(night: Boolean, voice: Boolean): DynamicsProcessing.Config {
        val eqCuts = floatArrayOf(250f, 1_000f, 4_000f, 20_000f)
        val eqGains = floatArrayOf(-3f, 0f, 5f, 0f)
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            voice, if (voice) eqCuts.size else 0,
            night, if (night) 1 else 0,
            false, 0,
            true,
        )
        val config = builder.build()
        if (voice) {
            val eq = DynamicsProcessing.Eq(true, true, eqCuts.size)
            for (i in eqCuts.indices) eq.setBand(i, DynamicsProcessing.EqBand(true, eqCuts[i], eqGains[i]))
            config.setPreEqAllChannelsTo(eq)
        }
        if (night) {
            val mbc = DynamicsProcessing.Mbc(true, true, 1)
            // cutoff, attack ms, release ms, ratio, threshold dB, knee dB, noise gate dB, expander ratio, pre-gain, post-gain dB.
            mbc.setBand(0, DynamicsProcessing.MbcBand(true, 20_000f, 3f, 80f, 4f, -28f, 6f, -90f, 1f, 0f, 9f))
            config.setMbcAllChannelsTo(mbc)
        }
        config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        return config
    }
}
