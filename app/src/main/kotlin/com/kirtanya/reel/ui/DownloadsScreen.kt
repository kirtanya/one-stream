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
import androidx.compose.foundation.layout.offset


import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Info
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

private typealias DownloadFilter = com.kirtanya.reel.core.library.LibraryFilter

private fun TorrentSnapshot.facts() = com.kirtanya.reel.core.library.LibraryFacts(
    kept = mode == TorrentMode.DOWNLOAD, finished = phase == TorrentPhase.FINISHED || phase == TorrentPhase.SEEDING, streaming = streaming,
)

/**
 * Everything played or downloaded, in one list (core/library/LibraryList): storage at a glance, then the titles,
 * downloading first. Kept titles stay; the rest is what you played and clears itself when space is needed.
 * Every row plays; Keep / Don't keep move a title between the two (docs/STREAMING_V2.md §7.3a).
 */
@Composable
fun DownloadsScreen(isTv: Boolean, onAdd: () -> Unit, onOpenDest: (com.kirtanya.reel.ui.nav.Dest) -> Unit = {}) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val live by engine.torrents.collectAsState()
    // Direct (debrid / HTTP) downloads sit in the same list as torrents, as rows with the same look (docs/HOME_DISK.md §25.6).
    val liveDirect by ReelApp.get(context).httpDownloads.snapshots.collectAsState()
    val torrents = fixed?.torrents ?: (live + liveDirect)
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var confirmDelete by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var more by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var confirmCache by remember { mutableStateOf<TorrentSnapshot?>(null) }
    // Home disk (docs/HOME_DISK.md §17.4): badges, held downloads, at-home requests, delete-where. Phone only.
    val home = com.kirtanya.reel.ui.homedisk.rememberHomeView()
    val showHome = !isTv && (home.setUp || home.jobs.isNotEmpty())
    if (!isTv && fixed == null) LaunchedEffect(Unit) {
        // Opening Downloads is one of the moments the outbox is sent and at-home progress fetched (§9.3).
        val client = ReelApp.get(context).home.client
        client.flush(force = true)
        while (true) {
            if (client.jobs.value.any { it.state != null && it.state !in com.kirtanya.reel.homedisk.HomeClient.DONE_STATES }) client.poll()
            delay(10_000)
        }
    }
    val liveOrigins by ReelApp.get(context).downloadOrigins.all.collectAsState()
    val origins = fixed?.origins ?: liveOrigins
    var cacheBytes by remember { mutableLongStateOf(fixed?.storage?.first ?: 0L) }
    var freeBytes by remember { mutableLongStateOf(fixed?.storage?.second ?: 0L) }
    // The USB disk's free space, only when it is in play (docs/HOME_DISK.md §24.7).
    var usbFree by remember { mutableStateOf<Long?>(fixed?.usbFree) }
    LaunchedEffect(Unit) {
        while (fixed?.storage == null) {
            withContext(Dispatchers.IO) {
                cacheBytes = engine.cacheBytes()
                freeBytes = runCatching { StatFs(engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
                if (isTv) usbFree = ReelApp.get(context).downloadPlacer.usbFreeBytes()
            }
            delay(3000)
        }
    }
    val shown = com.kirtanya.reel.core.library.LibraryList.order(torrents.filter { it.matches(filter) }) { it.facts() }
    val shownJobs = if (filter == DownloadFilter.ALL || filter == DownloadFilter.HOME) home.jobs.sortedByDescending { it.createdAt } else emptyList()
    val heldShown = torrents.filter { it.infoHash.lowercase() in home.held }
    // "USB disk" / "This TV" lines show only once the disk is in play, so a TV without one looks as before.
    val anyUsb = isTv && (usbFree != null || torrents.any { it.onUsb })
    // Disk checks once, not on every recomposition (and some devices can't answer at all).
    val saveDir = remember { runCatching { engine.downloadsDir().path.substringAfter("/0/") }.getOrNull() }
    val needsFileAccess = remember { !isTv && Build.VERSION.SDK_INT >= 30 && runCatching { !Environment.isExternalStorageManager() }.getOrDefault(false) }
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        if (!isTv) AmbientGlow(Tokens.Colors.accent, strength = 0.28f)
        // TV: the page glides to the focused row over the shared glide time, not Compose's spring.
        val list: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.l else 0.dp, bottom = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.bottomBarClearance + Tokens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(if (isTv) Tokens.Space.xs else Tokens.Space.l),
        ) {
            if (isTv) item(key = "title", contentType = "title") {
                TvHeader(torrents, cacheBytes, freeBytes, usbFree) { engine.trimCache(0) }
            } else item(key = "title", contentType = "title") {
                Row(Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()).padding(top = Tokens.Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Downloads", style = if (isTv) Tokens.Type.hero else Tokens.Type.display, color = Tokens.Colors.text)
                        if (saveDir != null) Text("Saving to $saveDir", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isTv) GlassIconButton(Icons.Rounded.Add, "Add a link", onAdd)
                }
            }
            if (!isTv) item(key = "storage", contentType = "storage") { StorageCard(torrents, cacheBytes, freeBytes, usbFree, false) { engine.trimCache(0) } }
            if (needsFileAccess) {
                item(key = "access") {
                    ReelCard {
                        Text("Save to your Downloads folder", style = Tokens.Type.heading, color = Tokens.Colors.text)
                        Text("Allow file access so downloads land in Download/Reel, where other apps can open them.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                        ReelButton("Allow", {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }, icon = Icons.Rounded.Folder)
                    }
                }
            }
            if (!isTv && home.waiting != null && heldShown.isNotEmpty()) item(key = "held") { WaitingCard(home, heldShown.size) }
            item(key = "filters", contentType = "filters") {
                if (isTv) {
                    // The same text tabs as the top bar (the tab's own inset is taken back so the text lines up with the title).
                    Row(Modifier.offset(x = -Tokens.Space.m), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                        DownloadFilter.entries.filter { it != DownloadFilter.HOME || showHome }.forEach { f ->
                            val count = if (f == DownloadFilter.HOME) home.jobs.size else torrents.count { it.matches(f) }
                            com.kirtanya.reel.ui.tv.TvTab("${f.label} · $count", null, selected = filter == f) { filter = f }
                        }
                    }
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    items(DownloadFilter.entries.filter { it != DownloadFilter.HOME || showHome }) { f ->
                        val count = if (f == DownloadFilter.HOME) home.jobs.size else torrents.count { it.matches(f) }
                        ReelButton("${f.label} · $count", { filter = f }, kind = if (filter == f) ButtonKind.Primary else ButtonKind.Ghost)
                    }
                }
            }
            if (shownJobs.isNotEmpty()) {
                item(key = "home-header") { Text("At home · ${shownJobs.size}", style = Tokens.Type.heading, color = Tokens.Colors.text) }
                items(shownJobs, key = { "job:" + it.requestId }) { j -> AtHomeCard(j, home) }
            }
            if (shown.isEmpty() && shownJobs.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.Download,
                        if (filter == DownloadFilter.KEPT) "Nothing kept yet" else "Nothing here yet",
                        if (isTv) "What you play shows up here. Download from a title's sources to keep it."
                        else "Tap + to add a magnet link or a .torrent file, or download from a title's sources.",
                        action = if (!isTv) ({ ReelButton("Add a link", onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add) }) else null,
                    )
                }
            }
            if (shown.any { it.mode == TorrentMode.STREAM }) item(key = "explainer", contentType = "explainer") {
                Text(
                    com.kirtanya.reel.core.library.LibraryList.EXPLAINER, Modifier.widthIn(max = 980.dp),
                    style = Tokens.Type.caption, color = Tokens.Colors.textSecondary,
                )
            }
            items(shown, key = { it.infoHash }, contentType = { "title" }) { t ->
                val origin = origins[t.infoHash.lowercase()]
                DownloadCard(
                    t, onDelete = { confirmDelete = t }, onMore = { more = t }, home = home.takeIf { !isTv && t.mode == TorrentMode.DOWNLOAD }, tv = isTv, anyUsb = anyUsb,
                    onChangeSource = origin?.let { o -> { onOpenDest(sourcesDest(o, t.infoHash)) } }, origin = origin,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        }
        if (isTv) com.kirtanya.reel.ui.tv.TvColumnGlide(reveal = Tokens.Space.l, content = list) else list()
    }

    more?.let { t ->
        val origin = origins[t.infoHash.lowercase()]
        MoreDialog(
            t, canChangeSource = origin != null,
            onChangeSource = {
                more = null
                origin?.let { o -> onOpenDest(sourcesDest(o, t.infoHash)) }
            },
            onKeepAsCache = { more = null; confirmCache = t },
            onDismiss = { more = null },
            onGoToTitle = origin?.let { o ->
                {
                    more = null
                    val type = if (o.type == com.kirtanya.reel.core.tmdb.MediaType.TV.path) com.kirtanya.reel.core.tmdb.MediaType.TV else com.kirtanya.reel.core.tmdb.MediaType.MOVIE
                    onOpenDest(com.kirtanya.reel.ui.nav.Dest.Title(com.kirtanya.reel.core.tmdb.TitleRef(type, o.tmdbId)))
                }
            },
        )
    }

    confirmCache?.let { t ->
        val finished = t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING
        AlertDialog(
            onDismissRequest = { confirmCache = null },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Don't keep it?", style = Tokens.Type.heading, color = Tokens.Colors.text) },
            text = { Text(com.kirtanya.reel.core.library.LibraryList.unkeepConfirm(finished), style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = { ReelButton("Don't keep", { engine.keepAsCache(t.infoHash); confirmCache = null }, kind = ButtonKind.Primary, icon = Icons.Rounded.Theaters) },
            dismissButton = { ReelButton("Keep it", { confirmCache = null }, kind = ButtonKind.Ghost) },
        )
    }

    confirmDelete?.takeIf { !isTv && it.mode == TorrentMode.DOWNLOAD && !it.http }?.let { t ->
        // Delete-where (§14): until a disk is there, its choices show "Home disk not connected".
        com.kirtanya.reel.ui.homedisk.DeleteWhereDialog(
            com.kirtanya.reel.ui.sources.releaseName(t.name),
            com.kirtanya.reel.core.homedisk.DeleteWhere.choice(onPhone = true, disk = com.kirtanya.reel.core.homedisk.DiskPresence.UNKNOWN),
            onPick = { action ->
                if (action == com.kirtanya.reel.core.homedisk.DeleteAction.PHONE) {
                    engine.remove(t.infoHash, deleteFiles = true)
                    ReelApp.get(context).downloadOrigins.remove(t.infoHash)
                }
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmDelete?.takeIf { isTv || it.mode != TorrentMode.DOWNLOAD || it.http }?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Delete “${com.kirtanya.reel.ui.sources.releaseName(t.name)}”?", style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            text = { Text(if (t.http) "This removes the download and its file from this device." else "This removes the torrent and its downloaded files from this device.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = {
                ReelButton("Delete", {
                    if (t.http) {
                        ReelApp.get(context).httpDownloads.remove(t.infoHash)
                    } else {
                        engine.remove(t.infoHash, deleteFiles = true)
                        ReelApp.get(context).downloadOrigins.remove(t.infoHash)
                    }
                    confirmDelete = null
                }, kind = ButtonKind.Primary, icon = Icons.Rounded.Delete)
            },
            dismissButton = { ReelButton("Keep it", { confirmDelete = null }, kind = ButtonKind.Ghost) },
        )
    }
}

@Composable
private fun StorageCard(torrents: List<TorrentSnapshot>, cacheBytes: Long, freeBytes: Long, usbFree: Long?, isTv: Boolean, onClearCache: () -> Unit) {
    // The bar is the TV's own storage: downloads on the USB disk are counted on their own line.
    val downloaded = torrents.filter { it.mode == TorrentMode.DOWNLOAD && !it.onUsb }.sumOf { it.doneBytes }
    val onUsbBytes = torrents.filter { it.mode == TorrentMode.DOWNLOAD && it.onUsb }.sumOf { it.doneBytes }
    val used = downloaded + cacheBytes
    val total = (used + freeBytes).coerceAtLeast(1)
    if (isTv) {
        // One slim row: the number that matters, the bar, what fills it, and the one action.
        ReelCard(padding = Tokens.Space.l, spacing = 0.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xl)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Text(formatBytes(freeBytes), style = Tokens.Type.title, color = Tokens.Colors.text)
                        Text("free on this device", style = Tokens.Type.label, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(bottom = 1.dp))
                    }
                    GradientProgress(used.toFloat() / total, height = 4.dp)
                    Text("${formatBytes(downloaded)} kept  ·  ${formatBytes(cacheBytes)} not kept", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                    if (usbFree != null) Text("USB disk: ${formatBytes(usbFree)} free  ·  ${formatBytes(onUsbBytes)} kept", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                }
                if (cacheBytes > 0) ReelButton("Clear what isn't kept", onClearCache, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
            }
        }
        return
    }
    ReelCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Text(formatBytes(freeBytes), style = Tokens.Type.title, color = Tokens.Colors.text)
            Text("free on this device", style = Tokens.Type.label, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
        }
        GradientProgress(used.toFloat() / total, height = 6.dp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Pill("${formatBytes(downloaded)} kept")
            Pill("${formatBytes(cacheBytes)} not kept")
        }
        if (cacheBytes > 0) ReelButton("Clear what isn't kept", onClearCache, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
    }
}

@Composable
private fun DownloadCard(t: TorrentSnapshot, onDelete: () -> Unit, onMore: (() -> Unit)?, home: com.kirtanya.reel.ui.homedisk.HomeView? = null, tv: Boolean = false, anyUsb: Boolean = false, onChangeSource: (() -> Unit)? = null, origin: com.kirtanya.reel.core.library.DownloadOrigin? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val done = t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING
    if (tv) {
        DownloadRowTv(t, done, onDelete, onMore.takeIf { t.mode == TorrentMode.DOWNLOAD }, anyUsb, onChangeSource, origin, modifier)
        return
    }
    ReelCard(modifier.widthIn(max = 980.dp)) {
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
                Text(statusLine(t), style = Tokens.Type.caption, color = if (t.error != null && !t.notReady) Tokens.Colors.danger else Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!done && !t.notReady && t.mode == TorrentMode.DOWNLOAD) GradientProgress(t.progress)
        val held = home?.let { h -> h.waiting.takeIf { t.infoHash.lowercase() in h.held } }
        home?.let { h ->
            com.kirtanya.reel.core.homedisk.HomeBadges.badge(com.kirtanya.reel.core.homedisk.TitleWhere(phoneDone = done && held == null, held = held), h.setUp)
                ?.let { com.kirtanya.reel.ui.homedisk.HomeBadge(it) }
        }
        if (held != null) HeldActions(t, held, home)
        DownloadNotice(t)
        when {
            t.notReady -> NotReadyNotice(t, onChangeSource)
            t.error != null -> Text(t.error, style = Tokens.Type.caption, color = Tokens.Colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
            t.stalledForSec >= 120 -> Text("No one is sharing this right now. It'll continue when someone does.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        }
        // Space.m between the 40 dp icons: Compose grows each touch area to 48 dp only while the neighbours leave room.
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
            // A direct download can only be played once it is whole; before that the row has Pause / Resume instead.
            if (!t.http || done || httpReady(t)) PlayButton(t, done)
            Spacer(Modifier.weight(1f))
            // Icons, not labels, so Play + up to three actions fit a 360 dp phone; the rest are under More.
            if (t.mode == TorrentMode.STREAM) {
                // Keeps the pieces already fetched and downloads the rest (Keep, §7.3).
                ReelButton("Keep", {
                    engine.keep(t.infoHash)
                    android.widget.Toast.makeText(context, com.kirtanya.reel.core.library.LibraryList.KEEP_TOAST, android.widget.Toast.LENGTH_SHORT).show()
                }, kind = ButtonKind.Ghost, icon = Icons.Rounded.SaveAlt)
            } else if (!done && held != null) {
                // Held by the network rule: its own buttons above say how to start it.
            } else if (!done && !t.notReady) {
                if (t.phase == TorrentPhase.PAUSED || (t.http && t.phase == TorrentPhase.ERROR)) GlassIconButton(Icons.Rounded.Download, "Resume", { resumeDownload(context, t) })
                else GlassIconButton(Icons.Rounded.Pause, "Pause", { pauseDownload(context, t) })
            }
            GlassIconButton(Icons.Rounded.Delete, "Delete", onDelete)
            if (onMore != null && t.mode == TorrentMode.DOWNLOAD) GlassIconButton(Icons.Rounded.MoreVert, "More", onMore)
        }
    }
}

private fun sourcesDest(o: com.kirtanya.reel.core.library.DownloadOrigin, replacing: String): com.kirtanya.reel.ui.nav.Dest.Sources {
    val type = if (o.type == com.kirtanya.reel.core.tmdb.MediaType.TV.path) com.kirtanya.reel.core.tmdb.MediaType.TV else com.kirtanya.reel.core.tmdb.MediaType.MOVIE
    return com.kirtanya.reel.ui.nav.Dest.Sources(com.kirtanya.reel.core.tmdb.TitleRef(type, o.tmdbId), o.season, o.episode, download = true, replacing = replacing)
}

private fun pauseDownload(context: android.content.Context, t: TorrentSnapshot) {
    val app = ReelApp.get(context)
    if (t.http) app.httpDownloads.pause(t.infoHash) else app.engine.pause(t.infoHash)
}

private fun resumeDownload(context: android.content.Context, t: TorrentSnapshot) {
    val app = ReelApp.get(context)
    if (t.http) app.httpDownloads.resume(t.infoHash) else app.engine.resume(t.infoHash)
}

/** Play (finished) / Play now (still downloading, streamed from its torrent). A direct download plays its local file, on the disk or the TV. */
@Composable
private fun PlayButton(t: TorrentSnapshot, done: Boolean) {
    val context = LocalContext.current
    ReelButton(if (done || t.mode == TorrentMode.STREAM) "Play" else "Play now", {
        if (t.http) {
            val app = ReelApp.get(context)
            val d = app.httpDownloads.get(t.infoHash)
            // Still downloading: play the source itself while the download carries on (no play from a growing file yet).
            if (d != null) context.startActivity(if (done) PlayerActivity.forHttpDownload(context, d) else PlayerActivity.forUrl(context, d.url, d.name, d.headers))
        } else {
            com.kirtanya.reel.ui.homedisk.gatedPlay(context, null, t.name) { context.startActivity(PlayerActivity.forTorrent(context, t.infoHash, t.name)) }
        }
    }, kind = ButtonKind.Secondary, icon = Icons.Rounded.PlayArrow)
}

/** A direct download far enough along to play (the same rule as the title page, core/library/TitleDownload). */
private fun httpReady(t: TorrentSnapshot): Boolean =
    t.http && t.phase == TorrentPhase.DOWNLOADING && !t.notReady &&
        t.doneBytes >= minOf(com.kirtanya.reel.core.library.TitleDownload.HTTP_READY_BYTES, (t.wantedBytes / 2).takeIf { it > 0 } ?: Long.MAX_VALUE)

/**
 * The debrid service had not cached the file yet: nothing was kept (docs/HOME_DISK.md §25.4). Retry asks again; Change source opens the
 * title's sources (only when the download knows its film / episode).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NotReadyNotice(t: TorrentSnapshot, onChangeSource: (() -> Unit)?) {
    val context = LocalContext.current
    Text(t.error ?: "Not ready yet. Try again later or pick another source", style = Tokens.Type.caption, color = Tokens.Colors.warn, maxLines = 3, overflow = TextOverflow.Ellipsis)
    // A flow, not a row: at a large font size "Change source" wraps to its own line instead of being cut off.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        ReelButton("Retry", { resumeDownload(context, t) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
        if (onChangeSource != null) ReelButton("Change source", onChangeSource, kind = ButtonKind.Ghost, icon = Icons.Rounded.SwapHoriz)
    }
}

/** TV: the width of every row's button area (the widest set: Retry + Change source + Delete), so the text column is equally wide on every row. */
private val TV_ACTIONS_WIDTH = 312.dp
private val TV_THUMB_WIDTH = 112.dp

/**
 * TV header: the title on the left, storage on the right (free space, a thin bar, what fills it, and its one action), so the
 * list starts high on a 540 dp screen instead of under a big storage card.
 */
@Composable
private fun TvHeader(torrents: List<TorrentSnapshot>, cacheBytes: Long, freeBytes: Long, usbFree: Long?, onClearCache: () -> Unit) {
    val downloaded = torrents.filter { it.mode == TorrentMode.DOWNLOAD && !it.onUsb }.sumOf { it.doneBytes }
    val onUsbBytes = torrents.filter { it.mode == TorrentMode.DOWNLOAD && it.onUsb }.sumOf { it.doneBytes }
    val used = downloaded + cacheBytes
    val total = (used + freeBytes).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = Tokens.Space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xl)) {
        Text("Downloads", Modifier.weight(1f), style = Tokens.Type.display, color = Tokens.Colors.text)
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                Text(formatBytes(freeBytes), style = Tokens.Type.heading, color = Tokens.Colors.text)
                Text("free on this TV", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
            GradientProgress(used.toFloat() / total, height = 3.dp)
            Text("${formatBytes(downloaded)} kept  ·  ${formatBytes(cacheBytes)} not kept", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
            if (usbFree != null) Text("USB disk: ${formatBytes(usbFree)} free  ·  ${formatBytes(onUsbBytes)} kept", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
        }
        if (cacheBytes > 0) ReelButton("Clear what isn't kept", onClearCache, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
    }
}

/**
 * TV: one flat row per download, like a TV list rather than a stack of phone cards: the title's picture, then name, status and a
 * thin progress line, then every action in one fixed-width group at the right. Notices (not ready, waiting for night, nobody
 * sharing, an error) are a line of text in the middle and their buttons join the group, so nothing focusable hangs under a row
 * and up / down always goes from row to row. The row lights up while any of its buttons has focus.
 */
@Composable
private fun DownloadRowTv(
    t: TorrentSnapshot, done: Boolean, onDelete: () -> Unit, onMore: (() -> Unit)?, anyUsb: Boolean = false,
    onChangeSource: (() -> Unit)? = null, origin: com.kirtanya.reel.core.library.DownloadOrigin? = null, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val shape = RoundedCornerShape(Tokens.Radius.l)
    var focused by remember { mutableStateOf(false) }
    val bg by androidx.compose.animation.animateColorAsState(
        if (focused) Tokens.Colors.surface1 else Tokens.Colors.canvas,
        androidx.compose.animation.core.tween(Tokens.Motion.focusFadeMs, easing = Tokens.Motion.easing), label = "row",
    )
    val notice: Pair<String, androidx.compose.ui.graphics.Color>? = when {
        t.notReady -> (t.error ?: "Not ready yet. Try again later or pick another source") to Tokens.Colors.warn
        t.error != null -> t.error to Tokens.Colors.danger
        t.notice != null -> t.notice to Tokens.Colors.textSecondary
        t.stalledForSec >= 120 -> "No one is sharing this right now. It'll continue when someone does." to Tokens.Colors.warn
        else -> null
    }
    Row(
        modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.clip(shape).background(bg).padding(Tokens.Space.m),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l), verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadThumb(t, origin)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            val release = com.kirtanya.reel.ui.sources.releaseName(t.name)
            Text(origin?.label ?: release, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (origin != null) Text(release, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!t.notReady && t.error == null) Text(
                statusLine(t, if (!anyUsb || t.mode != TorrentMode.DOWNLOAD) null else if (t.onUsb) "USB disk" else "This TV"),
                style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            notice?.let { (text, color) -> Text(text, style = Tokens.Type.caption, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (!done && !t.notReady && t.mode == TorrentMode.DOWNLOAD) GradientProgress(t.progress, Modifier.padding(top = Tokens.Space.xs), height = 3.dp)
        }
        // Fixed width, packed to the right: every row's text column is the same width and every row's buttons end at the far right.
        Row(Modifier.width(TV_ACTIONS_WIDTH), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            when {
                t.notReady -> {
                    ReelButton("Retry", { resumeDownload(context, t) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
                    if (onChangeSource != null) ReelButton("Change source", onChangeSource, kind = ButtonKind.Ghost, icon = Icons.Rounded.SwapHoriz)
                }
                // Waiting for the night window: starting it is the point of the row (Play now would start it anyway).
                t.waitingForNight -> ReelButton("Start now", { app.engine.startNow(t.infoHash) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
                else -> {
                    if (!t.http || done || httpReady(t)) PlayButton(t, done)
                    if (t.mode == TorrentMode.STREAM) {
                        ReelButton("Keep", {
                            app.engine.keep(t.infoHash)
                            android.widget.Toast.makeText(context, com.kirtanya.reel.core.library.LibraryList.KEEP_TOAST, android.widget.Toast.LENGTH_SHORT).show()
                        }, kind = ButtonKind.Ghost, icon = Icons.Rounded.SaveAlt)
                    } else if (!done) {
                        if (t.phase == TorrentPhase.PAUSED || (t.http && t.phase == TorrentPhase.ERROR)) GlassIconButton(Icons.Rounded.Download, "Resume", { resumeDownload(context, t) })
                        else GlassIconButton(Icons.Rounded.Pause, "Pause", { pauseDownload(context, t) })
                    }
                }
            }
            GlassIconButton(Icons.Rounded.Delete, "Delete", onDelete)
            if (onMore != null && !t.notReady) GlassIconButton(Icons.Rounded.MoreVert, "More", onMore)
        }
    }
}

/**
 * The title's picture (16:9): the episode's still for an episode, else the film / show backdrop, from TMDB (cached in memory by
 * the repository, on disk by the image loader). Without a known title (a pasted link) it is the initial on a tile.
 */
@Composable
private fun DownloadThumb(t: TorrentSnapshot, origin: com.kirtanya.reel.core.library.DownloadOrigin?) {
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val context = LocalContext.current
    val url by androidx.compose.runtime.produceState<String?>(fixed?.downloadArt?.let { f -> origin?.let(f) }, origin) {
        if (fixed != null || origin == null) return@produceState
        val tmdb = ReelApp.get(context).tmdb
        val isShow = origin.type == com.kirtanya.reel.core.tmdb.MediaType.TV.path
        value = runCatching {
            val season = origin.season
            val still = if (isShow && season != null && origin.episode != null)
                tmdb.season(origin.tmdbId, season).firstOrNull { it.number == origin.episode }?.stillPath else null
            still?.let { com.kirtanya.reel.core.tmdb.TmdbImages.still(it, "w300") } ?: run {
                val type = if (isShow) com.kirtanya.reel.core.tmdb.MediaType.TV else com.kirtanya.reel.core.tmdb.MediaType.MOVIE
                com.kirtanya.reel.core.tmdb.TmdbImages.backdrop(tmdb.details(com.kirtanya.reel.core.tmdb.TitleRef(type, origin.tmdbId)).summary.backdropPath, "w300")
            }
        }.getOrNull()
    }
    val shape = RoundedCornerShape(Tokens.Radius.m)
    Box(
        Modifier.width(TV_THUMB_WIDTH).aspectRatio(16f / 9f).clip(shape).background(Tokens.Colors.surface3)
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) com.kirtanya.reel.ui.common.RemoteImage(url, Modifier.matchParentSize())
        else if (t.mode == TorrentMode.STREAM) androidx.compose.material3.Icon(Icons.Rounded.Theaters, null, tint = Tokens.Colors.textSecondary)
        else Text(t.name.trim().take(1).uppercase(), style = Tokens.Type.title, color = Tokens.Colors.textSecondary)
    }
}

/** One rule for both the filter's list and its count (errors show under Downloading). */
private fun TorrentSnapshot.matches(f: DownloadFilter): Boolean = com.kirtanya.reel.core.library.LibraryList.matches(facts(), f)

private fun statusLine(t: TorrentSnapshot, place: String? = null): String = buildString {
    if (t.http) {
        // A direct download: no peers, no metadata step. "42% · 1.2 GB · USB disk · ↓ 8 MB/s · 3 min left".
        append(
            when {
                t.notReady -> "Not ready yet"
                t.phase == TorrentPhase.ERROR -> "Stopped"
                t.phase == TorrentPhase.FINISHED -> "Downloaded"
                t.phase == TorrentPhase.PAUSED -> "Paused · ${(t.progress * 100).toInt()}%"
                else -> "${(t.progress * 100).toInt()}%"
            },
        )
        if (t.wantedBytes > 0 && !t.notReady) append(" · ").append(formatBytes(t.wantedBytes))
        if (place != null) append(" · ").append(place)
        if (t.phase == TorrentPhase.DOWNLOADING && t.downBps > 0) {
            append(" · ↓ ").append(formatBytes(t.downBps.toLong())).append("/s")
            t.etaSec?.let { append(" · ").append(formatEta(it)) }
        }
        return@buildString
    }
    if (t.mode == TorrentMode.STREAM && !t.streaming) {
        append(com.kirtanya.reel.core.library.LibraryList.notKeptLine(if (t.hasMetadata) formatBytes(t.doneBytes) else null))
        return@buildString
    }
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
    if (place != null) append(" · ").append(place)
    if (t.phase == TorrentPhase.DOWNLOADING || t.phase == TorrentPhase.METADATA) {
        append(" · ↓ ").append(formatBytes(t.downBps.toLong())).append("/s · ").append(com.kirtanya.reel.ui.plural(t.peers, "peer"))
        t.etaSec?.let { append(" · ").append(formatEta(it)) }
    }
}

/** A download's other actions, as a dialog of buttons (reachable with the remote too). */
@Composable
internal fun MoreDialog(
    t: TorrentSnapshot,
    canChangeSource: Boolean,
    onChangeSource: () -> Unit,
    onKeepAsCache: () -> Unit,
    onDismiss: () -> Unit,
    /** Downloads started from a title's sources know their film / show: open its page. */
    onGoToTitle: (() -> Unit)? = null,
) {
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
                if (!t.http && t.mode == TorrentMode.DOWNLOAD) ReelButton("Don't keep", onKeepAsCache, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Theaters)
                onGoToTitle?.let { ReelButton("Go to title", it, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Info) }
            }
        },
        confirmButton = { ReelButton("Close", onDismiss, kind = ButtonKind.Ghost) },
    )
}

/** Downloads wait on this network: say why, and ask once for a new Wi-Fi (docs/HOME_DISK.md §5.3). */
@Composable
private fun WaitingCard(home: com.kirtanya.reel.ui.homedisk.HomeView, count: Int) {
    val context = LocalContext.current
    val gate = ReelApp.get(context).home.gate
    val reason = home.waiting ?: return
    ReelCard(Modifier.widthIn(max = 980.dp)) {
        Text(
            com.kirtanya.reel.ui.plural(count, "download") + " " + if (count == 1) "is waiting" else "are waiting",
            style = Tokens.Type.heading, color = Tokens.Colors.text,
        )
        Text(
            when (reason) {
                com.kirtanya.reel.core.homedisk.WaitReason.MOBILE_DATA -> "You're on mobile data: downloads wait for Wi-Fi. Start one anyway from its card."
                com.kirtanya.reel.core.homedisk.WaitReason.WORK_NETWORK -> "This Wi-Fi is a work network: no torrents here. They continue at home."
                com.kirtanya.reel.core.homedisk.WaitReason.UNKNOWN_WIFI -> "This Wi-Fi is new. Allow downloads here? Never allow this on a work network."
                com.kirtanya.reel.core.homedisk.WaitReason.NO_NETWORK -> "No connection: they continue when you're back online."
            },
            style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
        )
        if (reason == com.kirtanya.reel.core.homedisk.WaitReason.UNKNOWN_WIFI) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                ReelButton("Allow this Wi-Fi", { gate.answer(true) }, kind = ButtonKind.Primary)
                ReelButton("Never here (work)", { gate.answer(false) }, kind = ButtonKind.Ghost)
            }
        }
    }
}

/** A held download's ways forward: Start now anyway (mobile data only) or Download at home instead. */
@Composable
private fun HeldActions(t: TorrentSnapshot, reason: com.kirtanya.reel.core.homedisk.WaitReason, home: com.kirtanya.reel.ui.homedisk.HomeView) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val origins by app.downloadOrigins.all.collectAsState()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        if (reason == com.kirtanya.reel.core.homedisk.WaitReason.MOBILE_DATA) {
            ReelButton("Start now anyway", { app.home.gate.startAnyway(t.infoHash) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
        }
        if (home.setUp && home.tvPaired) {
            ReelButton("Download at home instead", {
                val label = origins[t.infoHash.lowercase()]?.label ?: com.kirtanya.reel.ui.sources.releaseName(t.name)
                com.kirtanya.reel.ui.homedisk.downloadAtHome(context, "magnet:?xt=urn:btih:${t.infoHash}", t.fileIndex, null, label, origins[t.infoHash.lowercase()])
            }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Home)
        }
    }
}

/** One "Download at home" request: where it stands, Cancel while it runs, Remove once it's over. */
@Composable
private fun AtHomeCard(j: com.kirtanya.reel.homedisk.PhoneJob, home: com.kirtanya.reel.ui.homedisk.HomeView) {
    val client = ReelApp.get(LocalContext.current).home.client
    val where = com.kirtanya.reel.core.homedisk.TitleWhere(waitingForTv = j.state == null, atHome = j.state, atHomePercent = j.percent, queuePos = j.queuePos)
    val badge = com.kirtanya.reel.core.homedisk.HomeBadges.badge(where, home.setUp)
    val over = j.state in com.kirtanya.reel.homedisk.HomeClient.DONE_STATES
    ReelCard(Modifier.widthIn(max = 980.dp)) {
        com.kirtanya.reel.ui.homedisk.AtHomeRow(j.label, badge, if (over) "Remove" else "Cancel", { if (over) client.forget(j) else client.cancel(j) })
        j.note?.takeIf { j.state != com.kirtanya.reel.core.sync.JobState.WAITING_DISK }?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary) }
    }
}

