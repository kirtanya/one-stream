package com.kirtanya.reel.core.stream

/**
 * Measurements for one stream, logged as `ReelStats` lines (docs/STREAMING_V2.md §11) so the
 * phases of the streaming rework can be compared on the user's devices.
 *
 * All times are ms on the elapsed-realtime clock; `t` values are relative to the tap.
 */
class StreamStats(val tapMs: Long) {
    var metaAt: Long? = null; private set
    var firstPeerAt: Long? = null; private set
    var firstPieceAt: Long? = null; private set
    var firstByteAt: Long? = null; private set
    var readyAt: Long? = null; private set
    var firstFrameAt: Long? = null; private set

    private var rebufStart: Long? = null
    private var rebufIsSeek = false
    private val rebufMs = ArrayList<Long>()
    private var seekStart: Long? = null
    private val seekMs = ArrayList<Long>()
    private val waitMs = ArrayList<Long>()
    var fatalSlow = 0; private set

    fun t(at: Long) = at - tapMs

    @Synchronized fun meta(at: Long) { if (metaAt == null) metaAt = at }
    @Synchronized fun firstPeer(at: Long) { if (firstPeerAt == null) firstPeerAt = at }
    @Synchronized fun firstPiece(at: Long) { if (firstPieceAt == null) firstPieceAt = at }
    @Synchronized fun firstByte(at: Long) { if (firstByteAt == null) firstByteAt = at }
    @Synchronized fun ready(at: Long) { if (readyAt == null) readyAt = at }
    @Synchronized fun firstFrame(at: Long) { if (firstFrameAt == null) firstFrameAt = at }

    /** Buffering began. Buffering right after a seek counts as seek time, not a rebuffer. */
    @Synchronized fun bufferingStart(at: Long) {
        if (firstFrameAt == null || rebufStart != null) return
        rebufStart = at
        rebufIsSeek = seekStart != null
    }

    @Synchronized fun bufferingEnd(at: Long) {
        val s = rebufStart ?: return
        if (!rebufIsSeek) rebufMs += at - s
        rebufStart = null
        rebufIsSeek = false
    }

    @Synchronized fun seek(at: Long) { seekStart = at }

    /** The player is ready after a seek: one seek-latency sample. */
    @Synchronized fun readyAfterSeek(at: Long): Long? {
        val s = seekStart ?: return null
        seekStart = null
        return (at - s).also { seekMs += it }
    }

    @Synchronized fun readerWait(ms: Long) { waitMs += ms }
    @Synchronized fun fatalSlowError() { fatalSlow++ }

    val rebufferCount get() = rebufMs.size
    val rebufferTotalMs get() = rebufMs.sum()
    val rebufferMaxMs get() = rebufMs.maxOrNull() ?: 0
    val seekCount get() = seekMs.size
    @Synchronized fun seekP(p: Int): Long = percentile(seekMs, p)

    /** The `ev=end` summary line. */
    @Synchronized fun endLine(extra: Map<String, Any?> = emptyMap()): String = line(
        "end",
        linkedMapOf<String, Any?>(
            "ttm" to metaAt?.let(::t),
            "ttfp" to firstPieceAt?.let(::t),
            "ttff" to firstFrameAt?.let(::t),
            "rebuf" to "$rebufferCount/${rebufferTotalMs}/$rebufferMaxMs",
            "seeks" to seekCount,
            "p50" to seekP(50),
            "p90" to seekP(90),
            "waits" to "${waitMs.size}/${waitMs.maxOrNull() ?: 0}",
            "fatalSlow" to fatalSlow,
        ) + extra,
    )

    companion object {
        /** `ev=<event> k=v k=v …`, skipping null values. */
        fun line(event: String, fields: Map<String, Any?>): String = buildString {
            append("ev=").append(event)
            fields.forEach { (k, v) -> if (v != null) append(' ').append(k).append('=').append(v) }
        }

        fun percentile(values: List<Long>, p: Int): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val idx = ((p / 100.0) * (sorted.size - 1)).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }
}
