package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.sources.ByteSize
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceFacts
import com.kirtanya.reel.core.stream.MiB
import com.kirtanya.reel.core.stream.PlayerBuffers
import com.kirtanya.reel.core.stream.SchedulerProfile

enum class BufferStage(val label: String) {
    FINDING_PEERS("Finding peers"),
    GETTING_INFO("Getting file info"),
    BUFFERING("Buffering"),
}

/** What the engine says about the stream right now (TorrentSnapshot, trimmed to what the info block shows). */
data class BufferSample(
    val hasMetadata: Boolean,
    val peers: Int,
    /** Seeds connected right now (libtorrent). */
    val liveSeeds: Int,
    val downBps: Long,
    val doneBytes: Long,
)

/**
 * The player's start / buffering info block, as text (PlayerOverlays draws it). [stats] are label + value pairs,
 * at most three; [progress] is 0..1 or null when nothing can be measured (finding peers, getting info).
 */
data class BufferInfo(
    val stage: BufferStage,
    /** "1080p · 1.8 GB · Torrentio". */
    val facts: String,
    val stats: List<Pair<String, String>>,
    val progress: Float?,
    val progressText: String?,
)

object BufferInfoBuilder {
    /** ExoPlayer's `bufferForPlaybackMs` for torrents (PlayerActivity's LoadControl). */
    private const val START_MEDIA_MS = 2_000L
    /** `bufferForPlaybackAfterRebufferMs`. */
    const val REBUFFER_NEED_MS = 5_000L

    /** "1080p · 1.8 GB · Torrentio": quality (when known), size, the addon that listed it. [fileSize] = the real file once known. */
    fun facts(s: Source, fileSize: Long? = null): String = listOfNotNull(
        s.quality.takeIf { it.resolution != null }?.label,
        (fileSize ?: s.sizeBytes)?.takeIf { it > 0 }?.let(ByteSize::format),
        SourceFacts.providerLabel(s.provider).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /**
     * Bytes the player needs before the first frame: what the engine fetches first (the file's head and its index at
     * the tail, SchedulerProfile) plus the two seconds of picture ExoPlayer waits for. An estimate, shown as one.
     */
    fun startTargetBytes(fileSize: Long?, durationMs: Long?, qualityLabel: String?, extension: String = "mkv"): Long {
        val profile = SchedulerProfile.of(com.kirtanya.reel.core.stream.FormFactor.PHONE, com.kirtanya.reel.core.stream.DiskBackend.MMAP)
        val rate = PlayerBuffers.rate(fileSize, durationMs, qualityLabel)
        val fixed = profile.startupHeadBytes + profile.startupTailBytes(extension.lowercase())
        val target = fixed + rate * START_MEDIA_MS / 1000
        return if (fileSize != null && fileSize in 1 until target) fileSize else target
    }

    /**
     * [baselineBytes]: the file's bytes on disk when its info arrived (a stream that was cached before starts ahead).
     * [targetBytes]: [startTargetBytes]. [rebufferAheadMs] non-null = playing before and the picture ran dry: the
     * bar is the picture buffered ahead against what ExoPlayer waits for.
     */
    fun build(
        source: Source,
        fileSize: Long?,
        s: BufferSample,
        baselineBytes: Long?,
        targetBytes: Long?,
        rebufferAheadMs: Long? = null,
    ): BufferInfo {
        val stage = when {
            !s.hasMetadata && s.peers <= 0 -> BufferStage.FINDING_PEERS
            !s.hasMetadata -> BufferStage.GETTING_INFO
            else -> BufferStage.BUFFERING
        }
        val listed = source.seeders?.takeIf { it > 0 }
        val stats = buildList {
            add("Download" to if (s.downBps > 0) ByteSize.format(s.downBps) + "/s" else "–")
            add("Peers" to s.peers.coerceAtLeast(0).toString())
            when {
                listed != null -> add("Seeders" to listed.toString())
                s.liveSeeds > 0 -> add("Seeds" to s.liveSeeds.toString())
            }
        }
        var progress: Float? = null
        var text: String? = null
        if (rebufferAheadMs != null) {
            val ahead = rebufferAheadMs.coerceIn(0, REBUFFER_NEED_MS)
            progress = ahead.toFloat() / REBUFFER_NEED_MS
            text = "${ahead / 1000} s of ${REBUFFER_NEED_MS / 1000} s buffered"
        } else if (stage == BufferStage.BUFFERING && baselineBytes != null && targetBytes != null && targetBytes > 0) {
            val got = (s.doneBytes - baselineBytes).coerceIn(0, targetBytes)
            progress = got.toFloat() / targetBytes
            text = if (got >= targetBytes) "Almost ready" else "${megabytes(got)} of ${megabytes(targetBytes)} to start"
        }
        return BufferInfo(stage, facts(source, fileSize), stats, progress, text)
    }

    /** "12 MB": one unit so "3 of 40 MB" reads at a glance. */
    private fun megabytes(bytes: Long): String = (bytes / MiB).coerceAtLeast(if (bytes > 0) 1 else 0).toString() + " MB"
}
