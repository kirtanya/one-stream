package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * OK on the remote) adds it to My list or takes it off.
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
) {
    val shape = RoundedCornerShape(Tokens.Radius.m)
    val toggleSaved = rememberWatchlistToggler()
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        // [modifier] goes on the focusable image so focus requesters land on it.
        val frame = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .focusCard(
                shape = shape, scale = Tokens.Motion.posterFocusScale, onFocus = onFocus, onLongClick = { toggleSaved(item) },
                origin = if (showText) androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) else androidx.compose.ui.graphics.TransformOrigin.Center,
                onClick = onClick,
            )
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
        if (item.posterPath != null) {
            RemoteImage(url = TmdbImages.poster(item.posterPath), modifier = frame)
        } else {
            // No poster: the name on a tonal card, so the grid never shows a blank hole.
            Box(frame.background(Brush.verticalGradient(listOf(Tokens.Colors.accentDeep, Tokens.Colors.surface2))).padding(Tokens.Space.s), contentAlignment = Alignment.BottomStart) {
                Text(item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showText) {
            Text(item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metaLine(item), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

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
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader(title, Modifier.padding(horizontal = sidePadding), action = if (onSeeAll != null) "See all" else null, onAction = onSeeAll)
        LazyRow(
            // TV: up/down between rows comes back to the card last focused in this row.
            modifier = Modifier.focusRestorer(),
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            itemsIndexed(items, key = { _, it -> it.ref.toString() }) { i, item ->
                val m = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                PosterCard(item, posterWidth, onClick = { onOpen(item) }, modifier = m, showText = showText, onFocus = onFocus?.let { { it(item) } })
            }
        }
    }
}

/** Under a poster (a third of a phone's width): "★ 8.2 · 2024", "Series · 2016" when there's no rating. */
fun metaLine(item: TitleSummary): String = listOfNotNull(
    item.rating?.let { "★ %.1f".format(it) } ?: if (item.ref.type == MediaType.TV) "Series" else "Film",
    item.year?.toString(),
).joinToString(" · ")
