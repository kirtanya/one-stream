package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.GenreBrowser
import com.kirtanya.reel.ui.phone.KindChips
import com.kirtanya.reel.ui.phone.SearchField
import com.kirtanya.reel.ui.phone.SearchKind
import com.kirtanya.reel.ui.phone.SearchResults
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/** TV search: the field and All · Films · Shows on one line; genres to browse until you type; results 8 across. */
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

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.3f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Spacer(Modifier.height(TvTopBarHeight))
            Row(Modifier.padding(horizontal = Tokens.Space.tvSafeH), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.fillMaxWidth(0.5f).focusRequester(field))
                KindChips(kind, { kind = it })
            }
            SearchResults(
                debounced, kind, GridCells.Fixed(8), Tokens.Space.tvSafeH, Tokens.Space.tvSafeV, onOpen = onOpen,
                browse = { GenreBrowser(kind, rows = 1, onOpen = { g, type -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = type)) }, Modifier.fillMaxWidth()) },
            )
        }
    }
}
