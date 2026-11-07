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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
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
import com.kirtanya.reel.ui.title.runPlayAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // The right half follows the focused episode: its still, once focus has rested on it for a moment (moving
    // through the list doesn't fetch a picture per row); the show's backdrop again when focus leaves the episodes.
    var focusedEpisode by remember { mutableStateOf<Episode?>(null) }
    var shownStill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedEpisode) {
        if (focusedEpisode != null) delay(STILL_REST_MS)
        shownStill = focusedEpisode?.stillPath
    }
    var options by remember { mutableStateOf<Episode?>(null) }
    // The episode to put focus back on once the panel closes; the list's own requester sits on it.
    var restoreNumber by remember { mutableStateOf<Int?>(null) }
    val episodeFocus = remember { FocusRequester() }
    var autoFocus by remember { mutableStateOf(true) }
    // ◄ / ► on an episode, or the "Season 3" row at a season's end: the previous / next season, focus on its first
    // episode (or the up-next one there). False when there's no season that way.
    fun stepSeason(delta: Int): Boolean {
        val i = d.seasons.indexOfFirst { it.number == season }
        val to = d.seasons.getOrNull(i + delta) ?: return false
        season = to.number
        restoreNumber = null
        autoFocus = true
        return true
    }
    // Top of the page: Resume / Play next opens the source panel for that episode (or goes straight back into the
    // player with the saved source, as everywhere else).
    fun runAction() = runPlayAction(context, action) { ep ->
        val number = ep ?: return@runPlayAction
        val e = (episodes as? Load.Ready)?.value?.firstOrNull { it.season == number.season && it.number == number.episode }
            ?: Episode(number.season, number.episode, "", "", null, null, null)
        restoreNumber = e.number
        panel = PanelFor(e, download = false)
    }
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
            // Under the source panel the page is only to look at: semantics say so (a modal's background is disabled).
            LazyColumn(
                Modifier.fillMaxSize().then(if (panel != null) Modifier.semantics { disabled() } else Modifier),
                state = listState, verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                item(key = "hero", contentType = "hero") {
                    // Back up from the rows below: the whole top section shows again. Gliding once on focus entry wasn't enough on the
                    // TV: the focused row's own bring-into-view scroll could land after the glide and leave the page stuck part-way
                    // (logo and story cut off). So while focus is anywhere up here, any settled offset is glided back to the top.
                    var heroFocused by remember { mutableStateOf(false) }
                    if (heroFocused) LaunchedEffect(Unit) {
                        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
                            .collect { (index, offset, moving) ->
                                // A focus move's own scroll can interrupt the glide; that cancels only the glide, never
                                // this watcher (it used to end here and leave the page stuck below the top for good).
                                if (!moving && (index > 0 || offset > 0)) {
                                    try { listState.glideToTop() } catch (e: kotlinx.coroutines.CancellationException) { if (!isActive) throw e }
                                }
                            }
                    }
                    Box(
                        Modifier.fillParentMaxSize().onFocusChanged { f -> heroFocused = f.hasFocus },
                    ) {
                        Backdrop(
                            shownStill?.let { TmdbImages.still(it, "w780") } ?: heroBackdrops(d).firstOrNull(),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f),
                        )
                        Column(
                            Modifier.fillMaxHeight().fillMaxWidth(0.5f).padding(start = side - INSET, end = Tokens.Space.l, top = Tokens.Space.tvSafeV, bottom = Tokens.Space.tvSafeV),
                        ) {
                            Column(Modifier.padding(start = INSET), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                TitleLogo(d.logoPath, d.summary.title, Tokens.Type.hero, LOGO_WIDTH, LOGO_HEIGHT, textMaxLines = 2)
                                // One line: ★ rating · year · runtime or seasons · certificate · genres.
                                MetaLine(d.summary.rating, detailsParts(d) + listOfNotNull(d.genres.take(2).joinToString(", ").ifBlank { null }), maxLines = 1)
                                if (d.summary.overview.isNotBlank()) {
                                    Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(Tokens.Space.m))
                            // The page's actions, on top: Resume / Play next (shows), Trailer, My list, Watched.
                            Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                if (isShow) ReelButton(action.label, ::runAction, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
                                // Round icons beside it, so a long "Resume S2 E8 · 30 min left" still fits on the half page.
                                d.trailer?.let { t -> RoundAction(Icons.Rounded.Movie, "Trailer") { openVideo(context, t) } }
                                RoundAction(watchlistIcon(saved), if (saved) "In My list" else "Add to My list", toggleSaved)
                                // The whole film or show (every aired episode).
                                RoundAction(if (watchedAll) Icons.Rounded.Check else Icons.Rounded.Visibility, if (watchedAll) "Watched" else "Mark watched", toggleWatched)
                            }
                            Spacer(Modifier.height(Tokens.Space.s))
                            if (isShow) {
                                Series(
                                    d, season, { season = it }, episodes, { retryEpisodes() }, progress,
                                    focusNumber = restoreNumber ?: nextUp?.takeIf { it.season == season }?.episode,
                                    autoFocus = autoFocus, onAutoFocused = { autoFocus = false }, episodeFocus = episodeFocus,
                                    onPick = { e -> restoreNumber = e.number; panel = PanelFor(e, download = false) },
                                    onOptions = { e -> restoreNumber = e.number; options = e },
                                    onFocusEpisode = { focusedEpisode = it },
                                    onStepSeason = ::stepSeason,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
                                val pick = rememberTvSourcePick(d, null, null, null, null)
                                TvSourceRows(pick, Modifier.weight(1f).fillMaxWidth(), remember { FocusRequester() }, onPick = pick.play)
                            }
                        }
                    }
                }
                // Below the fold, quiet: the people, then more titles.
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

/** How long focus rests on an episode before the right half shows its still. */
private const val STILL_REST_MS = 250L

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
    onFocusEpisode: (Episode?) -> Unit,
    onStepSeason: (Int) -> Boolean,
    modifier: Modifier,
) {
    val tabs = rememberLazyListState()
    // The selected tab stays in view when the season changes from the episodes (◄ / ►, the next-season row).
    LaunchedEffect(season) {
        val i = d.seasons.indexOfFirst { it.number == season }
        if (i >= 0 && tabs.layoutInfo.visibleItemsInfo.none { it.index == i && it.offset >= 0 && it.offset + it.size <= tabs.layoutInfo.viewportEndOffset }) tabs.animateScrollToItem(i)
    }
    val next = d.seasons.getOrNull(d.seasons.indexOfFirst { it.number == season } + 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            LazyRow(Modifier.weight(1f, fill = false).focusRestorer(), state = tabs, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                itemsIndexed(d.seasons, key = { _, s -> s.number }, contentType = { _, _ -> "tab" }) { _, s ->
                    TvTab(s.name, null, selected = s.number == season) { onSeason(s.number) }
                }
            }
            // How far into this season you are: "2 of 8 watched".
            (episodes as? Load.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { list ->
                val seen = list.count { e -> progress[EpisodeNumber(e.season, e.number)]?.finished == true }
                if (seen > 0) Text("$seen of ${list.size} watched", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
            }
        }
        when (episodes) {
            Load.Loading -> LoadingBox(Modifier.weight(1f))
            is Load.Failed -> ReelButton("Couldn't load episodes · Try again", { retry() }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh)
            is Load.Ready -> EpisodeRows(
                episodes.value, progress, focusNumber, autoFocus, onAutoFocused, episodeFocus, onPick, onOptions, onFocusEpisode,
                multiSeason = d.seasons.size > 1, next = next, onStepSeason = onStepSeason, modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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
    onFocusEpisode: (Episode?) -> Unit,
    multiSeason: Boolean,
    next: com.kirtanya.reel.core.tmdb.SeasonSummary?,
    onStepSeason: (Int) -> Boolean,
    modifier: Modifier,
) {
    val state = rememberLazyListState()
    // ◄ / ► jump a season (long anime lists): only with more than one season, and only on the key press.
    val seasonKeys = if (!multiSeason) Modifier else Modifier.onPreviewKeyEvent { k ->
        val delta = when (k.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
        if (k.type == KeyEventType.KeyDown) onStepSeason(delta) else true
    }
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
        LazyColumn(modifier.onFocusChanged { if (!it.hasFocus) onFocusEpisode(null) }, state = state, contentPadding = PaddingValues(vertical = Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            itemsIndexed(episodes, key = { _, e -> "${e.season}x${e.number}" }, contentType = { _, _ -> "episode" }) { _, e ->
                EpisodeRow(
                    e, progress[EpisodeNumber(e.season, e.number)],
                    (if (e.number == target) Modifier.focusRequester(focus) else Modifier).then(seasonKeys),
                    onClick = { onPick(e) }, onLongClick = { onOptions(e) }, onFocus = { onFocusEpisode(e) },
                )
            }
            // The season's end: straight on to the next one.
            if (next != null) item(key = "next", contentType = "next") { NextSeasonRow(next.name, seasonKeys) { onStepSeason(1) } }
        }
    }
}

private val ROW_SHAPE = RoundedCornerShape(Tokens.Radius.media)

/**
 * An episode: a small still (watched ones dimmed with a tick, part-watched ones with a progress bar along its bottom),
 * its number and name, the runtime, and "Resume 42:10" when part-way. One small image per row (w185), no effects.
 */
@Composable
private fun EpisodeRow(e: Episode, watched: WatchEntry?, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit) {
    val finished = watched?.finished == true
    val inProgress = watched != null && !finished && ContinueWatching.resumePositionMs(watched) > 0
    val line = Tokens.Colors.hairlineStrong
    val fill = Tokens.Colors.accentBright
    val share = if (inProgress) watched.progress else 0f
    Row(
        modifier.fillMaxWidth()
            .focusCard(shape = ROW_SHAPE, onLongClick = onLongClick, onClick = onClick, onFocus = onFocus)
            .padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(THUMB_WIDTH).aspectRatio(16f / 9f).clip(THUMB_SHAPE).background(Tokens.Colors.surface2)
                .drawWithContent {
                    drawContent()
                    if (finished) drawRect(Tokens.Colors.canvas.copy(alpha = 0.55f))
                    if (share > 0f) {
                        val h = 3.dp.toPx()
                        drawRect(line, Offset(0f, size.height - h), Size(size.width, h))
                        drawRect(fill, Offset(0f, size.height - h), Size(size.width * share, h))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(TmdbImages.still(e.stillPath, "w185"), Modifier.fillMaxSize())
            if (finished) Icon(Icons.Rounded.Check, "Watched", tint = Tokens.Colors.text, modifier = Modifier.size(20.dp))
        }
        Text("${e.number}", style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 1)
        Text(
            e.name.ifBlank { "Episode ${e.number}" }, style = Tokens.Type.label,
            color = if (finished) Tokens.Colors.textSecondary else Tokens.Colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        when {
            inProgress -> Text(TitleSources.resumeLabel(watched.positionMs), style = Tokens.Type.label, color = Tokens.Colors.accentBright, maxLines = 1)
            else -> e.runtimeMin?.takeIf { it > 0 }?.let { Text("${it}m", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1) }
        }
    }
}

/** A round icon-only action beside the title page's main button (Trailer, My list, Watched); the ring is the focus. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Box(
        Modifier.size(Tokens.Size.button).clip(shape).background(Tokens.Colors.surface2).focusCard(shape = shape, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Tokens.Colors.text, modifier = Modifier.size(20.dp))
    }
}

/** "Season 3  →" after a season's last episode: OK opens that season's tab, focus on its first episode. */
@Composable
private fun NextSeasonRow(name: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).focusCard(shape = ROW_SHAPE, onClick = onClick)
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Tokens.Colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

private val THUMB_WIDTH = 80.dp
private val THUMB_SHAPE = RoundedCornerShape(Tokens.Radius.xs)
