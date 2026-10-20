package com.kirtanya.reel.engine

import com.kirtanya.reel.core.stream.FileIntent
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
    /** The file a Play button should open (the downloaded / last-streamed one). */
    val fileIndex: Int? = null,
    val userPaused: Boolean = false,
    val streaming: Boolean = false,
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
    /** Legacy (schema 1): download only this file. Migrated into [wantedFiles] / [wantedHints]. */
    val onlyFileIndex: Int? = null,
    val onlyFileHint: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    // Schema 2 (docs/STREAMING_V2.md §3.2): all additive with defaults so old files still load.
    /** Download every file. */
    val fullTorrent: Boolean = false,
    /** Downloaded files (season-pack episodes); accumulates, never narrows. */
    val wantedFiles: List<Int> = emptyList(),
    val wantedHints: List<String> = emptyList(),
    /** File priorities still owed once metadata arrives (added with DEFAULT_DONT_DOWNLOAD). */
    val pendingFileIntent: Boolean = false,
    val userPaused: Boolean = false,
    val pausedForStream: Boolean = false,
    val pausedForSpace: Boolean = false,
    val seedingDone: Boolean = false,
    val wasAutoManaged: Boolean? = null,
    /** user | stream: a stream-created torrent with nothing downloaded can be deleted on abandon. */
    val createdBy: String = "user",
    val lastStoppedAt: Long = 0,
    val savePath: String? = null,
    val schema: Int = 1,
) {
    val intent: FileIntent get() = FileIntent(fullTorrent, wantedFiles, wantedHints, streamFileIndex)

    /** Schema 1 → 2: onlyFile* become wanted files; a plain download means the whole torrent. */
    fun migrated(): TorrentMeta {
        if (schema >= 2) return this
        return copy(
            wantedFiles = (wantedFiles + listOfNotNull(onlyFileIndex)).distinct(),
            wantedHints = (wantedHints + listOfNotNull(onlyFileHint)).distinct(),
            fullTorrent = fullTorrent || (mode == TorrentMode.DOWNLOAD && onlyFileIndex == null && onlyFileHint == null),
            onlyFileIndex = null,
            onlyFileHint = null,
            schema = 2,
        )
    }

    fun withIntent(i: FileIntent) = copy(fullTorrent = i.fullTorrent, wantedFiles = i.wantedFiles, wantedHints = i.wantedHints)
}

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
