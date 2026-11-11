package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.browse.Categories
import com.kirtanya.reel.core.browse.Category
import com.kirtanya.reel.core.browse.GridNav
import com.kirtanya.reel.core.browse.Interleave
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.Catalog
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.PosterCard
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.home.rememberHiddenKeys
import com.kirtanya.reel.ui.home.withoutHidden
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.SearchKind
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ─── Movies / Shows ──────────────────────────────────────────────────────────────────────────

/**
 * The TV's Movies or Shows page: Home's layout (backdrop, info block, rows of cards) with only films or only shows.
 * No Continue watching or My list: those are Home's. Rows are fetched when the tab is first opened, not at start.
 */
@Composable
fun TvTypeHome(type: MediaType, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit, onShowDownloads: () -> Unit = {}) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(type) { catalog.browseRows(type) }
    val hidden = rememberHiddenKeys()
    val noun = if (type == MediaType.MOVIE) "films" else "shows"
    when (load) {
        Load.Loading -> LoadingBox(Modifier.padding(start = TvRailWidth))
        is Load.Failed -> TvHomeProblem("Couldn't load the $noun", load.message, { retry() }, onShowDownloads)
        is Load.Ready -> {
            val rows = load.value.map { it.title to it.items.withoutHidden(hidden) }.filter { it.second.isNotEmpty() }
            if (rows.isEmpty()) TvHomeProblem("No $noun to show right now", "TMDB had no $noun to list. Check your connection and try again.", { retry() }, onShowDownloads)
            else TvHomeContent(rows, emptyList(), emptyList(), null, emptyList(), onOpen, onOpenDest, customLayout = false)
        }
    }
}

// ─── Categories ──────────────────────────────────────────────────────────────────────────────

private val TileHeight = 72.dp
private const val TileColumns = 4

/**
 * The TV's Categories page: a grid of plain tiles, genres (films and shows merged) then languages. Opening a tile pushes
 * [Dest.Category]; Back returns here with the same tile focused (its index is saved with the page). Only the genre lists
 * are fetched (cached), and the page shows TMDB's usual ones at once, so there is no spinner and nothing waits on the network.
 */
@Composable
fun TvCategories(onOpenDest: (Dest) -> Unit) {
    val catalog = rememberCatalog()
    val (load, _) = rememberLoad(Unit) {
        // Each list on its own: one failing leaves the other's genres, both failing leaves the built-in set.
        coroutineScope {
            val movie = async { runCatching { catalog.genres(MediaType.MOVIE) }.getOrNull() }
            val tv = async { runCatching { catalog.genres(MediaType.TV) }.getOrNull() }
            val m = movie.await()
            val t = tv.await()
            if (m.isNullOrEmpty() && t.isNullOrEmpty()) Categories.FALLBACK_GENRES else Categories.mergeGenres(m.orEmpty(), t.orEmpty())
        }
    }
    val genres = (load as? Load.Ready)?.value ?: Categories.FALLBACK_GENRES
    val tiles = remember(genres) { genres + Categories.LANGUAGES }
    val requesters = rememberRequesters(tiles.size)
    // The tile the remote was last on: read only when the page opens, so a focus move recomposes nothing.
    val last = rememberSaveable { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()
    // Heading, "Genres", the genre tiles, "Languages", the language tiles.
    fun gridIndex(tile: Int) = if (tile < genres.size) 2 + tile else 3 + tile
    FocusTile(grid, requesters, GridNav.restore(last.intValue, tiles.size), ::gridIndex, enabled = tiles.isNotEmpty())
    val side = Tokens.Space.tvSafeH
    TvColumnGlide(reveal = Tokens.Space.s) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(TileColumns),
            state = grid,
            modifier = Modifier.fillMaxSize().background(Tokens.Colors.canvas),
            contentPadding = PaddingValues(start = side, end = side, top = Tokens.Space.tvSafeV, bottom = Tokens.Space.tvSafeV),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "heading", contentType = "heading") {
                Text("Categories", style = Tokens.Type.hero, color = Tokens.Colors.text, maxLines = 1, modifier = Modifier.padding(bottom = Tokens.Space.s))
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "genres", contentType = "section") { SectionHeader("Genres") }
            itemsIndexed(genres, key = { _, c -> c.key }, contentType = { _, _ -> "tile" }) { i, c ->
                CategoryTile(c, Modifier.focusRequester(requesters[i]), onFocus = { last.intValue = i }) { onOpenDest(Dest.Category(c)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "languages", contentType = "section") {
                SectionHeader("Languages", Modifier.padding(top = Tokens.Space.m))
            }
            itemsIndexed(Categories.LANGUAGES, key = { _, c -> c.key }, contentType = { _, _ -> "tile" }) { j, c ->
                val i = genres.size + j
                CategoryTile(c, Modifier.focusRequester(requesters[i]), onFocus = { last.intValue = i }) { onOpenDest(Dest.Category(c)) }
            }
        }
    }
}

/**
 * One [FocusRequester] per card, kept as the list grows (a page more of posters) so a request already on its way
 * never holds one that no card carries any more.
 */
@Composable
private fun rememberRequesters(count: Int): List<FocusRequester> {
    val all = remember { ArrayList<FocusRequester>() }
    while (all.size < count) all.add(FocusRequester())
    return all
}

/** Puts the remote on tile [wanted] once the page is up (scrolling it into view first): the first visit, or coming back from a category. */
@Composable
private fun FocusTile(grid: LazyGridState, requesters: List<FocusRequester>, wanted: Int, gridIndex: (Int) -> Int, enabled: Boolean) {
    LaunchedEffect(Unit) {
        if (!enabled) return@LaunchedEffect
        // Laid out first: before that nothing counts as visible and the page would scroll its heading away for nothing.
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == gridIndex(wanted) }) runCatching { grid.scrollToItem(gridIndex(wanted)) }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { requesters[wanted].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/** A flat, token-coloured tile: the name and what it holds. No image, no gradient; focus is the shared ring inside its edge. */
@Composable
private fun CategoryTile(category: Category, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.m)
    Column(
        modifier.fillMaxWidth().height(TileHeight)
            .focusCard(shape = shape, onFocus = onFocus, onClick = onClick)
            .background(Tokens.Colors.surface2)
            .padding(horizontal = Tokens.Space.l),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(category.label, style = Tokens.Type.title, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(category.caption, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
    }
}

// ─── One category ────────────────────────────────────────────────────────────────────────────

private const val PosterColumns = 6

/**
 * The films and shows of one category (a page above the tabs, so the side nav is hidden): most popular first, films and
 * shows interleaved, with All / Films / Shows tabs when it has both. Pages of 20 per kind load as the grid nears its end.
 * Coming back from a title the remote returns to the poster it left (its index and how many pages were loaded are saved).
 */
@Composable
fun TvCategoryPage(dest: Dest.Category, onOpen: (TitleSummary) -> Unit) {
    val category = dest.category
    val catalog = rememberCatalog()
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    // Pages loaded before leaving: asked for again at once on return (cached by TMDB repository), so the poster focused before exists.
    val pagesSeen = rememberSaveable { mutableIntStateOf(1) }
    val wanted = rememberSaveable { mutableIntStateOf(0) }
    val paging = remember(category) { TitlePaging(pagesSeen.intValue, { pagesSeen.intValue = it }) { page -> categoryPage(catalog, category, page) } }
    LaunchedEffect(paging, paging.request) { paging.next() }

    val hidden = rememberHiddenKeys()
    val shown = remember(paging.items.size, kind, hidden) {
        paging.items.filter { it.ref.toString() !in hidden }.filter {
            when (kind) {
                SearchKind.All -> true
                SearchKind.Films -> it.ref.type == MediaType.MOVIE
                SearchKind.Shows -> it.ref.type == MediaType.TV
            }
        }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val requesters = rememberRequesters(shown.size)
    // The focused poster is named in one line above the grid; read only by that line.
    val focused = remember { mutableStateOf<TitleSummary?>(null) }
    var restored by remember { mutableStateOf(false) }

    // The first time posters are there and loading has settled: the poster focused before, or the first.
    val settled = shown.isNotEmpty() && !paging.loading
    LaunchedEffect(settled) {
        if (!settled || restored) return@LaunchedEffect
        restored = true
        val at = GridNav.restore(wanted.intValue, shown.size)
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == at }) runCatching { grid.scrollToItem(at) }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { requesters[at].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val nearEnd by remember { androidx.compose.runtime.derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - PosterColumns * 2 } }
    LaunchedEffect(nearEnd, shown.size) { if (nearEnd) paging.loadMore() }

    val side = Tokens.Space.tvSafeH
    val retryFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Tokens.Colors.canvas).padding(horizontal = side)) {
        Spacer(Modifier.height(Tokens.Space.tvSafeV))
        Text(category.label, style = Tokens.Type.hero, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            category.caption + " · most popular first", style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
            maxLines = 1, modifier = Modifier.padding(bottom = Tokens.Space.s),
        )
        // One kind only (Horror has no shows): nothing to switch between.
        if (category.types.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                SearchKind.entries.forEach { k -> TvTab(k.label, null, selected = kind == k) { kind = k; restored = true; focused.value = null } }
            }
        } else {
            Spacer(Modifier.height(36.dp))
        }
        FocusedTitleLine(focused, Modifier.padding(top = Tokens.Space.s))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                shown.isNotEmpty() -> TvColumnGlide(reveal = Tokens.Space.s) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(PosterColumns),
                        state = grid,
                        contentPadding = PaddingValues(top = Tokens.Space.s, bottom = Tokens.Space.tvSafeV),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.ref.toString() }, contentType = { _, _ -> "poster" }) { i, item ->
                            PosterCard(
                                item, width = null, onClick = { onOpen(item) },
                                modifier = Modifier.focusRequester(requesters[i]).gridMoves(i, shown.size, grid, scope, requesters),
                                showText = false, onFocus = { focused.value = item; wanted.intValue = i },
                            )
                        }
                    }
                }
                paging.failed != null -> {
                    RequestFocusOnce(retryFocus)
                    EmptyState(Icons.Rounded.Refresh, "Couldn't load ${category.label}", paging.failed.orEmpty()) {
                        ReelButton("Try again", paging::retry, Modifier.focusRequester(retryFocus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                    }
                }
                paging.loading || !paging.started -> LoadingBox()
                else -> EmptyState(Icons.Rounded.Movie, "Nothing here yet", "TMDB has no popular ${category.label} titles right now.")
            }
        }
    }
}

/**
 * Remote moves between posters from [GridNav]: ▼ from a short last row lands on its last poster, and a row that has scrolled
 * out of composition is brought in before it takes focus. Keys [GridNav] has no answer for (the grid's edges) go on as usual.
 */
private fun Modifier.gridMoves(index: Int, size: Int, grid: LazyGridState, scope: kotlinx.coroutines.CoroutineScope, requesters: List<FocusRequester>): Modifier =
    onPreviewKeyEvent { e ->
        val dir = when (e.key) {
            Key.DirectionDown -> GridNav.Dir.Down
            Key.DirectionUp -> GridNav.Dir.Up
            Key.DirectionLeft -> GridNav.Dir.Left
            Key.DirectionRight -> GridNav.Dir.Right
            else -> return@onPreviewKeyEvent false
        }
        val target = GridNav.move(index, PosterColumns, size, dir) ?: return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) scope.launch {
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target)
            repeat(5) {
                withFrameNanos { }
                if (runCatching { requesters[target].requestFocus() }.getOrDefault(false)) return@launch
            }
        }
        true
    }

/** One page of a category: its films and shows asked together and interleaved; one kind failing leaves the other's titles. */
private suspend fun categoryPage(catalog: Catalog, c: Category, page: Int): List<TitleSummary> = coroutineScope {
    val parts = c.types.map { type ->
        async {
            runCatching {
                val language = c.language
                if (language != null) catalog.byLanguage(type, language, page) else catalog.byGenre(type, c.genreFor(type) ?: error("no ${type.path} genre"), page)
            }
        }
    }.map { it.await() }
    val ok = parts.mapNotNull { it.getOrNull() }
    if (ok.isEmpty()) parts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    Interleave.alternate(ok.getOrElse(0) { emptyList() }, ok.getOrElse(1) { emptyList() })
}

/** Pages of a category (up to 10), the first [startPages] together so a page left earlier comes back as long as it was. */
private class TitlePaging(private val startPages: Int, private val onPage: (Int) -> Unit, private val load: suspend (page: Int) -> List<TitleSummary>) {
    val items = mutableStateListOf<TitleSummary>()
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    /** False until the first page has been asked for (so the page shows a spinner, not "nothing here"). */
    var started by mutableStateOf(false)
        private set
    private var page = 0
    private var end = false
    var request by mutableIntStateOf(0)
        private set

    fun loadMore() {
        if (!loading && !end && failed == null && page < MAX_PAGES) request++
    }

    fun retry() {
        failed = null
        request++
    }

    suspend fun next() {
        if (loading || end) return
        loading = true
        started = true
        try {
            val count = if (page == 0) startPages.coerceIn(1, MAX_PAGES) else 1
            for (n in 0 until count) {
                val more = load(page + 1)
                page++
                val have = items.map { it.ref }.toSet()
                items.addAll(more.filter { it.ref !in have })
                if (more.isEmpty() || page >= MAX_PAGES) {
                    end = true
                    break
                }
            }
            onPage(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Couldn't load this category"
        } finally {
            loading = false
        }
    }

    companion object {
        const val MAX_PAGES = 10
    }
}
