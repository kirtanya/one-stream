package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.theme.Tokens

/**
 * 2:3 poster (radius 12, hairline) with title and a quiet meta line underneath. Long-press (hold
 * OK on the remote) opens its options: My list, Trailer, Share, Go to title.
 */
@Composable
fun PosterCard(
    item: TitleSummary,
    /** Fixed width, or null to fill the cell (grids). */
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    onFocus: (() -> Unit)? = null,
    /** The IMDb pill in the corner (TV: only when the rating is already cached). */
    badge: Boolean = true,
) {
    var options by remember { mutableStateOf(false) }
    if (options) PosterOptions(item, onOpen = onClick, onDismiss = { options = false })
    PosterCardBody(item, width, onClick, { options = true }, modifier, showText, onFocus, badge)
}

/** [PosterCard] without its own options state: a row that owns one dialog for all its cards passes [onLongClick]. */
@Composable
internal fun PosterCardBody(
    item: TitleSummary,
    width: Dp?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    showText: Boolean,
    onFocus: (() -> Unit)?,
    badge: Boolean,
    outset: Boolean = false,
) {
    val shape = RoundedCornerShape(Tokens.Radius.m)
    val tv = LocalIsTv.current
    // [modifier] goes on the focusable image so focus requesters land on it.
    val size = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    val frame = modifier
        .then(if (showText) Modifier.fillMaxWidth() else size)
        .aspectRatio(2f / 3f)
        .focusCard(shape = shape, onFocus = onFocus, onLongClick = onLongClick, outset = outset, onClick = onClick)
        // TV: no hairline (a stroked path per card); the dim and the ring carry the edge.
        .then(if (tv) Modifier else Modifier.border(Tokens.Size.hairline, Tokens.Colors.hairline, shape))
    val image: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        if (item.posterPath != null) {
            RemoteImage(url = TmdbImages.poster(item.posterPath), modifier = Modifier.fillMaxSize())
        } else {
            // No poster: the name on a tonal card, so the grid never shows a blank hole.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tokens.Colors.accentDeep, Tokens.Colors.surface2))).padding(Tokens.Space.s), contentAlignment = Alignment.BottomStart) {
                Text(item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (badge) PosterImdbBadge(item.ref, Modifier.align(Alignment.TopStart).padding(Tokens.Space.s - Tokens.Space.xxs))
    }
    if (!showText) {
        Box(frame) { image() }
        return
    }
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Box(frame) { image() }
        Text(item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(metaLine(item), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A row's heading. TV: the larger title style in a fixed-height band, so every row's cards share one top line (the
 * column glides to it, see [rememberHomeColumnSpec]); phone: the usual section header.
 */
@Composable
internal fun RowHeader(title: String, sidePadding: Dp, onSeeAll: (() -> Unit)? = null) {
    if (!LocalIsTv.current) {
        SectionHeader(title, Modifier.padding(horizontal = sidePadding), action = if (onSeeAll != null) "See all" else null, onAction = onSeeAll)
        return
    }
    Row(Modifier.fillMaxWidth().height(Tokens.Cinema.tvRowHeader).padding(horizontal = sidePadding), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Tokens.Type.title, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "See all", style = Tokens.Type.label, color = Tokens.Colors.text,
                modifier = Modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = onSeeAll).padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.xs),
            )
        }
    }
}

/** A row's cards: the gap between them (TV leaves room for the focus ring) and the padding that keeps rings uncut. */
internal val rowGap: Dp @Composable get() = if (LocalIsTv.current) Tokens.Space.tvCardGap else Tokens.Space.m

@Composable
fun PosterRow(
    title: String,
    items: List<TitleSummary>,
    posterWidth: Dp,
    sidePadding: Dp,
    onOpen: (TitleSummary) -> Unit,
    showText: Boolean = true,
    onFocus: ((TitleSummary) -> Unit)? = null,
    /** Attached to the first poster, so a screen can put the remote's focus there. */
    firstFocus: FocusRequester? = null,
    onSeeAll: (() -> Unit)? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** IMDb pills on the posters (the TV Home shows the rating in its info block instead). */
    badges: Boolean = true,
) {
    val tv = LocalIsTv.current
    // One options dialog for the whole row, not one state per poster.
    var optionsFor by remember { mutableStateOf<TitleSummary?>(null) }
    optionsFor?.let { PosterOptions(it, onOpen = { onOpen(it) }, onDismiss = { optionsFor = null }) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        RowHeader(title, sidePadding, onSeeAll)
        FixedFocusRow(sidePadding) {
            LazyRow(
                // TV: up/down between rows comes back to the card last focused in this row.
                modifier = Modifier.focusRestorer(),
                state = listState,
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
                horizontalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                itemsIndexed(items, key = { _, it -> it.ref.toString() }, contentType = { _, _ -> "poster" }) { i, item ->
                    val m = (if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier).rowStart(i == 0)
                    PosterCardBody(
                        item, posterWidth, onClick = { onOpen(item) }, onLongClick = { optionsFor = item }, modifier = m, showText = showText,
                        onFocus = onFocus?.let { { it(item) } }, badge = badges, outset = tv,
                    )
                }
            }
        }
    }
}

/**
 * Under a poster: "Film · 2024" / "Series · 2016". No TMDB score: the IMDb pill on the poster carries the
 * rating (decided 2026-10-24, so two different scores never sit on one card).
 */
fun metaLine(item: TitleSummary): String = listOfNotNull(
    if (item.ref.type == MediaType.TV) "Series" else "Film",
    item.year?.toString(),
).joinToString(" · ")

/**
 * Long-press on a poster: Add to / Remove from My list, Trailer (once the title's videos are in and
 * it has one), Share, Go to title.
 */
@Composable
internal fun PosterOptions(item: TitleSummary, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (saved, toggle) = rememberWatchlistToggle(item)
    val catalog = rememberCatalog()
    val (details, _) = rememberLoad(item.ref) { catalog.details(item.ref) }
    val trailer = (details as? Load.Ready)?.value?.trailer
    val actions = listOfNotNull(
        OptionAction(watchlistIcon(saved), if (saved) "Remove from My list" else "Add to My list", toggle),
        trailer?.let { v -> OptionAction(Icons.Rounded.OndemandVideo, "Trailer", { openVideo(context, v) }, detail = "Opens YouTube") },
        OptionAction(Icons.Rounded.Share, "Share", { com.kirtanya.reel.ui.title.shareTitle(context, item) }),
        OptionAction(Icons.Rounded.Info, "Go to title", onOpen),
    )
    OptionsSheet(OptionsHeader(item.title, metaLine(item), item.posterPath?.let { TmdbImages.poster(it, "w185") }, poster = true), actions, onDismiss)
}
