package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.CancellationException

/**
 * A grid of titles: "See all" for a Home row, everything on one streaming service, or a genre
 * (most popular first; more load as you scroll).
 */
@Composable
fun CollectionScreen(dest: Dest.Collection, isTv: Boolean, onBack: () -> Unit, onOpen: (TitleSummary) -> Unit) {
    val catalog = rememberCatalog()
    if (dest.genre != null) {
        val paging = rememberGenrePaging(dest, catalog)
        val subtitle = (if (dest.genreType == MediaType.TV) "Shows" else "Films") + " · most popular first"
        TitleGrid(
            isTv = isTv, onBack = onBack, onOpen = onOpen, items = paging.items,
            backdrop = paging.items.firstOrNull { it.backdropPath != null }?.backdropPath,
            poster = paging.items.firstOrNull()?.posterPath,
            header = { GridHeading(dest.title, subtitle, isTv) },
            onNearEnd = paging::loadMore,
            footer = {
                when {
                    paging.failed != null && paging.items.isEmpty() -> ErrorBox(paging.failed!!, paging::retry, Modifier.height(240.dp))
                    paging.failed != null -> ReelButton("Couldn't load more · Try again", paging::retry, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh)
                    paging.loading || !paging.started -> LoadingBox(Modifier.height(if (paging.items.isEmpty()) 240.dp else 96.dp))
                    paging.items.isEmpty() -> EmptyState(Icons.Rounded.Movie, "Nothing here yet", "TMDB has no popular titles in this genre right now.")
                }
            },
        )
        return
    }
    val (load, retry) = rememberLoad(dest) { dest.items ?: dest.provider?.let { catalog.onService(it.id) }.orEmpty() }
    val items = (load as? Load.Ready)?.value.orEmpty()
    TitleGrid(
        isTv = isTv, onBack = onBack, onOpen = onOpen, items = items,
        backdrop = items.firstOrNull { it.backdropPath != null }?.backdropPath,
        poster = items.firstOrNull()?.posterPath,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                dest.provider?.let { RingAvatar(TmdbImages.logo(it.logoPath, "w154"), null, if (isTv) 56.dp else 48.dp) }
                GridHeading(
                    dest.title,
                    when {
                        load !is Load.Ready -> null
                        dest.provider != null -> "Streaming in India · ${com.kirtanya.reel.ui.plural(items.size, "title")}"
                        else -> com.kirtanya.reel.ui.plural(items.size, "title")
                    },
                    isTv,
                    Modifier.weight(1f, fill = false),
                )
            }
        },
        footer = {
            when (load) {
                Load.Loading -> LoadingBox(Modifier.height(240.dp))
                is Load.Failed -> ErrorBox(load.message, { retry() }, Modifier.height(240.dp))
                is Load.Ready -> if (items.isEmpty()) EmptyState(Icons.Rounded.Movie, "Nothing here yet", "Titles you add will show up here.")
            }
        },
    )
}

/** A cast member: photo, name, what they're known for, their story, and a grid of their films and shows. */
@Composable
fun PersonScreen(dest: Dest.Person, isTv: Boolean, onBack: () -> Unit, onOpen: (TitleSummary) -> Unit) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(dest.id) { catalog.person(dest.id) }
    val person = (load as? Load.Ready)?.value
    val credits = person?.credits.orEmpty()
    var bioOpen by rememberSaveable { mutableStateOf(false) }
    TitleGrid(
        isTv = isTv, onBack = onBack, onOpen = onOpen, items = credits,
        backdrop = credits.firstOrNull { it.backdropPath != null }?.backdropPath,
        poster = credits.firstOrNull()?.posterPath,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
                    RingAvatar(TmdbImages.profile(person?.profilePath, "w342"), null, if (isTv) 96.dp else 88.dp, placeholder = dest.name.take(1))
                    GridHeading(dest.name, person?.knownFor?.let(::knownForLabel), isTv, Modifier.weight(1f, fill = false))
                }
                person?.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                    Text(
                        bio,
                        style = Tokens.Type.body,
                        color = Tokens.Colors.textSecondary,
                        maxLines = if (bioOpen) Int.MAX_VALUE else if (isTv) 3 else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 720.dp).focusCard(shape = RoundedCornerShape(Tokens.Radius.s), scale = 1f, onClick = { bioOpen = !bioOpen }),
                    )
                }
                if (credits.isNotEmpty()) SectionHeader("Films and shows")
            }
        },
        footer = {
            when (load) {
                Load.Loading -> LoadingBox(Modifier.height(240.dp))
                is Load.Failed -> ErrorBox(load.message, { retry() }, Modifier.height(240.dp))
                is Load.Ready -> if (credits.isEmpty()) EmptyState(Icons.Rounded.Movie, "No films or shows listed", "TMDB doesn't list any credits for ${dest.name} yet.")
            }
        },
    )
}

/** TMDB's department → what to call the person. */
private fun knownForLabel(department: String): String = when (department) {
    "Acting" -> "Actor"
    "Directing" -> "Director"
    "Writing" -> "Writer"
    "Production" -> "Producer"
    else -> "Known for $department"
}

@Composable
private fun GridHeading(title: String, subtitle: String?, isTv: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
        Text(title, style = if (isTv) Tokens.Type.hero else Tokens.Type.display, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The page every grid shares: a backdrop behind the top (scrolling content slides over it), the
 * poster's colour as the app's, a heading, posters (3 across on the phone, 7 on TV) and a footer
 * for loading / errors / "nothing here".
 */
@Composable
private fun TitleGrid(
    isTv: Boolean,
    onBack: () -> Unit,
    onOpen: (TitleSummary) -> Unit,
    items: List<TitleSummary>,
    backdrop: String?,
    poster: String?,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    onNearEnd: (() -> Unit)? = null,
) {
    rememberAmbientColor(TmdbImages.poster(poster, "w185"))
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val firstFocus = remember { FocusRequester() }
    val first = items.firstOrNull()
    if (isTv && first != null) RequestFocusOnce(firstFocus, key = first.ref)
    val grid = rememberLazyGridState()
    if (onNearEnd != null) {
        val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
        LaunchedEffect(nearEnd, items.size) { if (nearEnd) onNearEnd() }
    }
    val bottom: Dp = if (isTv) Tokens.Space.tvSafeV else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Tokens.Space.xxl

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        Box(Modifier.fillMaxWidth().height(if (isTv) 360.dp else 280.dp)) {
            HeroImage(listOfNotNull(TmdbImages.backdrop(backdrop, "w1280")), Modifier.fillMaxSize(), kenBurns = false)
            // The heading sits on the picture: a dim wash, then the usual fades.
            Box(Modifier.matchParentSize().background(Tokens.Gradients.backdropDim))
            HeroScrims(top = !isTv, start = isTv)
        }
        LazyVerticalGrid(
            columns = if (isTv) GridCells.Fixed(7) else GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.tvSafeV else 0.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    // TV: room under the heading for the first poster's focus lift.
                    Modifier.then(if (isTv) Modifier.padding(top = Tokens.Space.xxxl, bottom = Tokens.Space.m) else Modifier.statusBarsPadding().padding(top = 96.dp)),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                ) { header() }
            }
            posterCells(items, firstFocus, onOpen)
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { footer() } }
        }
        if (!isTv) {
            TopFade()
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
        }
    }
}

private fun LazyGridScope.posterCells(items: List<TitleSummary>, firstFocus: FocusRequester, onOpen: (TitleSummary) -> Unit) {
    itemsIndexed(items, key = { _, it -> it.ref.toString() }) { i, item ->
        PosterCard(item, width = null, onClick = { onOpen(item) }, modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
    }
}

/** Pages of a genre (20 a page, up to 10 pages), fetched as the grid nears its end. */
private class GenrePaging(private val load: suspend (page: Int) -> List<TitleSummary>) {
    val items = mutableStateListOf<TitleSummary>()
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    /** False until the first page has been asked for (so the grid shows a spinner, not "nothing here"). */
    var started by mutableStateOf(false)
        private set
    private var page by mutableIntStateOf(0)
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
            val more = load(page + 1)
            page++
            val have = items.map { it.ref }.toSet()
            items.addAll(more.filter { it.ref !in have })
            if (more.isEmpty() || page >= MAX_PAGES) end = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Couldn't load this genre"
        } finally {
            loading = false
        }
    }

    companion object {
        const val MAX_PAGES = 10
    }
}

@Composable
private fun rememberGenrePaging(dest: Dest.Collection, catalog: Catalog): GenrePaging {
    val paging = remember(dest) { GenrePaging { page -> catalog.byGenre(dest.genreType ?: MediaType.MOVIE, dest.genre!!.id, page) } }
    LaunchedEffect(paging, paging.request) { paging.next() }
    return paging
}
