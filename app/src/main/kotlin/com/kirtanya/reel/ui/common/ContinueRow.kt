package com.kirtanya.reel.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.player.PlayerActivity
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens

/** Continue watching, newest first; recomputed whenever the history changes. */
@Composable
fun rememberContinueWatching(): List<ContinueItem> {
    val history = ReelApp.get(LocalContext.current).history
    val entries by history.entries.collectAsState()
    return remember(entries) { ContinueWatching.select(entries, System.currentTimeMillis()) }
}

/** Card heading and detail line, also used for the TV home's info block. */
fun continueTitle(item: ContinueItem): String = item.entry.playback.title

fun continueDetail(item: ContinueItem): String = when (item) {
    is ContinueItem.UpNext -> "Up next · ${item.next.label}"
    is ContinueItem.Resume -> listOfNotNull(
        item.entry.playback.episode?.label,
        "${(item.entry.remainingMs / 60_000).coerceAtLeast(1)} min left",
    ).joinToString(" · ")
}

/**
 * Resume → straight back into the player with the same source.
 * Up next → the Sources screen for that episode.
 */
fun openContinue(context: android.content.Context, item: ContinueItem, onOpenDest: (Dest) -> Unit) {
    val p = item.entry.playback
    when (item) {
        is ContinueItem.Resume -> context.startActivity(PlayerActivity.forPlayback(context, p))
        is ContinueItem.UpNext -> {
            val id = p.tmdbId ?: return
            onOpenDest(Dest.Sources(TitleRef(MediaType.TV, id), item.next.season, item.next.episode))
        }
    }
}

@Composable
fun ContinueRow(
    items: List<ContinueItem>,
    cardWidth: Dp,
    sidePadding: Dp,
    onOpenDest: (Dest) -> Unit,
    onFocus: ((ContinueItem) -> Unit)? = null,
    firstFocus: FocusRequester? = null,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val history = ReelApp.get(context).history
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Text("Continue watching", style = Tokens.Type.heading, color = Tokens.Colors.text, modifier = Modifier.padding(horizontal = sidePadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            itemsIndexed(items, key = { _, it -> it.entry.playback.titleKey }) { i, item ->
                val p = item.entry.playback
                Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Box(
                        Modifier
                            .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .focusCard(
                                onFocus = onFocus?.let { { it(item) } },
                                onLongClick = {
                                    history.removeTitle(p.titleKey)
                                    Toast.makeText(context, "Removed ${p.title} from Continue watching", Toast.LENGTH_SHORT).show()
                                },
                                onClick = { openContinue(context, item, onOpenDest) },
                            ),
                    ) {
                        RemoteImage(TmdbImages.still(p.stillPath, "w780") ?: TmdbImages.backdrop(p.backdropPath, "w780"), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Tokens.Colors.scrim)))
                        if (item is ContinueItem.Resume) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(Tokens.Size.progressBar).background(Tokens.Colors.surfaceHigh))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.entry.progress).height(Tokens.Size.progressBar).background(Tokens.Colors.accent))
                        } else {
                            Text(
                                "Up next",
                                style = Tokens.Type.caption,
                                color = Tokens.Colors.onAccent,
                                modifier = Modifier.align(Alignment.TopStart).padding(Tokens.Space.s).roundedClip(Tokens.Radius.s)
                                    .background(Tokens.Colors.accent).padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xxs),
                            )
                        }
                    }
                    Text(continueTitle(item), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(continueDetail(item), style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}
