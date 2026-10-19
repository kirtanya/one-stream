package com.kirtanya.reel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.theme.Tokens

/** 2:3 poster with title and year underneath. */
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
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        RemoteImage(
            url = TmdbImages.poster(item.posterPath),
            // [modifier] goes on the focusable image so focus requesters land on it.
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).focusCard(onFocus = onFocus, onClick = onClick),
        )
        if (showText) {
            Text(item.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metaLine(item), style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 1)
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
    firstFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text, modifier = Modifier.padding(horizontal = sidePadding))
        LazyRow(
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

fun metaLine(item: TitleSummary): String = listOfNotNull(
    item.year?.toString(),
    if (item.ref.type.name == "TV") "Series" else "Film",
    item.rating?.let { "★ %.1f".format(it) },
).joinToString(" · ")
