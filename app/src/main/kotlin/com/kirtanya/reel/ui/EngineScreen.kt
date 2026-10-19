package com.kirtanya.reel.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import com.kirtanya.reel.player.PlayerActivity
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M1–M2 test screen: add magnets, stream or download them, watch the engine work.
 * Works with touch (phone) and the D-pad (TV). Replaced by the real Home in M3.
 */
@Composable
fun EngineScreen(isTv: Boolean, incomingMagnet: String?, onIncomingHandled: () -> Unit, dialogOnly: Boolean = false) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val torrents by engine.torrents.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var magnet by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            cacheBytes = engine.cacheBytes()
            delay(3000)
        }
    }

    fun stream(link: String, title: String? = null) {
        context.startActivity(PlayerActivity.forMagnet(context, link, title))
    }

    fun download(link: String) {
        scope.launch {
            message = try {
                engine.addMagnet(link, TorrentMode.DOWNLOAD)
                "Added to downloads"
            } catch (e: EngineException) {
                e.message
            }
        }
    }

    incomingMagnet?.let { link ->
        IncomingDialog(link, onStream = { stream(link); onIncomingHandled() }, onDownload = { download(link); onIncomingHandled() }, onDismiss = onIncomingHandled)
    }

    if (dialogOnly) return

    val pad = if (isTv) PaddingValues(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV)
    else PaddingValues(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.xl)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Tokens.Colors.bg),
        contentPadding = pad,
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
    ) {
        item {
            Column {
                Text("Downloads", style = Tokens.Type.display, color = Tokens.Colors.text)
                Text(
                    "Stream cache ${formatBytes(cacheBytes)} · saving to ${engine.downloadsDir().path.substringAfterLast("/0/")}",
                    style = Tokens.Type.caption,
                    color = Tokens.Colors.textMuted,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                OutlinedTextField(
                    value = magnet,
                    onValueChange = { magnet = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Paste a magnet link") },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    ReelButton("Stream", { stream(magnet) }, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, enabled = magnet.isNotBlank())
                    ReelButton("Download", { download(magnet) }, icon = Icons.Rounded.Download, enabled = magnet.isNotBlank())
                    if (!isTv) {
                        ReelButton("Paste", { scope.launch { magnet = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString().orEmpty() } }, kind = ButtonKind.Ghost, icon = Icons.Rounded.ContentPaste)
                    }
                }
                message?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textMuted) }
                if (!isTv && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    ReelButton(
                        "Allow saving to the Downloads folder",
                        {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                            )
                        },
                        kind = ButtonKind.Ghost,
                        icon = Icons.Rounded.SaveAlt,
                    )
                }
            }
        }

        item { Text("Test videos (open movies)", style = Tokens.Type.heading, color = Tokens.Colors.text) }
        items(SAMPLE_TORRENTS) { sample ->
            Card {
                Text(sample.title, style = Tokens.Type.body, color = Tokens.Colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    ReelButton("Stream", { stream(sample.magnet, sample.title) }, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
                    ReelButton("Download", { download(sample.magnet) }, icon = Icons.Rounded.Download)
                }
            }
        }

        item { Text("Torrents (${torrents.size})", style = Tokens.Type.heading, color = Tokens.Colors.text) }
        if (torrents.isEmpty()) {
            item { Text("Nothing yet. Stream or download something above.", style = Tokens.Type.body, color = Tokens.Colors.textMuted) }
        }
        items(torrents, key = { it.infoHash }) { t -> TorrentRow(t) }
        item { Spacer(Modifier.height(Tokens.Space.xxl)) }
    }
}

@Composable
private fun TorrentRow(t: TorrentSnapshot) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    Card {
        Text(t.name, style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 2)
        val modeLabel = if (t.mode == TorrentMode.STREAM) "Stream cache" else "Download"
        val stats = buildString {
            append(modeLabel).append(" · ").append(phaseLabel(t.phase))
            if (t.hasMetadata) append(" · ").append(formatBytes(t.doneBytes)).append(" of ").append(formatBytes(t.wantedBytes))
            if (t.phase == TorrentPhase.DOWNLOADING || t.phase == TorrentPhase.METADATA) {
                append(" · ↓ ").append(formatBytes(t.downBps.toLong())).append("/s · ").append(t.peers).append(" peers")
                if (t.etaSec != null) append(" · ").append(formatEta(t.etaSec))
            }
        }
        Text(stats, style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
        LinearProgressIndicator(
            progress = { t.progress },
            modifier = Modifier.fillMaxWidth().height(Tokens.Size.progressBar).clip(RoundedCornerShape(Tokens.Radius.pill)),
            color = Tokens.Colors.accent,
            trackColor = Tokens.Colors.surfaceHigh,
        )
        when {
            t.error != null -> Text(t.error, style = Tokens.Type.caption, color = Tokens.Colors.danger)
            t.stalledForSec >= 120 -> Text("No one is sharing this right now. Try another source.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            ReelButton("Play", { context.startActivity(PlayerActivity.forTorrent(context, t.infoHash, t.name)) }, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
            if (t.mode == TorrentMode.STREAM) ReelButton("Keep", { engine.keep(t.infoHash) }, icon = Icons.Rounded.SaveAlt)
            if (t.phase == TorrentPhase.PAUSED) {
                ReelButton("Resume", { engine.resume(t.infoHash) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Download)
            } else {
                ReelButton("Pause", { engine.pause(t.infoHash) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Pause)
            }
            ReelButton("Delete", { engine.remove(t.infoHash, deleteFiles = true) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
        }
    }
}

private fun phaseLabel(phase: TorrentPhase) = when (phase) {
    TorrentPhase.METADATA -> "Finding peers"
    TorrentPhase.CHECKING -> "Checking"
    TorrentPhase.DOWNLOADING -> "Downloading"
    TorrentPhase.PAUSED -> "Paused"
    TorrentPhase.FINISHED -> "Done"
    TorrentPhase.SEEDING -> "Done · sharing"
    TorrentPhase.ERROR -> "Error"
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.l))
            .background(Tokens.Colors.surface)
            .padding(Tokens.Space.l),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) { content() }
}

@Composable
private fun IncomingDialog(link: String, onStream: () -> Unit, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val name = com.kirtanya.reel.core.magnet.Magnet.parse(link)?.name ?: "This torrent"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface,
        title = { Text(name, style = Tokens.Type.heading, color = Tokens.Colors.text) },
        text = { Text("Stream it now, or download and keep it?", style = Tokens.Type.body, color = Tokens.Colors.textMuted) },
        confirmButton = { ReelButton("Stream", onStream, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow) },
        dismissButton = { ReelButton("Download", onDownload, icon = Icons.Rounded.Download) },
    )
}
