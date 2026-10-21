package com.kirtanya.reel.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.metaLine
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.common.rememberWatchlistToggler
import com.kirtanya.reel.ui.theme.Tokens

/**
 * "Because you watched X": TMDB's recommendations for the newest title in your history (films
 * and shows you finished count too). Null until there's history or when TMDB has nothing.
 */
@Composable
fun rememberBecauseYouWatched(): Pair<String, List<TitleSummary>>? {
    val history = ReelApp.get(LocalContext.current).history
    val entries by history.entries.collectAsState()
    val latest = remember(entries) {
        entries.filter { it.playback.tmdbId != null }.maxByOrNull { it.updatedAt }?.playback
    } ?: return null
    val ref = TitleRef(if (latest.tmdbType == "tv") MediaType.TV else MediaType.MOVIE, latest.tmdbId!!)
    val catalog = rememberCatalog()
    val (load, _) = rememberLoad(ref) { catalog.details(ref).recommendations }
    val items = (load as? Load.Ready)?.value?.filterNot { it.ref == ref }.orEmpty()
    return if (items.isEmpty()) null else "Because you watched ${latest.title}" to items
}

/** Continue watching cards as titles (for the hero), skipping files with no TMDB title. */
fun continueTitles(items: List<ContinueItem>): List<TitleSummary> = items.mapNotNull { c ->
    val p = c.entry.playback
    val id = p.tmdbId ?: return@mapNotNull null
    TitleSummary(TitleRef(if (p.tmdbType == "tv") MediaType.TV else MediaType.MOVIE, id), p.title, p.year, p.posterPath, p.backdropPath, rating = null, overview = p.subtitle.orEmpty())
}

/** Poster width for phone rows, from Settings → Home screen → Size. */
val phonePosterWidth: Dp get() = if (HomeLayout.density == Density.Compact) Tokens.Cinema.posterCompact else Tokens.Cinema.posterRoomy

/** A Home row in the chosen look: posters, or wide 16:9 cards. */
@Composable
fun TitleRow(
    title: String,
    items: List<TitleSummary>,
    sidePadding: Dp,
    onOpen: (TitleSummary) -> Unit,
    isTv: Boolean,
    onSeeAll: (() -> Unit)? = null,
    onFocus: ((TitleSummary) -> Unit)? = null,
    firstFocus: FocusRequester? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    if (HomeLayout.posterStyle == PosterStyle.Posters) {
        PosterRow(
            title, items, posterWidth = if (isTv) Tokens.Cinema.posterTv else phonePosterWidth, sidePadding = sidePadding, onOpen = onOpen,
            showText = !isTv, onFocus = onFocus, firstFocus = firstFocus, onSeeAll = onSeeAll, listState = listState,
        )
        return
    }
    val width = if (isTv) Tokens.Cinema.wideCardTv else Tokens.Cinema.wideCardPhone
    val toggleSaved = rememberWatchlistToggler()
    val shape = RoundedCornerShape(Tokens.Radius.media)
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader(title, Modifier.padding(horizontal = sidePadding), action = if (onSeeAll != null) "See all" else null, onAction = onSeeAll)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            itemsIndexed(items, key = { _, it -> it.ref.toString() }) { i, item ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Box(
                        Modifier
                            .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .focusCard(
                                shape = shape, scale = Tokens.Motion.posterFocusScale, origin = TransformOrigin(0.5f, 1f),
                                onFocus = onFocus?.let { { it(item) } }, onLongClick = { toggleSaved(item) }, onClick = { onOpen(item) },
                            )
                            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
                    ) {
                        RemoteImage(TmdbImages.backdrop(item.backdropPath, "w780") ?: TmdbImages.poster(item.posterPath, "w342"), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Tokens.Colors.scrim)))
                        Text(
                            item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(Tokens.Space.s),
                        )
                    }
                    Text(metaLine(item), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * "New episodes": shows on My list or in Continue watching with an episode out this past week or
 * coming soon (TMDB calendar). One card per show; empty when nothing's airing.
 */
@Composable
fun rememberNewEpisodes(shows: List<TitleRef>): List<com.kirtanya.reel.core.tmdb.CalendarEntry> {
    val catalog = rememberCatalog()
    val today = remember { java.time.LocalDate.now() }
    val key = shows.distinct().sortedBy { it.id }.take(30)
    val (load, _) = rememberLoad(key) { if (key.isEmpty()) emptyList() else catalog.calendar(key, today.minusDays(7), today.plusDays(14)) }
    return remember(load) {
        com.kirtanya.reel.core.browse.AirDates.pick((load as? Load.Ready)?.value.orEmpty(), { it.date }, { it.show.ref }, today)
    }
}

/** Wide cards: the episode's still, the show, "S2 E4 · Tomorrow". Opens the show. */
@Composable
fun NewEpisodesRow(
    entries: List<com.kirtanya.reel.core.tmdb.CalendarEntry>,
    sidePadding: Dp,
    onOpen: (TitleSummary) -> Unit,
    isTv: Boolean,
    onFocus: ((TitleSummary) -> Unit)? = null,
    firstFocus: FocusRequester? = null,
) {
    val today = remember { java.time.LocalDate.now() }
    val width = if (isTv) Tokens.Cinema.wideCardTv else Tokens.Cinema.wideCardPhone
    val shape = RoundedCornerShape(Tokens.Radius.media)
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader("New episodes", Modifier.padding(horizontal = sidePadding))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            itemsIndexed(entries, key = { _, e -> e.show.ref.toString() }) { i, e ->
                val upcoming = e.date.isAfter(today)
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Box(
                        Modifier
                            .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .focusCard(
                                shape = shape, scale = Tokens.Motion.posterFocusScale, origin = TransformOrigin(0.5f, 1f),
                                onFocus = onFocus?.let { { it(e.show) } }, onClick = { onOpen(e.show) },
                            )
                            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
                    ) {
                        RemoteImage(TmdbImages.still(e.episode.stillPath, "w780") ?: TmdbImages.backdrop(e.show.backdropPath, "w780"), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Tokens.Colors.scrim)))
                        com.kirtanya.reel.ui.common.Pill(
                            com.kirtanya.reel.core.browse.AirDates.label(e.date, today),
                            Modifier.align(Alignment.TopStart).padding(Tokens.Space.s),
                            accent = !upcoming,
                        )
                    }
                    Text(e.show.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(e.episode.label, e.episode.name).filter { it.isNotBlank() }.joinToString(" · "), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
