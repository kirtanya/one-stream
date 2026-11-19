package com.kirtanya.reel.data

import android.util.Log
import android.widget.Toast
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.DownloadWhere
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.library.MyListDownload
import com.kirtanya.reel.core.library.OnAddToList
import com.kirtanya.reel.core.library.Watched
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.nav.Dest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What adding a title to My list does about downloading it (Settings → "When I add to My list"): ask which source
 * (the Sources page in download mode, opened by the nav roots through [asks]), download the ranker's best, or nothing.
 * A film downloads as a whole; a series downloads one episode, not the show (see [MyListDownload.episodeFor]).
 */
class MyListDownloader(private val app: ReelApp) {
    private val _asks = MutableSharedFlow<Dest.Sources>(extraBufferCapacity = 1)

    /** Sources pages to open in "pick one to download" mode. */
    val asks: SharedFlow<Dest.Sources> = _asks.asSharedFlow()

    /** Call after a title was added to My list. */
    fun added(title: TitleSummary) {
        val mode = app.autoDownloader.settings.value.onAddToList
        if (mode == OnAddToList.JUST_ADD) return
        app.appScope.launch {
            val details = runCatching { app.tmdb.details(title.ref) }.getOrNull()
            val episode = if (title.ref.type == MediaType.TV) {
                val d = details ?: return@launch
                val lookup = app.watched.state.first()
                MyListDownload.episodeFor(title.ref.id, Watched.allEpisodes(d.seasons, d.lastAired), lookup::isWatched) ?: return@launch
            } else null
            val label = title.title + (episode?.let { " · ${it.label}" } ?: "")
            val origin = DownloadOrigin(title.ref.type.path, title.ref.id, episode?.season, episode?.episode, label)
            if (MyListDownload.alreadyDownloading(origin, known())) return@launch
            when (mode) {
                OnAddToList.ASK -> _asks.emit(Dest.Sources(title.ref, episode?.season, episode?.episode, download = true, addedToList = true))
                OnAddToList.BEST -> downloadBest(details ?: return@launch, origin)
                OnAddToList.JUST_ADD -> Unit
            }
        }
    }

    private fun known(): List<KnownDownload> {
        if (!app.engineCreated.value) return emptyList()
        val origins = app.downloadOrigins.all.value
        return app.engine.torrents.value.filter { it.mode == TorrentMode.DOWNLOAD }.mapNotNull { t ->
            origins[t.infoHash.lowercase()]?.let { KnownDownload(t.infoHash, it, t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING) }
        }
    }

    /** Searches like the Sources page does and starts the ranker's top downloadable pick. */
    private suspend fun downloadBest(d: TitleDetails, origin: DownloadOrigin) {
        app.sources.ensureReady()
        val req = com.kirtanya.reel.ui.sources.requestFor(d, origin.season, origin.episode)
        val search = withTimeoutOrNull(LauncherPage.SOURCES_TIMEOUT_MS) {
            app.aggregator.search(req, app.sources.providers(), com.kirtanya.reel.ui.sources.rankPrefs(app, app.isTv, com.kirtanya.reel.ui.sources.wantedLanguages(app, d), forDownload = true)).first { it.done }
        }
        // TV with the USB disk ready (docs/HOME_DISK.md §24.3): prefer a source under 4 GiB at the same or one-lower quality.
        val onUsb = withContext(Dispatchers.IO) { app.downloadPlacer.usbInPlay() }
        val source = search?.let { MyListDownload.bestToDownload(it.sources, underFatLimit = onUsb) }
        if (onUsb && source != null) search.let { MyListDownload.bestToDownload(it.sources) }?.let { app.downloadPlacer.logSwap("mylist", it, source) }
        withContext(Dispatchers.Main) {
            if (source == null) {
                Toast.makeText(app, "No source to download for “${origin.label}” yet. It's on My list.", Toast.LENGTH_LONG).show()
                return@withContext
            }
            Log.i(TAG, "download on add ${origin.label} → ${source.provider}")
            start(source, origin)
        }
    }

    /**
     * The same route as the Download button on the Sources page: the TV (and anything that isn't a torrent) downloads
     * straight away; the phone sends it home when "At home" is what it remembered, else downloads on the phone, which
     * the network rule may hold or ask about (never the office network).
     */
    private fun start(source: com.kirtanya.reel.core.sources.Source, origin: DownloadOrigin) {
        val label = origin.label
        if (app.isTv || source.kind != SourceKind.TORRENT) return com.kirtanya.reel.ui.sources.download(app, source, label, origin, placeSrc = "mylist", automatic = true)
        val home = app.home
        val atHomeOk = home.setUp && home.client.pairedTv() != null
        if (home.settings.value.downloadWhere == DownloadWhere.AT_HOME && atHomeOk) {
            source.magnetUri()?.let { com.kirtanya.reel.ui.homedisk.downloadAtHome(app, it, source.fileIdx, source.filename, label, origin) }
            return
        }
        val hash = source.magnetUri()?.let { Magnet.parse(it)?.infoHash }
        home.gate.download(
            hash,
            add = { com.kirtanya.reel.ui.sources.download(app, source, label, origin) },
            atHome = if (atHomeOk) ({ source.magnetUri()?.let { com.kirtanya.reel.ui.homedisk.downloadAtHome(app, it, source.fileIdx, source.filename, label, origin) } }) else null,
        )
    }

    private companion object {
        const val TAG = "ReelMyList"
    }
}
