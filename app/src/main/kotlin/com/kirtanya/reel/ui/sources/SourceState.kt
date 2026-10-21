package com.kirtanya.reel.ui.sources

import android.app.DownloadManager
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
import kotlinx.coroutines.launch

fun requestFor(d: TitleDetails, season: Int?, episode: Int?) = SourceRequest(
    tmdbId = d.summary.ref.id,
    imdbId = d.imdbId,
    type = d.summary.ref.type,
    title = d.summary.title,
    year = d.summary.year,
    season = season,
    episode = episode,
)

/** Last results per title/episode, so going back and forth doesn't search again. */
private val searchCache = HashMap<String, SourceSearch>()

/**
 * Searches every enabled addon and plugin for [req] and updates as answers arrive.
 * [rescan] forgets the cached answer and searches again.
 */
@Composable
fun rememberSourceSearch(req: SourceRequest, isTv: Boolean): Pair<SourceSearch?, () -> Unit> {
    val app = ReelApp.get(LocalContext.current)
    var state by remember(req.key) { mutableStateOf(searchCache[req.key]) }
    var attempt by remember(req.key) { mutableStateOf(0) }
    LaunchedEffect(req.key, attempt) {
        if (attempt == 0 && searchCache[req.key]?.done == true) return@LaunchedEffect
        app.sources.ensureReady()
        app.aggregator.search(req, app.sources.providers(), rankPrefs(app, isTv)).collect {
            state = it
            if (it.done) searchCache[req.key] = it
        }
    }
    return state to { searchCache.remove(req.key); attempt++ }
}

/** TVs have little storage: torrents bigger than the free space can't be streamed there. */
fun rankPrefs(context: Context, isTv: Boolean): RankPrefs {
    if (!isTv) return RankPrefs()
    val free = StatFs(context.filesDir.path).availableBytes
    return RankPrefs(maxBytes = (free - (1L shl 30)).coerceAtLeast(0))
}

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
    )
}

fun play(context: Context, playback: Playback) {
    when (playback.source.kind) {
        SourceKind.EXTERNAL -> openExternal(context, playback.source)
        else -> context.startActivity(PlayerActivity.forPlayback(context, playback))
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
 * Torrents → the engine (only the chosen file). Direct links → Android's download manager.
 * [origin] remembers the film / episode for "Change source". [replacing] (an info-hash) is the
 * download this one replaces: it's deleted only once the new one has started, so a failed start
 * leaves it alone (docs/STREAMING_V2.md §7.3a).
 */
fun download(context: Context, source: Source, title: String, origin: DownloadOrigin? = null, replacing: String? = null) {
    val app = ReelApp.get(context)
    val main = android.os.Handler(context.mainLooper)
    fun dropOld(newHash: String?) {
        val old = replacing ?: return
        if (newHash != null && newHash.equals(old, ignoreCase = true)) return
        main.post {
            app.engine.remove(old, deleteFiles = true)
            app.downloadOrigins.remove(old)
        }
    }
    when (source.kind) {
        SourceKind.TORRENT -> app.appScope.launch {
            val msg = try {
                val hash = app.engine.addMagnet(source.magnetUri() ?: return@launch, TorrentMode.DOWNLOAD, source.fileIdx, source.filename)
                origin?.let { app.downloadOrigins.put(hash, it) }
                dropOld(hash)
                if (replacing != null) "Downloading “$title” from the new source" else "Downloading “$title”"
            } catch (e: EngineException) {
                e.message ?: "Couldn't start the download"
            }
            main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
        SourceKind.DIRECT -> {
            val url = source.url ?: return
            val name = (source.filename ?: url.substringAfterLast('/').substringBefore('?')).ifBlank { "$title.mp4" }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Reel/" + Uri.decode(name))
            source.headers.forEach { (k, v) -> request.addRequestHeader(k, v) }
            val started = runCatching { context.getSystemService(DownloadManager::class.java).enqueue(request) }.isSuccess
            if (started) dropOld(null)
            Toast.makeText(context, if (started) "Downloading “$title”" else "Couldn't start the download", Toast.LENGTH_SHORT).show()
        }
        SourceKind.EXTERNAL -> Unit
    }
}
