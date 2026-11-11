package com.kirtanya.reel.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.P2pUse
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.library.AutoDownload
import com.kirtanya.reel.core.library.AutoDownloadSettings
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.library.Watched
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Settings → Downloads automation: keeps the next episodes of My-list shows downloaded and deletes
 * watched downloads after N days. Runs on app start, every 30 min, when the network changes and when
 * something gets watched. On the phone it only downloads where the network rule allows it *and* at
 * home (never the office, never mobile data); the TV is at home.
 */
@OptIn(FlowPreview::class)
class AutoDownloader(private val app: ReelApp) {
    private val prefs = app.getSharedPreferences("auto_download", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _settings = MutableStateFlow(
        prefs.getString("settings", null)?.let { runCatching { json.decodeFromString(AutoDownloadSettings.serializer(), it) }.getOrNull() } ?: AutoDownloadSettings(),
    )
    val settings: StateFlow<AutoDownloadSettings> = _settings.asStateFlow()
    private val running = Mutex()

    /** What the last round did, for Settings ("Downloaded 2 episodes · 5 min ago"). */
    private val _last = MutableStateFlow<String?>(prefs.getString("last", null))
    val lastRun: StateFlow<String?> = _last.asStateFlow()

    fun update(change: (AutoDownloadSettings) -> AutoDownloadSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit { putString("settings", json.encodeToString(AutoDownloadSettings.serializer(), next)) }
        if (next.enabled) app.appScope.launch { runOnce() }
    }

    fun start() {
        app.appScope.launch {
            merge(
                app.home.network.now.map { },
                app.watched.all.map { },
                app.watchlist.all.map { },
                settings.map { },
            ).debounce(15_000).collect { runOnce() }
        }
        app.appScope.launch {
            while (isActive) {
                delay(30 * 60_000L)
                runOnce()
            }
        }
    }

    suspend fun runOnce() = running.withLock {
        val s = _settings.value
        runCatching { cleanup(s) }.onFailure { Log.w(TAG, "cleanup failed: ${it.message}") }
        if (!s.enabled || !allowedHere()) return@withLock
        runCatching { fill(s) }.onFailure { Log.w(TAG, "auto-download failed: ${it.message}") }
    }

    /** Phone: an allowed network that is home. TV: always (it lives at home). */
    private fun allowedHere(): Boolean {
        if (app.isTv) return true
        val verdict = app.home.gate.verdict(P2pUse.DOWNLOAD)
        val net = app.home.network.now.value
        val home = app.home.settings.value
        val atHome = app.home.disk.ui.value.home?.isHome == true || (net.isWifiLike && net.fingerprint != null && net.fingerprint == home.homeFingerprint)
        return verdict == P2pVerdict.Allow && atHome
    }

    private fun known(): List<KnownDownload> {
        val origins = app.downloadOrigins.all.value
        return app.engine.torrents.value.filter { it.mode == TorrentMode.DOWNLOAD }.mapNotNull { t ->
            origins[t.infoHash.lowercase()]?.let { KnownDownload(t.infoHash, it, t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING) }
        }
    }

    private suspend fun fill(s: AutoDownloadSettings) {
        val lookup = app.watched.state.first()
        val shows = app.watchlist.all.value.filter { !it.removed }.mapNotNull { it.ref }.filter { it.type == MediaType.TV }
        var started = 0
        for (ref in shows) {
            val d = runCatching { app.tmdb.details(ref) }.getOrNull() ?: continue
            val aired = Watched.allEpisodes(d.seasons, d.lastAired)
            val missing = AutoDownload.missing(ref.id, AutoDownload.wanted(ref.id, aired, s.keepNext, lookup::isWatched), known())
            for (ep in missing) {
                if (startOne(d, ep)) started++
                if (started >= MAX_PER_ROUND) break
            }
            if (started >= MAX_PER_ROUND) break
        }
        if (started > 0) note("Downloaded ${com.kirtanya.reel.ui.plural(started, "episode")}")
    }

    /** Searches like the Sources page and downloads the source that matches what you watched last (same release). */
    private suspend fun startOne(d: com.kirtanya.reel.core.tmdb.TitleDetails, ep: EpisodeNumber): Boolean {
        app.sources.ensureReady()
        val req = com.kirtanya.reel.ui.sources.requestFor(d, ep.season, ep.episode)
        val search = withTimeoutOrNull(LauncherPage.SOURCES_TIMEOUT_MS) {
            app.aggregator.search(req, app.sources.providers(), com.kirtanya.reel.ui.sources.rankPrefs(app, app.isTv, com.kirtanya.reel.ui.sources.wantedLanguages(app, d))).first { it.done }
        } ?: return false
        val previous = app.history.entries.value.firstOrNull { it.playback.titleKey == "tv:${d.summary.ref.id}" && !it.playback.markedOnly }?.playback?.source
        val downloadable = search.sources.filter(LauncherPage::downloadable)
        val source = pickNextSource(previous, downloadable) ?: return false
        val label = "${d.summary.title} · ${ep.label}"
        Log.i(TAG, "auto-download $label → ${source.provider}")
        withContext(Dispatchers.Main) {
            com.kirtanya.reel.ui.sources.download(app, source, label, DownloadOrigin("tv", d.summary.ref.id, ep.season, ep.episode, label), startedMessage = "Downloading $label (automatic)")
        }
        return true
    }

    private fun cleanup(s: AutoDownloadSettings) {
        val marks = app.watched.all.value.associateBy { it.key }
        val gone = AutoDownload.toDelete(known(), s.deleteWatchedAfterDays, System.currentTimeMillis()) { key ->
            marks[key]?.takeIf { it.watched }?.changedAt
        }
        gone.forEach { hash ->
            app.engine.remove(hash, deleteFiles = true)
            app.downloadOrigins.remove(hash)
        }
        if (gone.isNotEmpty()) note("Deleted ${com.kirtanya.reel.ui.plural(gone.size, "watched download")}")
    }

    private fun note(text: String) {
        val line = "$text · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}"
        _last.value = line
        prefs.edit { putString("last", line) }
    }

    private companion object {
        const val TAG = "ReelAuto"
        /** A round never starts more than this many (a long list on first switch-on shouldn't flood the disk). */
        const val MAX_PER_ROUND = 4
    }
}
