package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens

/**
 * One source as a card: quality badge, release text, provider · size · seeders.
 * Tap / OK plays; the glass button downloads (torrents and direct links).
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
    val shape = RoundedCornerShape(Tokens.Radius.media)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                .focusCard(shape = shape, scale = 1.03f, onClick = onPlay)
                .background(Tokens.Colors.surface1)
                .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
                .padding(Tokens.Space.m),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.widthIn(min = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                Pill(if (source.kind == SourceKind.EXTERNAL) "App" else source.quality.label, accent = (source.quality.resolution ?: 0) >= 1080)
                Text(
                    when (source.kind) {
                        SourceKind.TORRENT -> "P2P"
                        SourceKind.DIRECT -> "Direct"
                        SourceKind.EXTERNAL -> "Link"
                    },
                    style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, textAlign = TextAlign.Center,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                Text(releaseName(source.title), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metaLine(source), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!fits) Text("Too big to stream on this device", style = Tokens.Type.caption, color = Tokens.Colors.warn)
                if (source.kind == SourceKind.TORRENT && source.seeders == 0) Text("No one is sharing this right now", style = Tokens.Type.caption, color = Tokens.Colors.warn)
            }
        }
        if (source.kind == SourceKind.EXTERNAL) GlassIconButton(Icons.Rounded.OpenInNew, "Open", onPlay)
        else GlassIconButton(Icons.Rounded.Download, "Download", onDownload)
    }
}

/**
 * A release name that wraps at its dots and dashes ("Dune.Part.Two.2024.2160p…") instead of
 * mid-word; only the first line of a multi-line addon description.
 */
fun releaseName(title: String): String =
    title.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        .replace(".", ".\u200B").replace("_", "_\u200B").replace("-", "-\u200B").replace("[", "\u200B[")

private fun metaLine(s: Source) = listOfNotNull(
    s.provider,
    s.sizeBytes?.let { formatBytes(it) },
    s.seeders?.let { com.kirtanya.reel.ui.plural(it, "seeder") },
).joinToString(" · ")

