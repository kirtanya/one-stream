package com.kirtanya.reel.ui.sources

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.library.SourceMark
import com.kirtanya.reel.core.library.SourceMarks
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.SourceView
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * A source's long-press menu (touch) / hold OK (remote): play, download, stream only, favourite,
 * star, share. One column of full-width buttons, so the remote moves straight down it.
 */
@Composable
fun SourceOptionsDialog(
    v: SourceView,
    req: SourceRequest,
    mark: SourceMark?,
    label: String,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    /** Phone with a paired TV remote: wake the TV and play this source there. */
    onPlayOnTv: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val s = v.source
    val marks = ReelApp.get(context).sourceMarks
    val favourite = mark?.favourite == true
    val starred = mark?.starred == true
    val first = remember { FocusRequester() }
    fun then(block: () -> Unit) = { block(); onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = {
            Text(
                listOf(if (s.kind == SourceKind.EXTERNAL) "Service" else s.quality.label, v.provider).joinToString(" · "),
                style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text(releaseName(s.title), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val wide = Modifier.fillMaxWidth()
                if (s.kind == SourceKind.EXTERNAL) {
                    ReelButton("Open", then(onPlay), wide.focusRequester(first), kind = ButtonKind.Primary, icon = Icons.Rounded.OpenInNew)
                } else {
                    ReelButton("Play", then(onPlay), wide.focusRequester(first), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
                }
                if (onPlayOnTv != null) {
                    ReelButton("Play on TV", then(onPlayOnTv), wide, kind = ButtonKind.Secondary, icon = Icons.Rounded.Tv)
                }
                if (LauncherPage.downloadable(s)) {
                    ReelButton("Download file", then(onDownload), wide, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
                }
                if (s.kind == SourceKind.TORRENT) {
                    ReelButton("Stream only (not saved)", then { streamOnly(context, s, label) }, wide, kind = ButtonKind.Secondary, icon = Icons.Rounded.Bolt)
                }
                if (s.kind != SourceKind.EXTERNAL && !s.isExtra) {
                    ReelButton(
                        if (favourite) "Remove favourite" else "Make favourite",
                        then { marks.setFavourite(req, s, !favourite); toast(context, if (favourite) "Removed from favourite" else "Favourite: it'll be picked first for this") },
                        wide, kind = ButtonKind.Ghost, icon = if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    )
                }
                ReelButton(
                    if (starred) "Unstar" else "Star",
                    then { marks.setStarred(req, s, !starred) },
                    wide, kind = ButtonKind.Ghost, icon = if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                )
                SourceMarks.shareText(s)?.let { text ->
                    ReelButton("Share link", then { share(context, text, label) }, wide, kind = ButtonKind.Ghost, icon = Icons.Rounded.Share)
                }
            }
        },
        confirmButton = {},
        dismissButton = { ReelButton("Close", onDismiss, kind = ButtonKind.Ghost) },
    )
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * Gets a torrent ready to play without opening the player: the player's own start (stream mode,
 * first pieces first, kept in the stream cache, not in Downloads). Nobody plays it → it stops by
 * itself after a while, like the launcher's "prepare". Follows the network rule like Play.
 */
private fun streamOnly(context: Context, s: Source, label: String) {
    val app = ReelApp.get(context)
    val magnet = s.magnetUri() ?: return toast(context, "This source has no magnet link")
    com.kirtanya.reel.ui.homedisk.gatedPlay(context, s, label) {
        app.appScope.launch {
            runCatching {
                val info = app.engine.startStream(magnet, s.fileIdx, s.filename)
                app.engine.stopWhenIdle(info.infoHash, info, STREAM_ONLY_IDLE_MS)
            }.onFailure { Log.w("ReelSources", "stream only failed: ${it.message}") }
        }
        toast(context, "Getting it ready to stream. Press Play within 20 min; it isn't kept in Downloads.")
    }
}

private fun share(context: Context, text: String, label: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, label)
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(context, "No app can share this") }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private const val STREAM_ONLY_IDLE_MS = 20 * 60_000L
