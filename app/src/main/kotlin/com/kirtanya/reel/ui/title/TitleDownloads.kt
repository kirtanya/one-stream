package com.kirtanya.reel.ui.title

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.httpdl.HomeFileRef
import com.kirtanya.reel.core.httpdl.HttpState
import com.kirtanya.reel.core.library.AutoDownload
import com.kirtanya.reel.core.library.DlAction
import com.kirtanya.reel.core.library.DlKind
import com.kirtanya.reel.core.library.DlPhase
import com.kirtanya.reel.core.library.DownloadButtonState
import com.kirtanya.reel.core.library.DownloadFacts
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.TitleDownload
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import com.kirtanya.reel.ui.common.DeleteDownloadDialog
import com.kirtanya.reel.ui.common.DownloadedBadge
import com.kirtanya.reel.ui.common.EpisodeDownloadMark
import com.kirtanya.reel.ui.common.LocalRenderData
import com.kirtanya.reel.ui.sources.play
import com.kirtanya.reel.ui.sources.playbackFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** A Download the user pressed that has no download to show yet ([pending]), or whose start failed ([error]). */
data class StartEntry(val pending: Boolean, val error: String? = null)

/**
 * The starts in flight, by item key ("movie:603", "tv:1399:s1e3"). One entry per item is what makes a double press (or the
 * same pick from two screens) start once, and what lets the page say "Starting…" and later why a start failed, instead of a toast
 * that is gone before the page changes. In memory only: an entry never outlives the process, and "Starting…" never outlives
 * [SETTLE_MS] after the download exists.
 */
object DownloadStarts {
    /** The download exists from here on: hold "Starting…" this long, until its first snapshot arrives (about a second). */
    const val SETTLE_MS = 3_000L

    private val lock = Any()
    private val _all = MutableStateFlow<Map<String, StartEntry>>(emptyMap())
    val all: StateFlow<Map<String, StartEntry>> = _all.asStateFlow()

    /** False when this item is already starting: the caller does nothing. */
    fun begin(key: String): Boolean = synchronized(lock) {
        if (_all.value[key]?.pending == true) return false
        _all.value = _all.value + (key to StartEntry(pending = true))
        true
    }

    /** The download was created: keep "Starting…" until its snapshot is on screen, then forget the entry. */
    fun created(key: String, scope: CoroutineScope) {
        scope.launch {
            delay(SETTLE_MS)
            clear(key)
        }
    }

    fun failed(key: String, message: String) = synchronized(lock) { _all.value = _all.value + (key to StartEntry(pending = false, error = message)) }

    fun clear(key: String) = synchronized(lock) { _all.value = _all.value - key }
}

/** What the page needs of one snapshot (the engine's torrents and the direct downloads share one type). */
internal fun factsOf(t: TorrentSnapshot): DownloadFacts {
    val held = t.waitingForDisk || t.waitingForNight
    val phase = when {
        held -> DlPhase.DOWNLOADING
        t.userPaused && t.phase != TorrentPhase.FINISHED && t.phase != TorrentPhase.SEEDING -> DlPhase.PAUSED
        else -> when (t.phase) {
            TorrentPhase.METADATA -> DlPhase.METADATA
            TorrentPhase.CHECKING -> DlPhase.CHECKING
            TorrentPhase.DOWNLOADING -> DlPhase.DOWNLOADING
            TorrentPhase.PAUSED -> DlPhase.PAUSED
            TorrentPhase.FINISHED, TorrentPhase.SEEDING -> DlPhase.FINISHED
            TorrentPhase.ERROR -> DlPhase.ERROR
        }
    }
    return DownloadFacts(
        key = t.infoHash, http = t.http, phase = phase, progress = t.progress, wantedBytes = t.wantedBytes, doneBytes = t.doneBytes,
        downBps = t.downBps.toLong(), error = t.error, notice = t.notice, notReady = t.notReady,
        waitingForDisk = t.waitingForDisk, waitingForNight = t.waitingForNight, hasMetadata = t.hasMetadata, stalledForSec = t.stalledForSec,
    )
}

/** Every item of [ref] with something to show, from all downloads right now. Pure: the page's flow and the renders both use it. */
internal fun titleStates(ref: TitleRef, snaps: List<TorrentSnapshot>, origins: Map<String, DownloadOrigin>, starts: Map<String, StartEntry>): Map<String, DownloadButtonState> {
    val base = ref.type.path + ":" + ref.id
    fun mine(key: String) = key == base || key.startsWith("$base:")
    val items = snaps.mapNotNull { t ->
        if (t.mode != TorrentMode.DOWNLOAD) return@mapNotNull null
        val key = AutoDownload.keyOf(origins[t.infoHash.lowercase()] ?: return@mapNotNull null)
        if (mine(key)) key to factsOf(t) else null
    }
    val starting = starts.filter { (k, e) -> e.pending && mine(k) }.keys
    val errors = starts.mapNotNull { (k, e) -> if (!e.pending && e.error != null && mine(k)) k to e.error else null }.toMap()
    return TitleDownload.states(items, starting, errors)
}

private val IDLE = TitleDownload.state(null)

/**
 * Live download state of one title page: one map (item key → state) from a single flow, a tick a second while something moves.
 * Each row reads only its own item through [state] (a `derivedStateOf`), and states compare equal while nothing visible changed
 * (whole percents), so a tick recomposes only the rows that really changed and an idle page recomposes nothing.
 */
@Stable
class TitleDownloads internal constructor(
    private val d: TitleDetails,
    private val map: State<Map<String, DownloadButtonState>>,
    private val context: Context,
) {
    /** What the page does for Download / Change source: the TV opens its panel, the phone the Sources page. */
    var onDownload: (season: Int?, episode: Int?) -> Unit = { _, _ -> }
    var onChangeSource: (season: Int?, episode: Int?, replacing: String) -> Unit = { _, _, _ -> }

    private val base = d.summary.ref.type.path + ":" + d.summary.ref.id

    fun keyOf(season: Int?, episode: Int?) = if (season != null && episode != null) "$base:s${season}e$episode" else base

    /** The state of the film (both null) or of one episode. Read `.value` in the composable that draws it, never in its parent. */
    @Composable
    fun state(season: Int? = null, episode: Int? = null): State<DownloadButtonState> {
        val key = keyOf(season, episode)
        return remember(key, this) { derivedStateOf { map.value[key] ?: IDLE } }
    }

    /** "3 of 10 downloaded · 1 downloading" for [season] of [count] episodes (null while nothing is). */
    @Composable
    fun seasonLine(season: Int?, count: Int): State<String?> {
        val prefix = "$base:s${season}e"
        return remember(prefix, count, this) {
            derivedStateOf { TitleDownload.seasonLine(map.value.filterKeys { it.startsWith(prefix) }.values, count) }
        }
    }

    // ------------------------------------------------------------------ what the buttons do

    private val app get() = ReelApp.get(context)

    /** The snapshot behind an item: the same pick the state was made from. */
    private fun current(season: Int?, episode: Int?): TorrentSnapshot? {
        val key = keyOf(season, episode)
        val origins = app.downloadOrigins.all.value
        val mine = app.downloadsNow().filter { t ->
            t.mode == TorrentMode.DOWNLOAD && origins[t.infoHash.lowercase()]?.let(AutoDownload::keyOf) == key
        }
        val best = TitleDownload.pick(mine.map(::factsOf)) ?: return null
        return mine.firstOrNull { it.infoHash == best.key }
    }

    fun perform(action: DlAction, season: Int?, episode: Int?, episodeName: String? = null, stillPath: String? = null) {
        when (action) {
            DlAction.DOWNLOAD -> onDownload(season, episode)
            DlAction.PLAY -> playDownload(season, episode, episodeName, stillPath)
            DlAction.PAUSE -> current(season, episode)?.let { if (it.http) app.httpDownloads.pause(it.infoHash) else app.engine.pause(it.infoHash) }
            DlAction.RESUME, DlAction.RETRY -> current(season, episode)?.let { if (it.http) app.httpDownloads.resume(it.infoHash) else app.engine.resume(it.infoHash) }
            DlAction.CHANGE_SOURCE -> current(season, episode)?.let { onChangeSource(season, episode, it.infoHash) }
            DlAction.DELETE -> delete(season, episode)
        }
    }

    /**
     * Delete asks first, from wherever it was pressed: the long-press menu closes itself before it acts, so the question lives
     * on the page ([DeleteHost]) and not in the menu. The download control asks in its own dialog and calls [delete] directly.
     */
    private var pendingDelete by mutableStateOf<Pair<Int?, Int?>?>(null)

    fun askDelete(season: Int?, episode: Int?) { pendingDelete = season to episode }

    @Composable
    fun DeleteHost() {
        pendingDelete?.let { (s, e) -> DeleteDownloadDialog(nameOf(s, e), onConfirm = { pendingDelete = null; delete(s, e) }, onDismiss = { pendingDelete = null }) }
    }

    /** The name the delete dialog uses. */
    fun nameOf(season: Int?, episode: Int?): String =
        d.summary.title + if (season != null && episode != null) " · " + EpisodeNumber(season, episode).label else ""

    fun delete(season: Int?, episode: Int?) {
        val t = current(season, episode) ?: return
        if (t.http) {
            app.httpDownloads.remove(t.infoHash)
        } else {
            app.engine.remove(t.infoHash, deleteFiles = true)
            app.downloadOrigins.remove(t.infoHash)
        }
    }

    /**
     * Play this item's download. A torrent plays through the stream path on the same torrent (no second session: the engine
     * already has this info-hash, and the stream sets its own piece priorities), so it works while the download goes on.
     * A finished direct download plays its local file. A direct download that is still running has no playable file yet
     * (a growing file is not supported by the player): it streams the source link while the download carries on.
     */
    private fun playDownload(season: Int?, episode: Int?, episodeName: String?, stillPath: String?) {
        val t = current(season, episode) ?: return
        val number = if (season != null && episode != null) EpisodeNumber(season, episode) else null
        val source = if (t.http) {
            val dl = app.httpDownloads.get(t.infoHash) ?: return
            val size = dl.totalBytes.takeIf { it > 0 } ?: dl.expectedBytes
            if (dl.state == HttpState.DONE) {
                Source(SourceKind.DIRECT, dl.name, "Downloaded", QualityParser.parse(dl.name), url = HomeFileRef.format(dl.id, dl.fileName), filename = dl.fileName, sizeBytes = size)
            } else {
                Source(SourceKind.DIRECT, dl.name, dl.provider.ifBlank { "Link" }, QualityParser.parse(dl.name), url = dl.url, headers = dl.headers, filename = dl.fileName, sizeBytes = size)
            }
        } else {
            Source(SourceKind.TORRENT, t.name, "Torrent", QualityParser.parse(t.name), infoHash = t.infoHash, fileIdx = t.fileIndex)
        }
        play(context, playbackFor(d, number, episodeName, stillPath, source))
    }
}

/** The download state of [d]: collected with the lifecycle (nothing runs while the page is stopped), seeded so the first frame is right. */
@Composable
fun rememberTitleDownloads(d: TitleDetails): TitleDownloads {
    val context = LocalContext.current
    val fixed = LocalRenderData.current
    val ref = d.summary.ref
    val initial = remember(ref, fixed) {
        if (fixed?.torrents != null) titleStates(ref, fixed.torrents, fixed.origins.orEmpty(), fixed.starts.orEmpty())
        else ReelApp.get(context).let { titleStates(ref, it.downloadsNow(), it.downloadOrigins.all.value, DownloadStarts.all.value) }
    }
    val flow = remember(ref, fixed) { downloadStates(context, ref, initial.takeIf { fixed?.torrents != null }) }
    val map = flow.collectAsStateWithLifecycle(initialValue = initial)
    return remember(d, context) { TitleDownloads(d, map, context) }
}

/** [fixed] = screen renders: that map and nothing else (no live stores in a render). */
private fun downloadStates(context: Context, ref: TitleRef, fixed: Map<String, DownloadButtonState>?): Flow<Map<String, DownloadButtonState>> {
    if (fixed != null) return flowOf(fixed)
    val app = ReelApp.get(context)
    return combine(app.allDownloads, app.downloadOrigins.all, DownloadStarts.all) { snaps, origins, starts -> titleStates(ref, snaps, origins, starts) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
}

/**
 * An episode's mark on its picture. Reads only its own item's state, so a tick recomposes this mark and nothing around it.
 * [fallbackDone]: the finished-file badge from the library, for a download the engine no longer lists.
 */
@Composable
fun EpisodeMark(downloads: TitleDownloads, season: Int, episode: Int, fallbackDone: Boolean, modifier: Modifier = Modifier) {
    val state by downloads.state(season, episode)
    if (state.kind != DlKind.IDLE) EpisodeDownloadMark(state, modifier) else if (fallbackDone) DownloadedBadge(modifier)
}

/** "3 of 10 downloaded" under the season chips. Nothing while no episode of the season is downloaded or downloading. */
@Composable
fun SeasonDownloadLine(downloads: TitleDownloads, season: Int?, count: Int, modifier: Modifier = Modifier) {
    val line by downloads.seasonLine(season, count)
    line?.let { androidx.compose.material3.Text(it, modifier, style = com.kirtanya.reel.ui.theme.Tokens.Type.caption, color = com.kirtanya.reel.ui.theme.Tokens.Colors.textSecondary) }
}
