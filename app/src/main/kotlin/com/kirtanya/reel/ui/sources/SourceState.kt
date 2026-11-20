package com.kirtanya.reel.ui.sources

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.sources.RankPrefs
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun requestFor(d: TitleDetails, season: Int?, episode: Int?) = SourceRequest(
    tmdbId = d.summary.ref.id,
    imdbId = d.imdbId,
    type = d.summary.ref.type,
    title = d.summary.title,
    year = d.summary.year,
    season = season,
    episode = episode,
    originalTitle = d.originalTitle,
)

/** Last results per title/episode, so going back and forth doesn't search again. */
private val searchCache = HashMap<String, SourceSearch>()

/** The finished search for [key] ("movie:603", "tv:1399:s1e3") if this run has one: shared with the player's Change source page. */
fun cachedSearch(key: String): SourceSearch? = searchCache[key]?.takeIf { it.done }

fun cacheSearch(key: String, search: SourceSearch) { searchCache[key] = search }

/**
 * Searches every enabled addon and plugin for [req] and updates as answers arrive.
 * [rescan] forgets the cached answer and searches again.
 */
@Composable
fun rememberSourceSearch(req: SourceRequest, isTv: Boolean, languages: List<String>? = null): Pair<SourceSearch?, () -> Unit> {
    val app = ReelApp.get(LocalContext.current)
    var state by remember(req.key) { mutableStateOf(searchCache[req.key]) }
    var attempt by remember(req.key) { mutableStateOf(0) }
    LaunchedEffect(req.key, attempt) {
        if (attempt == 0 && searchCache[req.key]?.done == true) return@LaunchedEffect
        app.sources.ensureReady()
        app.aggregator.search(req, app.sources.providers(), rankPrefs(app, isTv, languages)).collect {
            state = it
            if (it.done) searchCache[req.key] = it
        }
    }
    return state to { searchCache.remove(req.key); attempt++ }
}

/**
 * TVs have little storage: torrents bigger than the free space can't be streamed there. On top: the
 * user's source preferences for this device (quality cap, never CAM, pinned / blocked providers).
 */
fun rankPrefs(context: Context, isTv: Boolean, languages: List<String>? = null, forDownload: Boolean = false): RankPrefs {
    val base = if (!isTv) RankPrefs() else {
        val internalCap = (StatFs(context.filesDir.path).availableBytes - (1L shl 30)).coerceAtLeast(0)
        // A download on the TV's USB disk is not limited by the internal storage (docs/HOME_DISK.md §24.4): a blocking read, only asked for downloads (never on the main thread).
        RankPrefs(maxBytes = if (forDownload) ReelApp.get(context).downloadPlacer.downloadCap(internalCap) else internalCap)
    }
    // Quick start's wanted languages for this title (QuickStart.wantedLanguages): the first one leads, the rest are fine to hear.
    val withLanguages = if (languages.isNullOrEmpty()) base else base.copy(language = languages.first(), alsoLanguages = languages.drop(1))
    return com.kirtanya.reel.ReelApp.get(context).sourcePrefs.applyTo(withLanguages)
}

/** The languages to prefer for [d] (Settings → Audio language; by default Hindi first for a Hindi-original title); also what Quick start recommends in. */
fun wantedLanguages(context: Context, d: TitleDetails): List<String> =
    com.kirtanya.reel.core.sources.QuickStart.wantedLanguages(d.originalLanguage, ReelApp.get(context).playbackSettings.state.value.audioLanguage)

/** Best source to start straight away: first non-external one. */
fun bestOf(search: SourceSearch?): Source? = search?.sources?.let(com.kirtanya.reel.core.sources.SourceBrowser::recommended)

/** What the player needs to play [source] for this film / episode and remember it. */
fun playbackFor(d: TitleDetails, episode: EpisodeNumber?, episodeName: String?, stillPath: String?, source: Source): Playback {
    val type = d.summary.ref.type.path
    return Playback(
        key = Playback.keyFor(type, d.summary.ref.id, episode),
        titleKey = "$type:${d.summary.ref.id}",
        title = d.summary.title,
        subtitle = episode?.let { listOfNotNull(it.label, episodeName).joinToString(" · ") },
        tmdbType = type,
        tmdbId = d.summary.ref.id,
        imdbId = d.imdbId,
        year = d.summary.year,
        episode = episode,
        posterPath = d.summary.posterPath,
        backdropPath = d.summary.backdropPath,
        stillPath = stillPath,
        next = episode?.let { nextEpisode(d.seasons, it, d.lastAired) },
        source = source,
        originalLanguage = d.originalLanguage,
    )
}

/** [startAtMs]: start exactly there (the player's Change source), else resume from history. */
fun play(context: Context, playback: Playback, startAtMs: Long? = null) {
    when (playback.source.kind) {
        SourceKind.EXTERNAL -> openExternal(context, playback.source)
        // Torrents follow the phone's network rule first (docs/HOME_DISK.md §5.3).
        else -> com.kirtanya.reel.ui.homedisk.gatedPlay(context, playback.source, playback.title) { context.startActivity(PlayerActivity.forPlayback(context, playback, startAtMs)) }
    }
}

/** Service links: the TV deep link on TVs, the app link on phones, else the web page. */
private fun openExternal(context: Context, source: Source) {
    val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    val candidates = buildList {
        if (isTv) source.tvAppUrl?.let { add(runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()) }
        source.appUrl?.let { add(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        source.externalUrl?.let { add(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }.filterNotNull()
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
}

/**
 * Torrents → the engine (only the chosen file). Direct links → our own downloader (`HttpDownloader`, docs/HOME_DISK.md §25).
 * [origin] remembers the film / episode for "Change source". [replacing] (an info-hash, or `http:<id>`) is the
 * download this one replaces: it's deleted only once the new one has started, so a failed start
 * leaves it alone (docs/STREAMING_V2.md §7.3a). On the TV with the USB disk ready a torrent is saved there when the file is
 * under 4 GiB (docs/HOME_DISK.md §24.3); [automatic] picks that cannot be placed are skipped without a message.
 *
 * With an [origin] the title page shows the outcome itself (docs/DESIGN_STYLE.md "Download status on the title page"): the item is
 * "Starting…" until the download exists, a failure is written on the page, and a second press while it starts does nothing.
 * So the toast is only for what the page can't show: a start without an origin, and automatic picks that fail.
 */
fun download(context: Context, source: Source, title: String, origin: DownloadOrigin? = null, replacing: String? = null, startedMessage: String? = null, placeSrc: String = "button", automatic: Boolean = false) {
    if (source.kind == SourceKind.EXTERNAL) return
    val app = ReelApp.get(context)
    val main = android.os.Handler(context.mainLooper)
    val item = origin?.let(com.kirtanya.reel.core.library.AutoDownload::keyOf)
    // One start per item at a time: a double press, or the same pick from two screens.
    if (item != null && !com.kirtanya.reel.ui.title.DownloadStarts.begin(item)) return
    fun toast(msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    fun dropOld(newHash: String?) {
        val old = replacing ?: return
        if (newHash != null && newHash.equals(old, ignoreCase = true)) return
        main.post {
            if (com.kirtanya.reel.core.httpdl.HttpDownloads.isKey(old)) {
                app.httpDownloads.remove(old)
            } else {
                app.engine.remove(old, deleteFiles = true)
                app.downloadOrigins.remove(old)
            }
        }
    }
    val started = startedMessage ?: if (replacing != null) "Downloading “$title” from the new source" else "Downloading “$title”"
    /** The start's outcome: [created] (null error), [error] (written on the page; a toast only when the page can't show it), or skipped ([silent]). */
    fun finish(created: Boolean, error: String? = null) {
        when {
            created -> {
                if (item != null) com.kirtanya.reel.ui.title.DownloadStarts.created(item, app.appScope)
                if (item == null || startedMessage != null) toast(started)
            }
            error != null -> {
                if (item != null) com.kirtanya.reel.ui.title.DownloadStarts.failed(item, error)
                if (item == null || automatic) toast(error)
            }
            else -> if (item != null) com.kirtanya.reel.ui.title.DownloadStarts.clear(item)
        }
    }
    app.appScope.launch {
        try {
            when (source.kind) {
                SourceKind.TORRENT -> {
                    val magnet = source.magnetUri()
                    if (magnet == null) { finish(created = false); return@launch }
                    val place = withContext(Dispatchers.IO) { app.downloadPlacer.decide(placeSrc, source.sizeBytes) }
                    val refused = place.refused
                    if (refused != null) { if (automatic) finish(created = false) else finish(created = false, error = refused.message); return@launch }
                    val hash = app.engine.addMagnet(magnet, TorrentMode.DOWNLOAD, source.fileIdx, source.filename, saveDir = place.saveDir)
                    origin?.let { app.downloadOrigins.put(hash, it) }
                    dropOld(hash)
                    finish(created = true)
                }
                SourceKind.DIRECT -> {
                    // Our own downloader (docs/HOME_DISK.md §25): a row on the Downloads screen, placed like a torrent.
                    when (val added = withContext(Dispatchers.IO) { app.httpDownloads.add(source, title, origin, placeSrc) }) {
                        is com.kirtanya.reel.download.HttpDownloader.Added.Started -> { dropOld(null); finish(created = true) }
                        is com.kirtanya.reel.download.HttpDownloader.Added.Refused -> if (automatic) finish(created = false) else finish(created = false, error = added.message)
                    }
                }
                SourceKind.EXTERNAL -> finish(created = false)
            }
        } catch (e: EngineException) {
            finish(created = false, error = e.message ?: "Couldn't start the download")
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (item != null) com.kirtanya.reel.ui.title.DownloadStarts.clear(item)
            throw e
        } catch (e: Exception) {
            finish(created = false, error = e.message ?: "Couldn't start the download")
        }
    }
}
