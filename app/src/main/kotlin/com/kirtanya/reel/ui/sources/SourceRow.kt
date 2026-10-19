package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens

/**
 * One source: quality badge, release text, provider · size · seeders. OK / tap = play.
 * The download button only appears for torrents and direct links.
 */
@Composable
fun SourceRow(
    source: Source,
    fits: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    playFocus: FocusRequester? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                .focusCard(onClick = onPlay)
                .background(Tokens.Colors.surface)
                .padding(Tokens.Space.m),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(source)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                Text(source.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(metaLine(source), style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!fits) Text("Too big to stream on this device", style = Tokens.Type.caption, color = Tokens.Colors.warn)
                if (source.kind == SourceKind.TORRENT && source.seeders == 0) {
                    Text("No one is sharing this right now", style = Tokens.Type.caption, color = Tokens.Colors.warn)
                }
            }
        }
        if (source.kind != SourceKind.EXTERNAL) {
            ReelButton("", onDownload, kind = ButtonKind.Ghost, icon = Icons.Rounded.Download)
        }
    }
}

@Composable
private fun Badge(source: Source) {
    val (bg, fg) = when {
        source.kind == SourceKind.EXTERNAL -> Tokens.Colors.surfaceHigh to Tokens.Colors.textMuted
        (source.quality.resolution ?: 0) >= 1080 -> Tokens.Colors.accent to Tokens.Colors.onAccent
        else -> Tokens.Colors.surfaceHigh to Tokens.Colors.text
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 56.dp)) {
        Text(
            if (source.kind == SourceKind.EXTERNAL) "App" else source.quality.label,
            style = Tokens.Type.caption,
            color = fg,
            modifier = Modifier.clip(RoundedCornerShape(Tokens.Radius.s)).background(bg).padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xxs),
        )
        Text(
            when (source.kind) {
                SourceKind.TORRENT -> "P2P"
                SourceKind.DIRECT -> "Direct"
                SourceKind.EXTERNAL -> "Link"
            },
            style = Tokens.Type.caption,
            color = Tokens.Colors.textMuted,
        )
    }
}

private fun metaLine(s: Source) = listOfNotNull(
    s.provider,
    s.sizeBytes?.let { formatBytes(it) },
    s.seeders?.let { "$it seeders" },
).joinToString(" · ")
