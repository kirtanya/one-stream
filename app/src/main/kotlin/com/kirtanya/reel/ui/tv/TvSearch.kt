package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.text.input.ImeAction
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.phone.SearchResults
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

@Composable
fun TvSearch(onOpen: (TitleSummary) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(500)
        debounced = query.trim()
    }
    val field = remember { FocusRequester() }
    RequestFocusOnce(field)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        Spacer(Modifier.height(TvTopBarHeight))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(0.5f).padding(horizontal = Tokens.Space.tvSafeH).focusRequester(field),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Search films and shows") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        SearchResults(debounced, columns = GridCells.Fixed(8), sidePadding = Tokens.Space.tvSafeH, onOpen = onOpen)
    }
}
