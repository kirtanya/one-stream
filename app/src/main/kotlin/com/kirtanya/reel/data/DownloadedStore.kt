package com.kirtanya.reel.data

import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.DownloadedSet
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Which films and episodes are completely downloaded, for the "Downloaded" badge on cards and title pages. Built from
 * the engine's finished / seeding downloads and where each came from (download origins). It waits for the engine to
 * exist instead of creating it (the P2P gate must be up before anything starts the engine), and only runs while a
 * screen shows a badge. Downloads without a known origin (a pasted magnet) have no title to badge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedStore(app: ReelApp) {
    val set: StateFlow<DownloadedSet> = combine(
        app.engineCreated.flatMapLatest { created -> if (created) app.engine.torrents else flowOf(emptyList()) },
        app.downloadOrigins.all,
    ) { torrents, origins ->
        DownloadedSet.of(
            torrents.filter { it.mode == TorrentMode.DOWNLOAD }.mapNotNull { t ->
                origins[t.infoHash.lowercase()]?.let { KnownDownload(t.infoHash, it, t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING) }
            },
        )
        // The engine refreshes ~1 Hz while downloading; the badges only care when a download completes.
    }.distinctUntilChanged().stateIn(app.appScope, SharingStarted.WhileSubscribed(5_000), DownloadedSet.EMPTY)
}
