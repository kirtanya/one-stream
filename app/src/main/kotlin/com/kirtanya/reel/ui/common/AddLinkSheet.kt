package com.kirtanya.reel.ui.common

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.player.PlayerActivity
import com.kirtanya.reel.ui.SAMPLE_TORRENTS
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "+" in the bottom bar, and links opened from a browser: paste a magnet or pick a
 * `.torrent` file, then Stream it now or Download it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkSheet(initialLink: String?, onDismiss: () -> Unit, onDownloadStarted: () -> Unit) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val magnet = Magnet.parse(link)
    val ready = magnet != null || file != null

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "torrent"
                    name to context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }.getOrNull()
            }
            if (picked == null) Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            else {
                file = picked
                link = ""
            }
        }
    }

    fun go(mode: TorrentMode) {
        busy = true
        scope.launch {
            try {
                val f = file
                when {
                    mode == TorrentMode.STREAM && f == null -> context.startActivity(PlayerActivity.forMagnet(context, link.trim(), magnet?.name))
                    mode == TorrentMode.STREAM && f != null -> {
                        val hash = app.engine.addTorrentFile(f.second, TorrentMode.STREAM)
                        context.startActivity(PlayerActivity.forTorrent(context, hash, f.first.removeSuffix(".torrent")))
                    }
                    f != null -> app.engine.addTorrentFile(f.second, TorrentMode.DOWNLOAD).also { onDownloadStarted() }
                    else -> app.engine.addMagnet(link.trim(), TorrentMode.DOWNLOAD).also { onDownloadStarted() }
                }
                onDismiss()
            } catch (e: EngineException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Colors.surface1,
        shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl),
        dragHandle = { Box(Modifier.padding(top = Tokens.Space.m).size(width = 40.dp, height = 4.dp)) { GradientProgress(1f, height = 4.dp) } },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                Text("Add a link", style = Tokens.Type.title, color = Tokens.Colors.text)
                Text("Paste a magnet link, or open a .torrent file.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
            }
            if (file != null) {
                Pill(file!!.first, icon = Icons.Rounded.FileOpen, accent = true)
            } else {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.m),
                    leadingIcon = { Icon(Icons.Rounded.Link, null, tint = Tokens.Colors.textSecondary) },
                    trailingIcon = {
                        GlassIconButton(Icons.Rounded.ContentPaste, "Paste", onClick = {
                            scope.launch { link = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString().orEmpty() }
                        }, modifier = Modifier.padding(end = Tokens.Space.xs))
                    },
                    placeholder = { Text("magnet:?xt=urn:btih:…") },
                    supportingText = { if (link.isNotBlank() && magnet == null) Text("That doesn't look like a magnet link", color = Tokens.Colors.warn) else magnet?.name?.let { Text(it, color = Tokens.Colors.textSecondary) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Tokens.Colors.surface2,
                        unfocusedContainerColor = Tokens.Colors.surface2,
                        focusedBorderColor = Tokens.Colors.accentBright,
                        unfocusedBorderColor = Tokens.Colors.hairlineStrong,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                ReelButton("Stream now", { go(TorrentMode.STREAM) }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, enabled = ready && !busy, large = true)
                ReelButton("Download", { go(TorrentMode.DOWNLOAD) }, Modifier.weight(1f), kind = ButtonKind.Ghost, icon = Icons.Rounded.Download, enabled = ready && !busy, large = true)
            }
            ReelButton(
                "Open a .torrent file",
                { pickFile.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                icon = Icons.Rounded.FileOpen,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text("Try a free open movie", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SAMPLE_TORRENTS.forEach { s ->
                        ReelButton(s.title, { file = null; link = s.magnet }, kind = ButtonKind.Ghost)
                    }
                }
            }
        }
    }
}

