package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.PosterCard
import com.kirtanya.reel.ui.common.metaLine
import com.kirtanya.reel.ui.common.rowStart
import com.kirtanya.reel.ui.theme.Tokens

// TV posters carry no text (SMOOTH_UI_PLAN §2.7): the focused title is named in one line instead, as Netflix and
// Hotstar do. The focused title lives in a State that only these small composables read, so a focus move
// redraws one line of text, never the grid or the row.

/** Height of [FocusedTitleLine]: fixed, so the name appearing never moves the grid under it. */
val FocusedTitleLineHeight = 28.dp

/** "Dune: Part Two   Film · 2024", one line; empty (but the same height) while nothing is focused. */
@Composable
fun FocusedTitleLine(focused: State<TitleSummary?>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(FocusedTitleLineHeight), contentAlignment = Alignment.CenterStart) {
        val t = focused.value ?: return@Box
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.Bottom) {
            Text(t.title, style = Tokens.Type.title, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(metaLine(t), style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
        }
    }
}

/** The focused title's name, small, for a row header's right side. */
@Composable
private fun FocusedName(focused: State<TitleSummary?>, modifier: Modifier = Modifier) {
    val t = focused.value
    Text(
        t?.let { "${it.title}  ·  ${metaLine(it)}" }.orEmpty(),
        style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A row of text-less posters under a heading; the focused one is named beside the heading. Focus lands on the first
 * poster for ↓ ([rowStart]); the row slides under a fixed start line ([TvRowPivot]).
 */
@Composable
fun TvPosterRow(
    title: String,
    items: List<TitleSummary>,
    side: Dp,
    posterWidth: Dp,
    onOpen: (TitleSummary) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    val focused = remember { mutableStateOf<TitleSummary?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Row(Modifier.padding(horizontal = side).height(Tokens.Size.button), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1)
            FocusedName(focused, Modifier.weight(1f))
            if (onSeeAll != null) TvTab("See all", null, selected = false, onClick = onSeeAll)
        }
        TvRowPivot(side) {
            LazyRow(
                Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            ) {
                itemsIndexed(items, key = { _, it -> it.ref.toString() }, contentType = { _, _ -> "poster" }) { i, item ->
                    PosterCard(item, posterWidth, onClick = { onOpen(item) }, modifier = Modifier.rowStart(i == 0), showText = false, onFocus = { focused.value = item })
                }
            }
        }
    }
}
