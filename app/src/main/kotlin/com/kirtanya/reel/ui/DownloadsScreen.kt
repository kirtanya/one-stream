package com.kirtanya.reel.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth


import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import com.kirtanya.reel.player.PlayerActivity
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.ReelCard
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class DownloadFilter(val label: String) { All("All"), Active("Downloading"), Done("Done"), Streams("Stream cache") }

/**
 * Downloads and the stream cache: storage at a glance, then downloads, then the stream cache as its
 * own section. Each card plays; downloads can change source or become stream cache, cached streams
 * can be saved as downloads (docs/STREAMING_V2.md §7.3a).
 */
@Composable
fun DownloadsScreen(isTv: Boolean, onAdd: () -> Unit, onOpenDest: (com.kirtanya.reel.ui.nav.Dest) -> Unit = {}) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val live by engine.torrents.collectAsState()
    val torrents = fixed?.torrents ?: live
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var confirmDelete by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var more by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var confirmCache by remember { mutableStateOf<TorrentSnapshot?>(null) }
    val liveOrigins by ReelApp.get(context).downloadOrigins.all.collectAsState()
    val origins = fixed?.origins ?: liveOrigins
    var cacheBytes by remember { mutableLongStateOf(fixed?.storage?.first ?: 0L) }
    var freeBytes by remember { mutableLongStateOf(fixed?.storage?.second ?: 0L) }
    LaunchedEffect(Unit) {
        while (fixed?.storage == null) {
            withContext(Dispatchers.IO) {
                cacheBytes = engine.cacheBytes()
                freeBytes = runCatching { StatFs(engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
            }
            delay(3000)
        }
    }
    val shown = torrents.filter { it.matches(filter) }
    val shownDownloads = shown.filter { it.mode == TorrentMode.DOWNLOAD }
    val shownCache = shown.filter { it.mode == TorrentMode.STREAM }
    // Disk checks once, not on every recomposition (and some devices can't answer at all).
    val saveDir = remember { runCatching { engine.downloadsDir().path.substringAfter("/0/") }.getOrNull() }
    val needsFileAccess = remember { !isTv && Build.VERSION.SDK_INT >= 30 && runCatching { !Environment.isExternalStorageManager() }.getOrDefault(false) }
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.28f)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.l else 0.dp, bottom = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.bottomBarClearance + Tokens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item {
                Row(Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()).padding(top = Tokens.Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Downloads", style = Tokens.Type.display, color = Tokens.Colors.text)
                        if (saveDir != null) Text("Saving to $saveDir", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isTv) GlassIconButton(Icons.Rounded.Add, "Add a link", onAdd)
                }
            }
            item { StorageCard(torrents, cacheBytes, freeBytes) { engine.trimCache(0) } }
            if (needsFileAccess) {
                item {
                    ReelCard {
                        Text("Save to your Downloads folder", style = Tokens.Type.heading, color = Tokens.Colors.text)
                        Text("Allow file access so downloads land in Download/Reel, where other apps can open them.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                        ReelButton("Allow", {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }, icon = Icons.Rounded.Folder)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    items(DownloadFilter.entries) { f ->
                        val count = torrents.count { it.matches(f) }
                        ReelButton("${f.label} · $count", { filter = f }, kind = if (filter == f) ButtonKind.Primary else ButtonKind.Ghost)
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Download,
                        if (filter == DownloadFilter.Streams) "Nothing cached" else "Nothing here yet",
                        if (isTv) "Download from a title's sources, or send a link from your phone."
                        else "Tap + to add a magnet link or a .torrent file, or download from a title's sources.",
                        action = if (!isTv) ({ ReelButton("Add a link", onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add) }) else null,
                    )
                }
            }
            items(shownDownloads, key = { it.infoHash }) { t -> DownloadCard(t, onDelete = { confirmDelete = t }, onMore = { more = t }) }
            if (shownCache.isNotEmpty()) {
                item(key = "cache-header") {
                    Column(Modifier.widthIn(max = 980.dp).padding(top = if (shownDownloads.isEmpty()) 0.dp else Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                        Text("Stream cache · ${shownCache.size}", style = Tokens.Type.heading, color = Tokens.Colors.text)
                        Text(
                            "What you played recently, so it starts again without waiting. Deleted automatically when space is needed; save one to keep it.",
                            style = Tokens.Type.caption, color = Tokens.Colors.textSecondary,
                        )
                    }
                }
                items(shownCache, key = { it.infoHash }) { t -> DownloadCard(t, onDelete = { confirmDelete = t }, onMore = null) }
            }
        }
    }

    more?.let { t ->
        val origin = origins[t.infoHash.lowercase()]
        MoreDialog(
            t, canChangeSource = origin != null,
            onChangeSource = {
                more = null
                origin?.let { o ->
                    val type = if (o.type == com.kirtanya.reel.core.tmdb.MediaType.TV.path) com.kirtanya.reel.core.tmdb.MediaType.TV else com.kirtanya.reel.core.tmdb.MediaType.MOVIE
                    onOpenDest(com.kirtanya.reel.ui.nav.Dest.Sources(com.kirtanya.reel.core.tmdb.TitleRef(type, o.tmdbId), o.season, o.episode, download = true, replacing = t.infoHash))
                }
            },
            onKeepAsCache = { more = null; confirmCache = t },
            onDismiss = { more = null },
        )
    }

    confirmCache?.let { t ->
        val finished = t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING
        AlertDialog(
            onDismissRequest = { confirmCache = null },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Keep as stream cache?", style = Tokens.Type.heading, color = Tokens.Colors.text) },
            text = { Text(com.kirtanya.reel.core.stream.ModeChange.cacheConfirm(finished), style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = { ReelButton("Keep as cache", { engine.keepAsCache(t.infoHash); confirmCache = null }, kind = ButtonKind.Primary, icon = Icons.Rounded.Theaters) },
            dismissButton = { ReelButton("Keep download", { confirmCache = null }, kind = ButtonKind.Ghost) },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Delete “${com.kirtanya.reel.ui.sources.releaseName(t.name)}”?", style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            text = { Text("This removes the torrent and its downloaded files from this device.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = {
                ReelButton("Delete", {
                    engine.remove(t.infoHash, deleteFiles = true)
                    ReelApp.get(context).downloadOrigins.remove(t.infoHash)
                    confirmDelete = null
                }, kind = ButtonKind.Primary, icon = Icons.Rounded.Delete)
            },
            dismissButton = { ReelButton("Keep it", { confirmDelete = null }, kind = ButtonKind.Ghost) },
        )
    }
}

@Composable
private fun StorageCard(torrents: List<TorrentSnapshot>, cacheBytes: Long, freeBytes: Long, onClearCache: () -> Unit) {
    val downloaded = torrents.filter { it.mode == TorrentMode.DOWNLOAD }.sumOf { it.doneBytes }
    val used = downloaded + cacheBytes
    val total = (used + freeBytes).coerceAtLeast(1)
    ReelCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Text(formatBytes(freeBytes), style = Tokens.Type.title, color = Tokens.Colors.text)
            Text("free on this device", style = Tokens.Type.label, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
        }
        GradientProgress(used.toFloat() / total, height = 6.dp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Pill("${formatBytes(downloaded)} downloads")
            Pill("${formatBytes(cacheBytes)} stream cache")
        }
        if (cacheBytes > 0) ReelButton("Clear stream cache", onClearCache, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
    }
}

@Composable
private fun DownloadCard(t: TorrentSnapshot, onDelete: () -> Unit, onMore: (() -> Unit)?) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val done = t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING
    ReelCard(Modifier.widthIn(max = 980.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
            // Tile: initial on the accent gradient (torrents have no artwork).
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(Tokens.Radius.m))
                    .background(Brush.linearGradient(listOf(Tokens.Colors.accentDeep, Tokens.Colors.surface3)))
                    .border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, RoundedCornerShape(Tokens.Radius.m)),
                contentAlignment = Alignment.Center,
            ) {
                if (t.mode == TorrentMode.STREAM) androidx.compose.material3.Icon(Icons.Rounded.Theaters, null, tint = Tokens.Colors.text)
                else Text(t.name.trim().take(1).uppercase(), style = Tokens.Type.title, color = Tokens.Colors.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                Text(com.kirtanya.reel.ui.sources.releaseName(t.name), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(statusLine(t), style = Tokens.Type.caption, color = if (t.error != null) Tokens.Colors.danger else Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!done) GradientProgress(t.progress)
        when {
            t.error != null -> Text(t.error, style = Tokens.Type.caption, color = Tokens.Colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
            t.stalledForSec >= 120 -> Text("No one is sharing this right now. It'll continue when someone does.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
            ReelButton(if (done) "Play" else "Play now", { context.startActivity(PlayerActivity.forTorrent(context, t.infoHash, t.name)) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.PlayArrow)
            Spacer(Modifier.weight(1f))
            // Icons, not labels, so Play + up to three actions fit a 360 dp phone; the rest are under More.
            if (t.mode == TorrentMode.STREAM) {
                // Keeps the pieces already fetched and downloads the rest (Keep, §7.3).
                GlassIconButton(Icons.Rounded.SaveAlt, "Save as download", {
                    engine.keep(t.infoHash)
                    android.widget.Toast.makeText(context, "Saving as a download", android.widget.Toast.LENGTH_SHORT).show()
                })
            } else if (!done) {
                if (t.phase == TorrentPhase.PAUSED) GlassIconButton(Icons.Rounded.Download, "Resume", { engine.resume(t.infoHash) })
                else GlassIconButton(Icons.Rounded.Pause, "Pause", { engine.pause(t.infoHash) })
            }
            GlassIconButton(Icons.Rounded.Delete, "Delete", onDelete)
            if (onMore != null) GlassIconButton(Icons.Rounded.MoreVert, "More", onMore)
        }
    }
}

/** One rule for both the filter's list and its count (errors show under Downloading). */
private fun TorrentSnapshot.matches(f: DownloadFilter): Boolean = when (f) {
    DownloadFilter.All -> true
    DownloadFilter.Active -> mode == TorrentMode.DOWNLOAD && phase !in setOf(TorrentPhase.FINISHED, TorrentPhase.SEEDING)
    DownloadFilter.Done -> mode == TorrentMode.DOWNLOAD && phase in setOf(TorrentPhase.FINISHED, TorrentPhase.SEEDING)
    DownloadFilter.Streams -> mode == TorrentMode.STREAM
}

private fun statusLine(t: TorrentSnapshot): String = buildString {
    append(
        when (t.phase) {
            TorrentPhase.METADATA -> "Finding peers"
            TorrentPhase.CHECKING -> "Checking files"
            TorrentPhase.DOWNLOADING -> "${(t.progress * 100).toInt()}%"
            TorrentPhase.PAUSED -> "Paused · ${(t.progress * 100).toInt()}%"
            TorrentPhase.FINISHED -> "Downloaded"
            TorrentPhase.SEEDING -> "Downloaded · sharing"
            TorrentPhase.ERROR -> "Error"
        },
    )
    if (t.hasMetadata) append(" · ").append(formatBytes(t.wantedBytes))
    if (t.phase == TorrentPhase.DOWNLOADING || t.phase == TorrentPhase.METADATA) {
        append(" · ↓ ").append(formatBytes(t.downBps.toLong())).append("/s · ").append(com.kirtanya.reel.ui.plural(t.peers, "peer"))
        t.etaSec?.let { append(" · ").append(formatEta(it)) }
    }
}

/** A download's other actions, as a dialog of buttons (reachable with the remote too). */
@Composable
internal fun MoreDialog(t: TorrentSnapshot, canChangeSource: Boolean, onChangeSource: () -> Unit, onKeepAsCache: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = { Text(com.kirtanya.reel.ui.sources.releaseName(t.name), style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                if (canChangeSource) {
                    ReelButton("Change source", onChangeSource, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.SwapHoriz)
                } else {
                    Text("Change source works for downloads started from a title's sources.", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                }
                ReelButton("Keep as stream cache", onKeepAsCache, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Theaters)
            }
        },
        confirmButton = { ReelButton("Close", onDismiss, kind = ButtonKind.Ghost) },
    )
}
