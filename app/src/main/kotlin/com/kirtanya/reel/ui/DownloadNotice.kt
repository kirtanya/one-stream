package com.kirtanya.reel.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.TorrentSnapshot
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Why the engine holds or slows a download (docs/STREAMING_V2.md §8.3): "Waiting for night (01:00)" with
 * Start now, or "Slowed while you watch". Nothing when the engine has nothing to say.
 */
@Composable
internal fun DownloadNotice(t: TorrentSnapshot) {
    val notice = t.notice ?: return
    val engine = ReelApp.get(LocalContext.current).engine
    Text(notice, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (t.waitingForNight) ReelButton("Start now", { engine.startNow(t.infoHash) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
}
