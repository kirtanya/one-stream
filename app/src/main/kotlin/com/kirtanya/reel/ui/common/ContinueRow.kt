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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
    // A card removed a moment ago stays hidden while its Undo is offered.
    val hidden = LocalUndo.current?.hidden.orEmpty().toList()
    return remember(entries, hidden) { ContinueWatching.select(entries, System.currentTimeMillis()).filterNot { it.entry.playback.titleKey in hidden } }
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
 * Used by the options sheet and the launcher; a card tap goes to the title page ([openContinueTitle]).
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

/**
 * A tap on a Continue watching card: the title's page, never the player (the page's Play / Resume button
 * does that, and one wrong press no longer starts a film). A show opens on the episode's season. A raw
 * file with no TMDB title has no page, so it resumes straight away.
 */
fun openContinueTitle(context: android.content.Context, item: ContinueItem, onOpenDest: (Dest) -> Unit) {
    val p = item.entry.playback
    val id = p.tmdbId
    if (id == null) {
        openContinue(context, item, onOpenDest)
        return
    }
    val type = if (p.tmdbType == MediaType.TV.path) MediaType.TV else MediaType.MOVIE
    val season = when (item) {
        is ContinueItem.UpNext -> item.next.season
        is ContinueItem.Resume -> p.episode?.season
    }
    onOpenDest(Dest.Title(TitleRef(type, id), season))
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
    // Cards still on a letter tile (a raw torrent / file / link): look their names up on TMDB in the background.
    val unmatched = items.count { com.kirtanya.reel.core.artmatch.ArtMatch.needsMatch(it.entry.playback) }
    androidx.compose.runtime.LaunchedEffect(unmatched) { if (unmatched > 0) ReelApp.get(context).artMatcher.enrichSoon("row") }
    val shape = RoundedCornerShape(Tokens.Radius.media)
    // Long-press (hold OK) opens the options; removing is one of them, with Undo (it used to remove at once,
    // and on a TV remote a long press happens by accident).
    var options by remember { mutableStateOf<ContinueItem?>(null) }
    val rowFocus = remember { FocusRequester() }
    options?.let { item ->
        ContinueOptions(item, onOpenDest, onDismiss = { options = null }, refocus = { rowFocus.requestFocus() })
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader("Continue watching", Modifier.padding(horizontal = sidePadding))
        LazyRow(
            // TV: up/down between rows comes back to the card last focused in this row.
            modifier = Modifier.focusRequester(rowFocus).focusRestorer(),
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
                                onFocus = onFocus?.let { { it(item) } },
                                onLongClick = { options = item },
                                onClick = { openContinueTitle(context, item, onOpenDest) },
                            )
                            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
                    ) {
                        // w500 covers a 240 dp card at the TV's 2x density; w780 only cost decode time and texture memory.
                        val art = TmdbImages.still(p.stillPath, "w500") ?: TmdbImages.backdrop(p.backdropPath, "w500")
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
                    // textSecondary: on the TV this sits over the focused title's backdrop (a bright one left
                    // textTertiary at ~3.2:1, design rule 3).
                    Text(continueDetail(item), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** The card's art for the options sheet: the episode still, else the backdrop. */
private fun continueArt(item: ContinueItem): String? = item.entry.playback.let { TmdbImages.still(it.stillPath, "w300") ?: TmdbImages.backdrop(it.backdropPath, "w300") }

/**
 * Long-press on a Continue watching card: Resume, Start over, Play next episode, Mark as watched,
 * Choose another source, Go to title, Remove from row (with Undo). Up next cards only have the next
 * episode to play, so they get Play, Go to title and Remove.
 */
@Composable
internal fun ContinueOptions(item: ContinueItem, onOpenDest: (Dest) -> Unit, onDismiss: () -> Unit, refocus: () -> Unit) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val undo = LocalUndo.current
    val entry = item.entry
    val p = entry.playback
    val ref = p.tmdbId?.let { id -> TitleRef(if (p.tmdbType == MediaType.TV.path) MediaType.TV else MediaType.MOVIE, id) }
    fun play(startAtMs: Long?) = com.kirtanya.reel.ui.homedisk.gatedPlay(context, p.source, p.title) {
        context.startActivity(PlayerActivity.forPlayback(context, p, startAtMs))
    }
    val actions = buildList {
        when (item) {
            is ContinueItem.Resume -> {
                add(OptionAction(Icons.Rounded.PlayArrow, "Resume", { play(null) }, detail = continueDetail(item)))
                add(OptionAction(Icons.Rounded.Replay, "Start over", { play(0) }, detail = p.episode?.label))
                val next = p.next
                if (ref != null && next != null) {
                    add(OptionAction(Icons.Rounded.SkipNext, "Play next episode", { onOpenDest(Dest.Sources(ref, next.season, next.episode)) }, detail = next.label))
                }
                // A finished entry drops out of the row, or turns into Up next for a show.
                add(OptionAction(Icons.Rounded.TaskAlt, "Mark as watched", {
                    app.history.markWatched(p, entry.durationMs.takeIf { it > 0 })
                    Toast.makeText(context, "Marked ${p.displayTitle} as watched", Toast.LENGTH_SHORT).show()
                }))
                if (ref != null) {
                    add(OptionAction(Icons.Rounded.SwapHoriz, "Choose another source", {
                        onOpenDest(Dest.Sources(ref, p.episode?.season, p.episode?.episode, resumeAtMs = ContinueWatching.resumePositionMs(entry)))
                    }, detail = "Carries on where you stopped"))
                }
            }
            is ContinueItem.UpNext -> add(OptionAction(Icons.Rounded.PlayArrow, "Play ${item.next.label}", { openContinue(context, item, onOpenDest) }))
        }
        if (ref != null) add(OptionAction(Icons.Rounded.Info, "Go to title", { onOpenDest(Dest.Title(ref)) }))
        add(OptionAction(Icons.Rounded.RemoveCircleOutline, "Remove from row", {
            val commit = { app.history.removeTitle(p.titleKey) }
            if (undo != null) undo.remove(p.titleKey, "Removed ${p.title}", commit, refocus)
            else { commit(); Toast.makeText(context, "Removed ${p.title}", Toast.LENGTH_SHORT).show() }
        }, danger = true))
    }
    OptionsSheet(OptionsHeader(p.title, continueDetail(item), continueArt(item)), actions, onDismiss)
}
