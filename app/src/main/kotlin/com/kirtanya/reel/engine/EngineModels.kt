package com.kirtanya.reel.engine

import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.TorrentFileEntry
import kotlinx.serialization.Serializable

/** STREAM = play now, cached in app storage. DOWNLOAD = keep the files. */
enum class TorrentMode { STREAM, DOWNLOAD }

enum class TorrentPhase { METADATA, CHECKING, DOWNLOADING, PAUSED, FINISHED, SEEDING, ERROR }

/** What the UI sees for one torrent, refreshed about once a second. */
data class TorrentSnapshot(
    val infoHash: String,
    val name: String,
    val mode: TorrentMode,
    val phase: TorrentPhase,
    val progress: Float,
    val wantedBytes: Long,
    val doneBytes: Long,
    val downBps: Int,
    val upBps: Int,
    val peers: Int,
    val seeds: Int,
    val hasMetadata: Boolean,
    val savePath: String,
    val error: String? = null,
    /** Seconds the torrent has had metadata but no data flowing (for the stall warning). */
    val stalledForSec: Int = 0,
) {
    val etaSec: Long? get() =
        if (downBps <= 0 || wantedBytes <= doneBytes) null else (wantedBytes - doneBytes) / downBps
}

/** Saved next to libtorrent's resume data so we remember our own choices. */
@Serializable
data class TorrentMeta(
    val infoHash: String,
    val mode: TorrentMode,
    val streamFileIndex: Int? = null,
    /** Download only this file (index, or a filename hint when the index is unknown). */
    val onlyFileIndex: Int? = null,
    val onlyFileHint: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/** A file that is ready to be served by the local stream server. */
data class StreamInfo(
    val infoHash: String,
    val file: TorrentFileEntry,
    val span: FileSpan,
    val mimeType: String,
    /** Where libtorrent writes this file on disk. */
    val path: java.io.File,
)

class EngineException(message: String) : Exception(message)
