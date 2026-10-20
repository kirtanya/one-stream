package com.kirtanya.reel.core.stream

/** The torrent flags restore cares about (mirrors libtorrent's torrent_flags). */
data class RestoreFlags(val autoManaged: Boolean, val paused: Boolean, val sequential: Boolean)

/** Our own remembered state for a torrent (from TorrentMeta). */
data class RestoreMeta(
    val download: Boolean,
    val userPaused: Boolean = false,
    val seedingDone: Boolean = false,
    val pausedForSpace: Boolean = false,
    val pausedForStream: Boolean = false,
    val wasAutoManaged: Boolean? = null,
)

/**
 * The flags a torrent comes back with after a restart (docs/STREAMING_V2.md §9.2).
 *
 * Before this, every restart forced downloads back to auto-managed and unpaused, so a download
 * the user paused started again by itself. Streams always come back paused until played.
 */
object RestorePolicy {
    fun restore(saved: RestoreFlags, meta: RestoreMeta): RestoreFlags {
        if (!meta.download) return RestoreFlags(autoManaged = false, paused = true, sequential = false)
        val f = saved.copy(sequential = false)
        return when {
            meta.userPaused || meta.seedingDone || meta.pausedForSpace -> f.copy(autoManaged = false, paused = true)
            // The app died while a stream had paused this download: give it back to the queue.
            meta.pausedForStream -> f.copy(autoManaged = true, paused = false)
            meta.wasAutoManaged != null ->
                if (meta.wasAutoManaged) f.copy(autoManaged = true, paused = false) else f.copy(autoManaged = false)
            // Queue-paused (auto-managed + paused) comes back queued: the queue decides.
            saved.autoManaged -> f.copy(paused = false)
            else -> f
        }
    }
}
