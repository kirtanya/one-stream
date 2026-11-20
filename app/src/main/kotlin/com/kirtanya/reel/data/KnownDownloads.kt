package com.kirtanya.reel.data

import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase

/**
 * The downloads that were started for a known film / episode, torrents and direct (debrid / HTTP) ones alike: a finished direct
 * download counts like a finished torrent for "Downloaded", the next-episode prefetch, My List and Auto-download (docs/HOME_DISK.md §25.6).
 */
fun ReelApp.knownDownloads(): List<KnownDownload> {
    val origins = downloadOrigins.all.value
    return downloadsNow().filter { it.mode == TorrentMode.DOWNLOAD }.mapNotNull { t ->
        origins[t.infoHash.lowercase()]?.let { KnownDownload(t.infoHash, it, t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING) }
    }
}
