package com.kirtanya.reel.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.PosterCard
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

@Composable
fun PhoneSearch(onOpen: (TitleSummary) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        delay(400)
        debounced = query.trim()
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.phoneGutter).padding(top = Tokens.Space.l).focusRequester(focus),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Films and shows") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        SearchResults(debounced, columns = GridCells.Adaptive(96.dp), sidePadding = Tokens.Space.phoneGutter, onOpen = onOpen)
    }
}

/** Results grid for [query]; trending titles when it's empty. Shared by phone and TV. */
@Composable
fun SearchResults(
    query: String,
    columns: GridCells,
    sidePadding: androidx.compose.ui.unit.Dp,
    onOpen: (TitleSummary) -> Unit,
    focusFirst: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(query) {
        if (query.isEmpty()) tmdb.home().firstOrNull()?.items.orEmpty() else tmdb.search(query)
    }
    LoadView(load, retry) { results ->
        Column {
            Text(
                when {
                    query.isEmpty() -> "Trending this week"
                    results.isEmpty() -> "Nothing found for “$query”"
                    else -> "${results.size} results"
                },
                style = Tokens.Type.label,
                color = Tokens.Colors.textMuted,
                modifier = Modifier.padding(horizontal = sidePadding),
            )
            LazyVerticalGrid(
                columns = columns,
                contentPadding = PaddingValues(sidePadding),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                itemsIndexed(results, key = { _, it -> it.ref.toString() }) { i, item ->
                    val m = if (i == 0 && focusFirst != null) Modifier.focusRequester(focusFirst) else Modifier
                    PosterCard(item, width = null, onClick = { onOpen(item) }, modifier = m)
                }
            }
        }
    }
}

