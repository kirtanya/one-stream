package com.kirtanya.reel.ui.tv

import com.kirtanya.reel.ui.home.withoutHidden
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.kirtanya.reel.ui.common.HeroPicture
import com.kirtanya.reel.ui.common.ImdbPill
import com.kirtanya.reel.ui.common.WithBringIntoView
import com.kirtanya.reel.ui.common.rememberHomeColumnSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.layout.Row as RowLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ContinueRow
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.MetaLine
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.continueDetail
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.common.rememberWatchlist
import com.kirtanya.reel.ui.common.summaryParts
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.home.RowIds
import com.kirtanya.reel.ui.home.TitleRow
import com.kirtanya.reel.ui.home.TvCards
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/** What the big info block and backdrop show for the focused card. [key] identifies the card. */
private data class HomeInfo(
    val key: String,
    val ref: TitleRef?,
    val title: String,
    val rating: Double?,
    val parts: List<String>,
    val overview: String,
    val backdropPath: String?,
    val posterPath: String?,
    /** Continue watching: "Up next · S2 E6" / "8 min left". */
    val badge: String? = null,
)

private fun TitleSummary.info() = HomeInfo("poster:$ref", ref, title, rating, summaryParts(this), overview, backdropPath, posterPath)

private fun ContinueItem.info(): HomeInfo {
    val p = entry.playback
    val ref = p.tmdbId?.let { id -> TitleRef(if (p.tmdbType == "tv") MediaType.TV else MediaType.MOVIE, id) }
    return HomeInfo(
        "continue:${p.titleKey}", ref, p.title, null, listOfNotNull(if (p.tmdbType == "tv") "Series" else if (ref != null) "Film" else null, p.year?.toString()),
        p.subtitle.orEmpty(), p.backdropPath, p.posterPath, badge = continueDetail(this),
    )
}

/** Side padding of Home's rows and info block: the first card sits [TvHomeSide] right of the rail ([TvRailWidth]). */
internal val TvHomeSide = Tokens.Space.l

/**
 * Cinematic home: the focused card's backdrop fills the screen, its logo and details sit top-left,
 * Continue watching, My list and rows of posters below.
 */
@Composable
fun TvHome(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit, onShowDownloads: () -> Unit = {}) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(Unit) { catalog.home() }
    val continueItems = rememberContinueWatching()
    val myList = rememberWatchlist()
    val because = com.kirtanya.reel.ui.home.rememberBecauseYouWatched()
    val hidden = com.kirtanya.reel.ui.home.rememberHiddenKeys()
    // Shows you follow (My list, Continue watching) with an episode out this week or coming soon.
    val followed = (myList.map { it.ref } + com.kirtanya.reel.ui.home.continueTitles(continueItems).map { it.ref }).filter { it.type == com.kirtanya.reel.core.tmdb.MediaType.TV }
    val newEpisodes = com.kirtanya.reel.ui.home.rememberNewEpisodes(followed)
    when (load) {
        Load.Loading -> LoadingBox(Modifier.padding(start = TvRailWidth))
        is Load.Failed -> TvHomeProblem("Couldn't load today's titles", load.message, { retry() }, onShowDownloads)
        is Load.Ready -> {
            // TMDB answered but with nothing to show (e.g. a regional hiccup): say so instead of crashing.
            val rows = load.value.map { it.copy(items = it.items.withoutHidden(hidden)) }.filter { it.items.isNotEmpty() }
            if (rows.isEmpty()) TvHomeProblem("Nothing to show right now", "Couldn't get today's titles. Check your connection and try again.", { retry() }, onShowDownloads)
            else TvHomeContent(rows.map { it.title to it.items }, continueItems, myList, because?.let { it.first to it.second.withoutHidden(hidden) }?.takeIf { it.second.isNotEmpty() }, newEpisodes, onOpen, onOpenDest)
        }
    }
}

/** Home couldn't load (offline, or TMDB said nothing): Retry, or the Downloads tab, which works without a connection. */
@Composable
private fun TvHomeProblem(title: String, body: String, onRetry: () -> Unit, onShowDownloads: () -> Unit) {
    val offline = com.kirtanya.reel.ui.common.rememberOffline()
    val focus = remember { FocusRequester() }
    RequestFocusOnce(focus)
    Box(Modifier.fillMaxSize().padding(start = TvRailWidth), contentAlignment = Alignment.Center) {
        EmptyState(
            if (offline) Icons.Rounded.CloudOff else Icons.Rounded.Refresh,
            if (offline) "You're offline" else title,
            if (offline) "Downloads still play. Home comes back when you're connected." else body,
            Modifier.fillMaxWidth(0.6f),
        ) {
            RowLayout(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                ReelButton("Retry", onRetry, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                ReelButton("View downloads", onShowDownloads, kind = ButtonKind.Ghost, icon = Icons.Rounded.Download)
            }
        }
    }
}

@Composable
private fun TvHomeContent(
    rows: List<Pair<String, List<TitleSummary>>>,
    continueItems: List<ContinueItem>,
    myList: List<TitleSummary>,
    because: Pair<String, List<TitleSummary>>?,
    newEpisodes: List<com.kirtanya.reel.core.tmdb.CalendarEntry>,
    onOpen: (TitleSummary) -> Unit,
    onOpenDest: (Dest) -> Unit,
) {
    val initial = continueItems.firstOrNull()?.info() ?: rows.first().second.first().info()
    // Read only inside HomeBackdrop / HomeInfoBlock: a read here would recompose every row on each move.
    val focusedState = remember { mutableStateOf(initial) }
    var focused by focusedState
    // Two settle stages after the remote rests on a card: the info block (text, logo) first, then the backdrop, so a
    // card it merely skips over changes nothing and a held key shows no flicker.
    val infoState = remember { mutableStateOf(initial) }
    val settledState = remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        snapshotFlow { focusedState.value }.collectLatest {
            delay(Tokens.Motion.infoSettleMs.toLong())
            infoState.value = it
            delay((Tokens.Motion.heroSettleMs - Tokens.Motion.infoSettleMs).toLong())
            settledState.value = it
        }
    }
    val firstCard = remember { FocusRequester() }
    val columnState = rememberLazyListState()
    val continueState = rememberLazyListState()
    // The first poster row's scroll, so focus can come back to poster 0 after the last Continue card goes.
    val firstRowState = rememberLazyListState()
    // First visit: the first card. Coming back (Back from a title): the card you opened, via focusRestorer.
    val columnFocus = remember { FocusRequester() }
    RequestFocusOnce(columnFocus)
    // A Continue watching card was removed (hold OK) or changed: refresh the header and put the
    // remote's focus back on the first card instead of losing it.
    LaunchedEffect(continueItems) {
        if (!focused.key.startsWith("continue:")) return@LaunchedEffect
        val still = continueItems.firstOrNull { "continue:${it.entry.playback.titleKey}" == focused.key }
        if (still != null) {
            focused = still.info()
        } else {
            focused = continueItems.firstOrNull()?.info() ?: rows.first().second.first().info()
            // The first card may have scrolled out (and been disposed): bring it back, then focus it.
            runCatching { columnState.scrollToItem(0) }
            if (continueItems.isNotEmpty()) runCatching { continueState.scrollToItem(0) }
            else runCatching { firstRowState.scrollToItem(0) }
            repeat(10) {
                withFrameNanos { }
                // requestFocus() doesn't throw when the node isn't attached; the Boolean overload tells us.
                if (runCatching { firstCard.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }

    val columnTop = Tokens.Space.s
    val columnSpec = rememberHomeColumnSpec(columnTop)
    Box(Modifier.fillMaxSize()) {
        // The focused title fills the room: its backdrop over pure black, black behind the text.
        HomeBackdrop(settledState)

        // Right of the rail; the backdrop above runs under it.
        Column(Modifier.fillMaxSize().padding(start = TvRailWidth)) {
            Spacer(Modifier.height(Tokens.Space.tvSafeV))
            HomeInfoBlock(infoState)
            // Netflix-style: the focused row glides to a fixed line just under the info block (columnSpec) and its
            // cards slide under a fixed focus (each row's own spec).
            WithBringIntoView(columnSpec) {
                LazyColumn(
                    Modifier.weight(1f).focusRequester(columnFocus).focusRestorer(),
                    state = columnState,
                    contentPadding = PaddingValues(top = columnTop, bottom = Tokens.Space.tvSafeV),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                ) {
                    // Rows in the order chosen in Settings → Home screen; hidden ones left out.
                    val available = buildList {
                        if (continueItems.isNotEmpty()) add(RowIds.CONTINUE)
                        if (newEpisodes.isNotEmpty()) add(RowIds.NEW_EPISODES)
                        if (myList.isNotEmpty()) add(RowIds.MY_LIST)
                        if (because != null) add(RowIds.BECAUSE)
                        rows.forEach { r -> add(RowIds.tmdb(r.first)) }
                    }
                    val arranged = HomeLayout.arrange(available)
                    // The remote starts on the first row.
                    val firstRow = arranged.firstOrNull()
                    val firstTmdb = arranged.firstOrNull { it.startsWith("tmdb:") }
                    arranged.forEach { id ->
                        item(key = id, contentType = if (id == RowIds.CONTINUE) "continue" else "titles") {
                            val first = if (id == firstRow) firstCard else null
                            when (id) {
                                RowIds.CONTINUE -> ContinueRow(
                                    continueItems, cardWidth = TvCards.stillWidth, sidePadding = TvHomeSide, onOpenDest = onOpenDest,
                                    onFocus = { focused = it.info() }, firstFocus = first, listState = continueState,
                                )
                                RowIds.MY_LIST -> TitleRow(
                                    "My list", myList, TvHomeSide, onOpen, isTv = true, onFocus = { focused = it.info() }, firstFocus = first,
                                    onSeeAll = { onOpenDest(Dest.Collection("My list", items = myList)) },
                                )
                                RowIds.BECAUSE -> because?.let { (title, items) ->
                                    TitleRow(title, items, TvHomeSide, onOpen, isTv = true, onFocus = { focused = it.info() }, firstFocus = first, onSeeAll = { onOpenDest(Dest.Collection(title, items = items)) })
                                }
                                RowIds.NEW_EPISODES -> com.kirtanya.reel.ui.home.NewEpisodesRow(newEpisodes, TvHomeSide, onOpen, isTv = true, onFocus = { focused = it.info() }, firstFocus = first)
                                else -> rows.firstOrNull { RowIds.tmdb(it.first) == id }?.let { (title, items) ->
                                    TitleRow(
                                        title, items, TvHomeSide, onOpen, isTv = true, onFocus = { focused = it.info() }, firstFocus = first,
                                        onSeeAll = { onOpenDest(Dest.Collection(title, items = items)) },
                                        listState = if (id == firstTmdb) firstRowState else rememberLazyListState(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The settled title's backdrop over black; recomposes only when the remote settles on another title. The picture
 * cross-fades ([HeroPicture], opacity only); the scrims are baked once ([HeroScrims]).
 */
@Composable
private fun HomeBackdrop(settled: State<HomeInfo>) {
    val path = settled.value.backdropPath
    Box(Modifier.fillMaxSize()) {
        HeroPicture(TmdbImages.backdrop(path), Modifier.fillMaxSize())
        HeroScrims(top = true, start = true, topHeight = Tokens.Space.tvSafeV + Tokens.Space.xxxl)
    }
}

/**
 * Badge, logo, meta line, IMDb score and two lines of story for the title the remote rests on. When it changes the
 * block fades in and lifts 8 dp ([Tokens.Motion.infoInMs], graphicsLayer only: its height is fixed, so the rows
 * below never re-layout). It waits for the title's logo (a short timeout, then the name as text) so the name never
 * flashes as text before the logo replaces it.
 */
@Composable
private fun HomeInfoBlock(infoState: State<HomeInfo>) {
    val info = infoState.value
    val catalog = rememberCatalog()
    val context = LocalContext.current
    val (logo, _) = rememberLoad(info.ref) {
        val path = info.ref?.let { catalog.logo(it) }
        // Fetch the picture before reporting the logo as ready, so it is there when the block fades in.
        if (path != null) runCatching { SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(TmdbImages.titleLogo(path)).build()) }
        path
    }
    // IMDb score: the posters on TV carry none (cost), so it's here. Cached ones are there at once.
    val (imdb, _) = rememberLoad(info.ref) { info.ref?.let { catalog.posterImdb(it) } }
    var timedOut by remember(info.key) { mutableStateOf(false) }
    LaunchedEffect(info.key) {
        delay(600)
        timedOut = true
    }
    val ready = info.ref == null || logo !is Load.Loading || timedOut
    // A new Animatable per title starts at 0: the new text is never drawn for a frame at full opacity.
    val shown = remember(info.key) { Animatable(0f) }
    LaunchedEffect(shown, ready) {
        if (ready) shown.animateTo(1f, tween(Tokens.Motion.infoInMs, easing = Tokens.Motion.easing))
    }
    val lift = with(LocalDensity.current) { 8.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth(0.5f)
            .height(Tokens.Cinema.tvInfoHeight)
            .padding(horizontal = TvHomeSide)
            .graphicsLayer {
                alpha = shown.value
                translationY = (1f - shown.value) * lift
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s, Alignment.Bottom),
    ) {
        info.badge?.let { Pill(it, accent = true) }
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.BottomStart) {
            TitleLogo((logo as? Load.Ready)?.value, info.title, Tokens.Type.hero, Tokens.Cinema.logoMaxWidthTv, 64.dp, textMaxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
            MetaLine(null, info.parts, Modifier.weight(1f, fill = false), maxLines = 1)
            (imdb as? Load.Ready)?.value?.let { ImdbPill(it) }
        }
        if (info.overview.isNotBlank()) {
            Text(info.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
