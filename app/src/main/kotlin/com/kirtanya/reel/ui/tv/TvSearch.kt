package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.GenreBrowser
import com.kirtanya.reel.ui.phone.SearchField
import com.kirtanya.reel.ui.phone.SearchKind
import com.kirtanya.reel.ui.phone.SearchResults
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Posters across: 7, like the collection grids (111 dp wide, big enough to recognise without a name under them). */
private const val COLUMNS = 7

/**
 * TV search, the way Netflix lays it out: the field and All · Films · Shows on top, the focused title named in one line
 * above the poster grid (no text under the posters), genres to browse until you type. ↓ in the grid lands on the first
 * poster of the next row ([SearchResults] `rowStartOnDown`).
 */
@Composable
fun TvSearch(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit = {}, initialQuery: String = "") {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var debounced by rememberSaveable { mutableStateOf(initialQuery) }
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    LaunchedEffect(query) {
        delay(500)
        debounced = query.trim()
    }
    val field = remember { FocusRequester() }
    RequestFocusOnce(field)
    // The focused poster; read only by the info line, so a focus move redraws one line of text.
    val focused = remember { mutableStateOf<TitleSummary?>(null) }
    LaunchedEffect(debounced, kind) { focused.value = null }
    val side = Tokens.Space.tvSafeH

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Tokens.Space.tvSafeV))
            Row(Modifier.padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.width(440.dp).focusRequester(field))
                // The same text tabs as the top bar.
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    SearchKind.entries.forEach { k -> TvTab(k.label, null, selected = kind == k) { kind = k } }
                }
            }
            FocusedTitleLine(focused, Modifier.padding(start = side, end = side, top = Tokens.Space.m))
            SearchResults(
                debounced, kind, GridCells.Fixed(COLUMNS), side, Tokens.Space.tvSafeV, onOpen = onOpen, rowStartOnDown = COLUMNS,
                showText = false, onFocusItem = { focused.value = it },
                browse = {
                    // The chips scroll sideways from edge to edge: the row is laid out wider than the grid's padding and shifted
                    // back, so chips don't vanish at the padding line.
                    Box(Modifier.layout { m, c ->
                        val extra = side.roundToPx() * 2
                        val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                        layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
                    }) {
                        TvRowPivot(side) {
                            GenreBrowser(kind, rows = 1, onOpen = { g, type -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = type)) }, sidePadding = side)
                        }
                    }
                },
            )
        }
    }
}
