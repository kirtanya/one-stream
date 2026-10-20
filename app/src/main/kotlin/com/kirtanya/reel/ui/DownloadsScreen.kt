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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
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

/** Downloads and the stream cache: storage at a glance, each torrent as a card with glass actions. */
@Composable
fun DownloadsScreen(isTv: Boolean, onAdd: () -> Unit) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val torrents by engine.torrents.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var confirmDelete by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var freeBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                cacheBytes = engine.cacheBytes()
                freeBytes = runCatching { StatFs(engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
            }
            delay(3000)
        }
    }
    val shown = torrents.filter {
        when (filter) {
            DownloadFilter.All -> true
            DownloadFilter.Active -> it.phase in setOf(TorrentPhase.DOWNLOADING, TorrentPhase.METADATA, TorrentPhase.CHECKING, TorrentPhase.PAUSED) && it.mode == TorrentMode.DOWNLOAD
            DownloadFilter.Done -> it.mode == TorrentMode.DOWNLOAD && it.phase in setOf(TorrentPhase.FINISHED, TorrentPhase.SEEDING)
            DownloadFilter.Streams -> it.mode == TorrentMode.STREAM
        }
    }
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
                        Text("Saving to ${engine.downloadsDir().path.substringAfter("/0/")}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isTv) GlassIconButton(Icons.Rounded.Add, "Add a link", onAdd)
                }
            }
            item { StorageCard(torrents, cacheBytes, freeBytes) { engine.trimCache(0) } }
            if (!isTv && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
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
                        val count = torrents.count { t ->
                            when (f) {
                                DownloadFilter.All -> true
                                DownloadFilter.Active -> t.mode == TorrentMode.DOWNLOAD && t.phase !in setOf(TorrentPhase.FINISHED, TorrentPhase.SEEDING)
                                DownloadFilter.Done -> t.mode == TorrentMode.DOWNLOAD && t.phase in setOf(TorrentPhase.FINISHED, TorrentPhase.SEEDING)
                                DownloadFilter.Streams -> t.mode == TorrentMode.STREAM
                            }
                        }
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
            items(shown, key = { it.infoHash }) { t -> DownloadCard(t, onDelete = { confirmDelete = t }) }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Delete “${t.name}”?", style = Tokens.Type.heading, color = Tokens.Colors.text) },
            text = { Text("This removes the torrent and its downloaded files from this device.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = { ReelButton("Delete", { engine.remove(t.infoHash, deleteFiles = true); confirmDelete = null }, kind = ButtonKind.Primary, icon = Icons.Rounded.Delete) },
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
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Pill("${formatBytes(downloaded)} downloads")
            Pill("${formatBytes(cacheBytes)} stream cache")
        }
        if (cacheBytes > 0) ReelButton("Clear stream cache", onClearCache, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
    }
}

@Composable
private fun DownloadCard(t: TorrentSnapshot, onDelete: () -> Unit) {
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
                Text(t.name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(statusLine(t), style = Tokens.Type.caption, color = if (t.error != null) Tokens.Colors.danger else Tokens.Colors.textSecondary, maxLines = 1)
            }
        }
        if (!done) GradientProgress(t.progress)
        when {
            t.error != null -> Text(t.error, style = Tokens.Type.caption, color = Tokens.Colors.danger)
            t.stalledForSec >= 120 -> Text("No one is sharing this right now. It'll continue when someone does.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
            ReelButton(if (done) "Play" else "Play now", { context.startActivity(PlayerActivity.forTorrent(context, t.infoHash, t.name)) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.PlayArrow)
            if (t.mode == TorrentMode.STREAM) ReelButton("Keep", { engine.keep(t.infoHash) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.SaveAlt)
            Spacer(Modifier.weight(1f))
            if (!done) {
                if (t.phase == TorrentPhase.PAUSED) GlassIconButton(Icons.Rounded.Download, "Resume", { engine.resume(t.infoHash) })
                else GlassIconButton(Icons.Rounded.Pause, "Pause", { engine.pause(t.infoHash) })
            }
            GlassIconButton(Icons.Rounded.Delete, "Delete", onDelete)
        }
    }
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
        append(" · ↓ ").append(formatBytes(t.downBps.toLong())).append("/s · ").append(t.peers).append(" peers")
        t.etaSec?.let { append(" · ").append(formatEta(it)) }
    }
}

