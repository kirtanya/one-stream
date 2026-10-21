package com.kirtanya.reel.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        is ContinueItem.Resume -> com.kirtanya.reel.ui.homedisk.gatedPlay(context, p.source, p.title) { context.startActivity(PlayerActivity.forPlayback(context, p)) }
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
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val history = ReelApp.get(context).history
    val shape = RoundedCornerShape(Tokens.Radius.media)
    // Long-press asks first: on a TV remote a long press happens by accident (it removed two titles at once).
    var confirmRemove by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ContinueItem?>(null) }
    confirmRemove?.let { pending ->
        val keep = remember { FocusRequester() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove from Continue watching?") },
            text = { Text("${pending.entry.playback.title} and where you stopped will be forgotten.") },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemove = null }, modifier = Modifier.focusRequester(keep)) { Text("Keep") }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    history.removeTitle(pending.entry.playback.titleKey)
                    Toast.makeText(context, "Removed ${pending.entry.playback.title}", Toast.LENGTH_SHORT).show()
                    confirmRemove = null
                }) { Text("Remove") }
            },
        )
        // The safe choice has focus, so an accidental OK keeps the title.
        androidx.compose.runtime.LaunchedEffect(pending) { runCatching { keep.requestFocus() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader("Continue watching", Modifier.padding(horizontal = sidePadding))
        LazyRow(
            // TV: up/down between rows comes back to the card last focused in this row.
            modifier = Modifier.focusRestorer(),
            state = listState,
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
                                shape = shape,
                                scale = Tokens.Motion.posterFocusScale,
                                // Grows upward, so it never covers the title underneath.
                                origin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
                                onFocus = onFocus?.let { { it(item) } },
                                onLongClick = { confirmRemove = item },
                                onClick = { openContinue(context, item, onOpenDest) },
                            )
                            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
                    ) {
                        val art = TmdbImages.still(p.stillPath, "w780") ?: TmdbImages.backdrop(p.backdropPath, "w780")
                        if (art != null) {
                            RemoteImage(art, Modifier.fillMaxSize())
                        } else {
                            // A file with no artwork (a link or .torrent): its initial on a tonal tile.
                            Box(Modifier.fillMaxSize().background(Tokens.Colors.tonal), contentAlignment = Alignment.Center) {
                                Text(p.title.trim().take(1).uppercase(), style = Tokens.Type.hero, color = Tokens.Colors.onTonal)
                            }
                        }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Tokens.Colors.scrim)))
                        // Play affordance: glass circle, bottom-left.
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(Tokens.Space.m).size(36.dp).clip(CircleShape)
                                .background(Tokens.Colors.glass).border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, null, tint = Tokens.Colors.text, modifier = Modifier.size(20.dp)) }
                        if (item is ContinueItem.UpNext) {
                            Pill("Up next · ${item.next.label}", Modifier.align(Alignment.TopStart).padding(Tokens.Space.s), accent = true)
                        }
                        if (item is ContinueItem.Resume) {
                            GradientProgress(item.entry.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.xs), height = 3.dp, track = Color.White.copy(alpha = 0.18f))
                        }
                    }
                    Text(continueTitle(item), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(continueDetail(item), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1)
                }
            }
        }
    }
}
