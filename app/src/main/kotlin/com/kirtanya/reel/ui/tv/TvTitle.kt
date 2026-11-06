package com.kirtanya.reel.ui.tv

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.player.TitleSources
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.EnterRowAtStart
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.MetaLine
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.RingAvatar
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.detailsParts
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.openVideo
import com.kirtanya.reel.ui.common.rememberWatchlistToggle
import com.kirtanya.reel.ui.common.rowStart
import com.kirtanya.reel.ui.common.watchlistIcon
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.EpisodeOptions
import com.kirtanya.reel.ui.title.PlayAction
import com.kirtanya.reel.ui.title.heroBackdrops
import com.kirtanya.reel.ui.title.rememberEpisodeProgress
import com.kirtanya.reel.ui.title.rememberPlayAction
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import kotlinx.coroutines.launch

/**
 * TV title page, a cinematic 50/50 (docs/TV_CLEAN_REDESIGN.md §4): the backdrop fills the right half edge to edge, the left
 * half has the logo, one facts line, three lines of story and then the way to play. A film lists its sources right
 * there (best first, OK plays, hold OK downloads); a series lists season tabs and episodes, and OK on an episode opens
 * [TvSourcePanel] over the page. Cast and More like this wait below the fold.
 *
 * [onPlay] is unused: nothing on this page leaves for the Sources page any more (`Dest.Sources` stays for deep links and the phone).
 */
@Composable
fun TvTitle(ref: TitleRef, initialSeason: Int? = null, onPlay: (season: Int?, episode: Int?) -> Unit = { _, _ -> }, onOpen: (TitleSummary) -> Unit = {}, onOpenDest: (Dest) -> Unit = {}) {
    val (load, retry) = rememberTitle(ref)
    when (load) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> {
            val focus = remember { FocusRequester() }
            RequestFocusOnce(focus)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Refresh, "Couldn't load this title", load.message, Modifier.fillMaxWidth(0.6f)) {
                    ReelButton("Try again", { retry() }, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                }
            }
        }
        is Load.Ready -> TvTitleBody(load.value, initialSeason, onOpen, onOpenDest)
    }
}

/** The source panel's job: the episode it is open for, and whether OK downloads (from an episode's options) instead of playing. */
private data class PanelFor(val episode: Episode, val download: Boolean)

@Composable
private fun TvTitleBody(d: TitleDetails, initialSeason: Int?, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit) {
    val context = LocalContext.current
    val isShow = d.seasons.isNotEmpty()
    val action = rememberPlayAction(d)
    // Where "keep watching" is: the episode Resume would carry on with, or the one after a finished one.
    val nextUp = (action as? PlayAction.Resume)?.entry?.playback?.episode ?: (action as? PlayAction.Choose)?.episode
    var season by rememberSaveable {
        mutableStateOf(
            initialSeason?.takeIf { s -> d.seasons.any { it.number == s } }
                ?: nextUp?.season?.takeIf { s -> d.seasons.any { it.number == s } }
                ?: d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number,
        )
    }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val progress = if (d.summary.ref.type == MediaType.TV) rememberEpisodeProgress(d.summary.ref.id) else emptyMap()
    val (saved, toggleSaved) = rememberWatchlistToggle(d.summary)
    val (watchedAll, toggleWatched) = com.kirtanya.reel.ui.title.rememberTitleWatched(d)
    val side = Tokens.Space.tvSafeH
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var panel by remember { mutableStateOf<PanelFor?>(null) }
    var options by remember { mutableStateOf<Episode?>(null) }
    // The episode to put focus back on once the panel closes; the list's own requester sits on it.
    var restoreNumber by remember { mutableStateOf<Int?>(null) }
    val episodeFocus = remember { FocusRequester() }
    var autoFocus by remember { mutableStateOf(true) }
    fun closePanel() {
        panel = null
        scope.launch {
            repeat(10) {
                withFrameNanos { }
                if (runCatching { episodeFocus.requestFocus() }.getOrDefault(false)) return@launch
            }
        }
    }
    options?.let { e ->
        EpisodeOptions(
            d, e, progress[EpisodeNumber(e.season, e.number)],
            onPlay = { _, _ -> restoreNumber = e.number; panel = PanelFor(e, download = false) },
            onOpenDest = onOpenDest, onDismiss = { options = null },
            onDownload = { _, _ -> restoreNumber = e.number; panel = PanelFor(e, download = true) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        TvColumnGlide(reveal = RowReveal) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
                item(key = "hero", contentType = "hero") {
                    Box(
                        Modifier.fillParentMaxSize().onFocusChanged { f ->
                            // Back up from the rows below: glide to the very top so the whole page shows, not just the list's last row.
                            if (f.hasFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) scope.launch { listState.glideToTop() }
                        },
                    ) {
                        Backdrop(heroBackdrops(d).firstOrNull(), Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f))
                        Column(
                            Modifier.fillMaxHeight().fillMaxWidth(0.5f).padding(start = side - INSET, end = Tokens.Space.l, top = Tokens.Space.tvSafeV, bottom = Tokens.Space.tvSafeV),
                        ) {
                            Column(Modifier.padding(start = INSET), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                TitleLogo(d.logoPath, d.summary.title, Tokens.Type.hero, LOGO_WIDTH, LOGO_HEIGHT, textMaxLines = 2)
                                // One line: ★ rating · year · runtime or seasons · certificate · genres.
                                MetaLine(d.summary.rating, detailsParts(d) + listOfNotNull(d.genres.take(2).joinToString(", ").ifBlank { null }), maxLines = 1)
                                if (d.summary.overview.isNotBlank()) {
                                    Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(Tokens.Space.m))
                            if (isShow) {
                                Series(
                                    d, season, { season = it }, episodes, { retryEpisodes() }, progress,
                                    focusNumber = restoreNumber ?: nextUp?.takeIf { it.season == season }?.episode,
                                    autoFocus = autoFocus, onAutoFocused = { autoFocus = false }, episodeFocus = episodeFocus,
                                    onPick = { e -> restoreNumber = e.number; panel = PanelFor(e, download = false) },
                                    onOptions = { e -> restoreNumber = e.number; options = e },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
                                val pick = rememberTvSourcePick(d, null, null, null, null)
                                TvSourceRows(pick, Modifier.weight(1f).fillMaxWidth(), remember { FocusRequester() }, onPick = pick.play)
                            }
                        }
                    }
                }
                // Below the fold, quiet: what used to be buttons on the hero, then the people, then more titles.
                item(key = "actions", contentType = "actions") {
                    EnterRowAtStart {
                        Row(Modifier.padding(horizontal = side - INSET).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            var first = true
                            d.trailer?.let { t ->
                                ReelButton("Trailer", { openVideo(context, t) }, Modifier.rowStart(true), kind = ButtonKind.Ghost, icon = Icons.Rounded.Movie)
                                first = false
                            }
                            ReelButton(if (saved) "In My list" else "My list", toggleSaved, Modifier.rowStart(first), kind = ButtonKind.Ghost, icon = watchlistIcon(saved))
                            // The whole film or show (every aired episode).
                            ReelButton(if (watchedAll) "Watched" else "Mark watched", toggleWatched, kind = ButtonKind.Ghost, icon = if (watchedAll) Icons.Rounded.Check else Icons.Rounded.Visibility)
                        }
                    }
                }
                if (d.cast.isNotEmpty()) {
                    item(key = "cast", contentType = "cast") {
                        EnterRowAtStart {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                SectionHeader("Cast", Modifier.padding(horizontal = side))
                                TvRowPivot(side) {
                                    LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                        itemsIndexed(d.cast, key = { i, c -> "$i:${c.id}" }, contentType = { _, _ -> "person" }) { i, c ->
                                            RingAvatar(
                                                TmdbImages.profile(c.profilePath), c.name, 60.dp, modifier = Modifier.rowStart(i == 0), sublabel = c.character, placeholder = c.name.take(1), plain = true,
                                                onClick = { c.id?.let { onOpenDest(Dest.Person(it, c.name)) } },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (d.collection != null) {
                    item(key = "franchise", contentType = "posters") { EnterRowAtStart { com.kirtanya.reel.ui.title.FranchiseRow(d, side, Tokens.Cinema.posterTv, onOpen, onOpenDest, tv = true) } }
                }
                if (d.recommendations.isNotEmpty()) {
                    item(key = "more", contentType = "posters") { EnterRowAtStart { TvPosterRow("More like this", d.recommendations, side, Tokens.Cinema.posterTv, onOpen) } }
                }
                // Not contentPadding: the first item fills the screen, and padding would shrink it and let the next row peek in.
                item(key = "end", contentType = "end") { Spacer(Modifier.height(Tokens.Space.tvSafeV)) }
            }
        }
        panel?.let { p -> TvSourcePanel(d, p.episode, p.download, onClose = ::closePanel) }
    }
}

/** The left half's content sits this far in from the rows' focus ring, so ring and text line up under the logo. */
private val INSET = Tokens.Space.m
private val LOGO_WIDTH = 300.dp
private val LOGO_HEIGHT = 64.dp

/** Under a focused item the page also shows its caption (a cast member's role): scroll that far into view too. */
private val RowReveal = 28.dp

/** Back to the top over the page glide time (a fixed-length tween, not the list's default spring). */
private suspend fun LazyListState.glideToTop() {
    if (firstVisibleItemIndex == 0) animateScrollBy(-firstVisibleItemScrollOffset.toFloat(), tween(TvMotion.columnGlideMs, easing = TvMotion.easing))
    else animateScrollToItem(0)
}

/**
 * The right half: the backdrop, cropped, with one baked scrim on its left edge so it melts into the canvas.
 * Static: nothing animates here (docs/SMOOTH_UI_PLAN.md §2).
 */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    Box(modifier) {
        RemoteImage(url, Modifier.fillMaxSize())
        EdgeScrim(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(SCRIM_WIDTH))
    }
}

private val SCRIM_WIDTH = 240.dp

/**
 * Canvas fading to clear, left to right, baked once into a tiny bitmap (a few dozen pixels wide, one pixel tall) and
 * stretched: a gradient shader over half the screen costs the TV's GPU on every frame, a stretched texture almost nothing
 * (the same trick as `HeroScrims`, which also fades the bottom and so can't be used here).
 */
@Composable
private fun EdgeScrim(modifier: Modifier) {
    val canvas = Tokens.Colors.canvas
    Box(
        modifier.drawWithCache {
            val w = (size.width / 6f).toInt().coerceAtLeast(1)
            val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, 1)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), Size(w.toFloat(), 1f)) {
                drawRect(Brush.horizontalGradient(0f to canvas, 0.35f to canvas.copy(alpha = 0.7f), 1f to Color.Transparent))
            }
            onDrawBehind {
                drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            }
        },
    )
}

// ─── Series: season tabs and the episode list ─────────────────────────────────────────────────

@Composable
private fun Series(
    d: TitleDetails,
    season: Int?,
    onSeason: (Int) -> Unit,
    episodes: Load<List<Episode>>,
    retry: () -> Unit,
    progress: Map<EpisodeNumber, WatchEntry>,
    focusNumber: Int?,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    episodeFocus: FocusRequester,
    onPick: (Episode) -> Unit,
    onOptions: (Episode) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            itemsIndexed(d.seasons, key = { _, s -> s.number }, contentType = { _, _ -> "tab" }) { _, s ->
                TvTab(s.name, null, selected = s.number == season) { onSeason(s.number) }
            }
        }
        when (episodes) {
            Load.Loading -> LoadingBox(Modifier.weight(1f))
            is Load.Failed -> ReelButton("Couldn't load episodes · Try again", { retry() }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh)
            is Load.Ready -> EpisodeRows(episodes.value, progress, focusNumber, autoFocus, onAutoFocused, episodeFocus, onPick, onOptions, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun EpisodeRows(
    episodes: List<Episode>,
    progress: Map<EpisodeNumber, WatchEntry>,
    focusNumber: Int?,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    focus: FocusRequester,
    onPick: (Episode) -> Unit,
    onOptions: (Episode) -> Unit,
    modifier: Modifier,
) {
    val state = rememberLazyListState()
    // The row that holds the requester: the one to pre-focus (up next) or to come back to after the panel; else the first.
    val target = episodes.firstOrNull { it.number == focusNumber }?.number ?: episodes.firstOrNull()?.number
    if (autoFocus && episodes.isNotEmpty()) {
        // Once, when the page opens: the up-next episode is in view (one above it for context) and has focus.
        androidx.compose.runtime.LaunchedEffect(episodes) {
            val index = episodes.indexOfFirst { it.number == target }.coerceAtLeast(0)
            state.scrollToItem((index - 1).coerceAtLeast(0))
            repeat(10) {
                withFrameNanos { }
                if (runCatching { focus.requestFocus() }.getOrDefault(false)) {
                    onAutoFocused()
                    return@LaunchedEffect
                }
            }
        }
    }
    com.kirtanya.reel.ui.common.WithBringIntoView(com.kirtanya.reel.ui.common.MinimalBringIntoView) {
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            itemsIndexed(episodes, key = { _, e -> "${e.season}x${e.number}" }, contentType = { _, _ -> "episode" }) { _, e ->
                EpisodeRow(
                    e, progress[EpisodeNumber(e.season, e.number)],
                    if (e.number == target) Modifier.focusRequester(focus) else Modifier,
                    onClick = { onPick(e) }, onLongClick = { onOptions(e) },
                )
            }
        }
    }
}

private val ROW_SHAPE = RoundedCornerShape(Tokens.Radius.media)

/**
 * An episode: its number, name, runtime and what you did with it (a tick when watched, "Resume 42:10" part-way, a thin
 * line under the row). No picture and no effects per row: a list of these stays light.
 */
@Composable
private fun EpisodeRow(e: Episode, watched: WatchEntry?, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val inProgress = watched != null && !watched.finished && ContinueWatching.resumePositionMs(watched) > 0
    val line = Tokens.Colors.hairlineStrong
    val fill = Tokens.Colors.text
    val share = if (inProgress) watched.progress else 0f
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .focusCard(shape = ROW_SHAPE, onLongClick = onLongClick, onClick = onClick)
            .drawBehind {
                if (share > 0f) {
                    val inset = Tokens.Space.m.toPx()
                    val w = size.width - 2 * inset
                    val h = 2.dp.toPx()
                    drawRect(line, Offset(inset, size.height - h - 3.dp.toPx()), Size(w, h))
                    drawRect(fill, Offset(inset, size.height - h - 3.dp.toPx()), Size(w * share, h))
                }
            }
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.number}", style = Tokens.Type.label, color = Tokens.Colors.textSecondary, modifier = Modifier.width(24.dp), textAlign = TextAlign.End, maxLines = 1)
        Text(e.name.ifBlank { "Episode ${e.number}" }, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        e.runtimeMin?.takeIf { it > 0 }?.let { Text("${it}m", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1) }
        when {
            watched?.finished == true -> Icon(Icons.Rounded.Check, "Watched", tint = Tokens.Colors.textSecondary, modifier = Modifier.size(16.dp))
            inProgress -> Text(TitleSources.resumeLabel(watched.positionMs), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1)
        }
    }
}
