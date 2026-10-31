package com.kirtanya.reel.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.LauncherApi
import com.kirtanya.reel.core.launcher.LauncherApi.Query
import com.kirtanya.reel.core.launcher.LauncherCaller
import com.kirtanya.reel.core.tmdb.TitleRef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.launcher.SourceTokens
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.sources.download
import com.kirtanya.reel.ui.sources.playbackFor
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.requestFor
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Launcher's API (docs/LAUNCHER_API.md): queries plus a few actions through call(). Exported, but every call is checked:
 * only One Launcher (package name + its signing certificate, see [LauncherCaller]) may read.
 * The logic lives in core `LauncherApi`; this class only fetches and fills cursors.
 */
/** Sources handed to One Launcher, by token (this process only); read by MainActivity's play links. */
object LauncherSources {
    val tokens = SourceTokens<Playback>()
}

class LauncherProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        val query = LauncherApi.parse(uri.path) { runCatching { uri.getQueryParameter(it) }.getOrNull() }
        // Binder threads only: clear the caller identity so our own file / network access is ours.
        val token = Binder.clearCallingIdentity()
        try {
            return answer(query)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun answer(query: Query): Cursor {
        val app = ReelApp.get(context!!)
        return when (query) {
            Query.Info -> MatrixCursor(LauncherApi.INFO_COLUMNS.toTypedArray(), 1).apply {
                addRow(arrayOf<Any?>(LauncherApi.API_VERSION, BuildConfig.VERSION_NAME))
            }
            is Query.Search -> rows(
                if (query.q.isEmpty()) emptyList()
                else network { LauncherApi.searchRows(app.tmdb.search(query.q), query.limit) }.orEmpty(),
            )
            is Query.Continue -> rows(LauncherApi.continueRows(app.history.continueWatching(), query.limit))
            is Query.Watchlist -> rows(LauncherApi.watchlistRows(app.watchlist.all.value, query.limit))
            is Query.Title -> rows(
                network {
                    val tmdbId = query.tmdb
                    if (tmdbId != null) {
                        val d = app.tmdb.details(TitleRef(query.type, tmdbId))
                        LauncherApi.titleRow(d.summary, d.imdbId ?: query.imdb)
                    } else {
                        app.tmdb.findByImdb(query.imdb!!, query.type)?.let { LauncherApi.titleRow(it, query.imdb) }
                    }
                }?.let(::listOf).orEmpty(),
            )
            is Query.Details -> {
                val ref = TitleRef(query.type, query.tmdb)
                val d = network { app.tmdb.details(ref) }
                cursor(LauncherPage.DETAILS_COLUMNS, listOfNotNull(d?.let { LauncherPage.detailsRow(it, app.watchlist.contains(ref), app.history.continueWatching()) }))
            }
            is Query.Episodes -> cursor(LauncherPage.EPISODE_COLUMNS, network {
                val d = app.tmdb.details(TitleRef(MediaType.TV, query.tmdb))
                LauncherPage.episodeRows(
                    query.tmdb, app.tmdb.season(query.tmdb, query.season), LocalDate.now().toString(), d.lastAired,
                    history = app.history::get,
                    bestToken = { s, e -> LauncherSources.tokens.best("tv:${query.tmdb}:s${s}e$e") },
                )
            }.orEmpty())
            is Query.Sources -> cursor(LauncherPage.SOURCE_COLUMNS, sources(app, query))
            Query.Downloads -> cursor(LauncherPage.DOWNLOAD_COLUMNS, downloads(app))
            Query.Unknown -> rows(emptyList())
        }
    }

    /**
     * The Sources screen's own search (every enabled addon + plugin, ranked the same way). Waits
     * for all of them up to [LauncherPage.SOURCES_TIMEOUT_MS], then answers with what has arrived.
     * Each source gets a token; its Playback (what the Sources screen would play) is kept for
     * onestream://play and the download / prepare actions.
     */
    private fun sources(app: ReelApp, q: Query.Sources): List<Array<Any?>> {
        val ref = TitleRef(q.type, q.tmdb)
        val season = q.season
        val episode = q.episode
        val latest = AtomicReference<Pair<TitleDetails, SourceSearch>?>(null)
        var episodeName: String? = null
        var still: String? = null
        val job = app.appScope.launch {
            runCatching {
                val d = app.tmdb.details(ref)
                if (season != null) {
                    runCatching { app.tmdb.season(q.tmdb, season).firstOrNull { it.number == episode } }.getOrNull()?.let {
                        episodeName = it.name
                        still = it.stillPath
                    }
                }
                app.sources.ensureReady()
                app.aggregator.search(requestFor(d, season, episode), app.sources.providers(), rankPrefs(app, app.isTv)).collect {
                    latest.set(d to it)
                    if (it.done) cancel()
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "launcher sources failed: ${it.message}") }
        }
        runBlocking { withTimeoutOrNull(LauncherPage.SOURCES_TIMEOUT_MS) { job.join() } }
        job.cancel()
        val (d, search) = latest.get() ?: return emptyList()
        val epNumber = if (season != null && episode != null) EpisodeNumber(season, episode) else null
        val ranked = search.sources
        val tokens = ranked.map { LauncherSources.tokens.put(playbackFor(d, epNumber, episodeName, still, it)) }
        val best = LauncherPage.best(ranked)
        LauncherSources.tokens.setBest(requestFor(d, season, episode).key, best?.let { tokens[ranked.indexOf(it)] })
        return LauncherPage.sourceRows(ranked, tokens, ref, season, episode)
    }

    /** Downloads (not the stream cache) while the engine runs; it starts with the app, a stream or a download. */
    private fun downloads(app: ReelApp): List<Array<Any?>> =
        app.engineIfCreated?.torrents?.value.orEmpty().filter { it.mode == TorrentMode.DOWNLOAD }.map { t ->
            LauncherPage.downloadRow(
                t.infoHash,
                t.name.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: t.infoHash.take(8),
                if (t.wantedBytes > 0) "${formatBytes(t.doneBytes)} of ${formatBytes(t.wantedBytes)}" else null,
                t.progress,
                LauncherPage.downloadState(t.phase.name, t.userPaused, t.error != null),
                t.wantedBytes,
            )
        }

    /** Actions; every one answers {ok, message} and none throws for bad input. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        val token = Binder.clearCallingIdentity()
        try {
            val (ok, message) = runCatching { act(method, extras) }.getOrElse { false to (it.message ?: "Something went wrong") }
            return Bundle().apply {
                putBoolean("ok", ok)
                putString("message", message)
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Suppress("DEPRECATION")
    private fun act(method: String, extras: Bundle?): Pair<Boolean, String?> {
        val app = ReelApp.get(context!!)
        fun playback(): Playback? = extras?.getString("token")?.let(LauncherSources.tokens::get)
        return when (method) {
            LauncherPage.WATCHLIST_ADD -> {
                val ref = LauncherPage.titleRef(extras?.get("type"), extras?.get("tmdb")) ?: return false to "Needs type and tmdb"
                val d = network { app.tmdb.details(ref) } ?: return false to "Couldn't reach TMDB"
                app.watchlist.add(d.summary)
                true to null
            }
            LauncherPage.WATCHLIST_REMOVE -> {
                val ref = LauncherPage.titleRef(extras?.get("type"), extras?.get("tmdb")) ?: return false to "Needs type and tmdb"
                app.watchlist.remove(ref)
                true to null
            }
            LauncherPage.DOWNLOAD -> {
                val p = playback() ?: return false to "Unknown source; search again"
                if (!LauncherPage.downloadable(p.source)) return false to "This source can't be downloaded"
                // The app's own Download button (it shows a toast, so on the main thread).
                val origin = p.tmdbId?.let { id -> p.tmdbType?.let { type -> com.kirtanya.reel.core.library.DownloadOrigin(type, id, p.episode?.season, p.episode?.episode, p.displayTitle) } }
                Handler(Looper.getMainLooper()).post { download(app, p.source, p.displayTitle, origin) }
                true to "Downloading “${p.displayTitle}”"
            }
            LauncherPage.PREPARE -> {
                val p = playback() ?: return false to "Unknown source; search again"
                when (p.source.kind) {
                    SourceKind.TORRENT -> {
                        val magnet = p.source.magnetUri() ?: return false to "This source has no magnet link"
                        app.appScope.launch {
                            runCatching {
                                // The player's own start (stream mode, startup pieces first); no player attached.
                                // Nobody plays it → it stops like a stream an external player left (stopWhenIdle).
                                val info = app.engine.startStream(magnet, p.source.fileIdx, p.source.filename)
                                app.engine.stopWhenIdle(info.infoHash, info, LauncherPage.PREPARE_IDLE_MS)
                            }.onFailure { Log.w(TAG, "prepare failed: ${it.message}") }
                        }
                        true to "Preparing"
                    }
                    SourceKind.DIRECT -> true to "Direct links start straight away"
                    SourceKind.EXTERNAL -> false to "This source opens another app"
                }
            }
            LauncherPage.CANCEL_DOWNLOAD -> {
                val key = extras?.getString("key") ?: return false to "Needs key"
                val engine = app.engineIfCreated ?: return false to "No such download"
                val t = engine.snapshot(key)?.takeIf { it.mode == TorrentMode.DOWNLOAD } ?: return false to "No such download"
                // What the Downloads tab's Delete does.
                engine.remove(t.infoHash, deleteFiles = true)
                true to null
            }
            LauncherPage.STOP_ENGINE -> when (val verdict = com.kirtanya.reel.engine.EngineIdle.verdict(app)) {
                is com.kirtanya.reel.core.launcher.StopEngine.Verdict.Keep -> false to verdict.reason
                // Answer first, then stop in the background (EngineIdle.stop).
                com.kirtanya.reel.core.launcher.StopEngine.Verdict.Stop -> {
                    com.kirtanya.reel.engine.EngineIdle.stop(app, "stop_engine for One Launcher")
                    true to "One Stream stopped"
                }
            }
            LauncherPage.WARM_ENGINE -> {
                com.kirtanya.reel.engine.EngineIdle.warm(app, "One Launcher title page")
                true to null
            }
            else -> false to "Unknown action $method"
        }
    }

    private fun cursor(columns: List<String>, list: List<Array<Any?>>) = MatrixCursor(columns.toTypedArray(), list.size).apply {
        list.forEach { addRow(it) }
    }

    private fun rows(list: List<LauncherApi.Row>) = MatrixCursor(LauncherApi.COLUMNS.toTypedArray(), list.size).apply {
        list.forEach { addRow(it.values()) }
    }

    /**
     * Runs a TMDB call with a hard deadline. The call runs on the app scope (OkHttp's blocking read
     * can't be interrupted by a timeout around it); the binder thread waits at most
     * [LauncherApi.NETWORK_TIMEOUT_MS] and gives up — null, so the launcher gets an empty cursor.
     */
    private fun <T> network(timeoutMs: Long = LauncherApi.NETWORK_TIMEOUT_MS, block: suspend () -> T): T? {
        val app = ReelApp.get(context!!)
        val job = app.appScope.async { runCatching { block() }.onFailure { Log.w(TAG, "launcher query failed: ${it.message}") }.getOrNull() }
        return runBlocking { withTimeoutOrNull(timeoutMs) { job.await() } }
            .also { if (job.isActive) job.cancel() }
    }

    private fun checkCaller() {
        val pm = context!!.packageManager
        val allowed = LauncherCaller.isAllowed(
            callingUid = Binder.getCallingUid(),
            ownUid = Process.myUid(),
            packagesForUid = { pm.getPackagesForUid(it)?.toList() },
            hasCert = { pkg, sha -> pm.hasSigningCertificate(pkg, sha, PackageManager.CERT_INPUT_SHA256) },
        )
        if (!allowed) throw SecurityException("Only One Launcher may read One Stream's launcher API")
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    private companion object {
        const val TAG = "ReelLauncher"
    }
}
