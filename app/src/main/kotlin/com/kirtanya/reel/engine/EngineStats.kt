package com.kirtanya.reel.engine

import android.os.SystemClock
import android.util.Log
import com.kirtanya.reel.core.stream.StreamStats
import java.util.concurrent.ConcurrentHashMap

/**
 * `ReelStats` logcat lines (docs/STREAMING_V2.md §11): one key=value line per event, so streaming
 * can be measured on the user's devices with `adb logcat -s ReelStats` and compared across builds.
 */
object EngineStats {
    const val TAG = "ReelStats"

    /** Per-torrent stats for the stream being started / played (key = info-hash). */
    private val byKey = ConcurrentHashMap<String, StreamStats>()

    fun now() = SystemClock.elapsedRealtime()

    fun begin(key: String, tapMs: Long, fields: Map<String, Any?>): StreamStats {
        val s = StreamStats(tapMs)
        byKey[key] = s
        log("start", linkedMapOf<String, Any?>("key" to key.take(12)) + fields)
        return s
    }

    fun of(key: String): StreamStats? = byKey[key]

    fun end(key: String, extra: Map<String, Any?> = emptyMap()) {
        val s = byKey.remove(key) ?: return
        Log.i(TAG, s.endLine(linkedMapOf<String, Any?>("key" to key.take(12)) + extra))
    }

    /** Logs `ev=<event>` with `t` relative to the stream's tap, when one is running for [key]. */
    fun event(key: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        val t = byKey[key]?.t(now())
        log(event, linkedMapOf<String, Any?>("t" to t, "key" to key.take(12)) + fields)
    }

    fun log(event: String, fields: Map<String, Any?>) = Log.i(TAG, StreamStats.line(event, fields))
}
