package com.kirtanya.reel.ui.phone

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.PosterCard
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

enum class SearchKind(val label: String) { All("All"), Films("Films"), Shows("Shows") }

@Composable
fun PhoneSearch(onOpen: (TitleSummary) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    var recents by remember { mutableStateOf(RecentSearches.load(context)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        delay(400)
        debounced = query.trim()
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.3f)
        Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            Text("Search", style = Tokens.Type.display, color = Tokens.Colors.text, modifier = Modifier.padding(start = Tokens.Space.phoneGutter, top = Tokens.Space.l))
            SearchField(query, { query = it }, Modifier.padding(horizontal = Tokens.Space.phoneGutter).focusRequester(focus), onSubmit = {
                if (query.isNotBlank()) recents = RecentSearches.add(context, query.trim())
            })
            Row(Modifier.padding(horizontal = Tokens.Space.phoneGutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                SearchKind.entries.forEach { k -> ReelButton(k.label, { kind = k }, kind = if (kind == k) ButtonKind.Primary else ButtonKind.Ghost) }
            }
            if (debounced.isEmpty() && recents.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SectionHeader("Recent", action = "Clear", onAction = { recents = RecentSearches.clear(context) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        recents.forEach { r -> ReelButton(r, { query = r; debounced = r }, kind = ButtonKind.Ghost, icon = Icons.Rounded.History) }
                    }
                }
            }
            SearchResults(
                debounced, kind, GridCells.Adaptive(104.dp), Tokens.Space.phoneGutter, Tokens.Space.bottomBarClearance + Tokens.Space.xl,
                onOpen = { item ->
                    if (debounced.isNotEmpty()) recents = RecentSearches.add(context, debounced)
                    onOpen(item)
                },
            )
        }
    }
}

/** Glass pill search field: icon, text, clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onSubmit: () -> Unit = {}, height: Dp = 56.dp) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Row(
        modifier.fillMaxWidth().height(height).clip(shape).background(Tokens.Colors.surface2).border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape)
            .padding(horizontal = Tokens.Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Tokens.Colors.textSecondary)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Films, shows, people…", style = Tokens.Type.body, color = Tokens.Colors.textTertiary)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = Tokens.Type.body.copy(color = Tokens.Colors.text),
                cursorBrush = SolidColor(Tokens.Colors.accentBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Clear", tint = Tokens.Colors.textSecondary,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(Tokens.Radius.pill)).clickable(interactionSource = null, indication = null) { onChange("") },
            )
        }
    }
}

/** Results grid for [query] (filtered by [kind]); trending titles when it's empty. Shared by phone and TV. */
@Composable
fun SearchResults(
    query: String,
    kind: SearchKind,
    columns: GridCells,
    sidePadding: Dp,
    bottomPadding: Dp,
    onOpen: (TitleSummary) -> Unit,
    focusFirst: FocusRequester? = null,
) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(query) {
        if (query.isEmpty()) tmdb.home().firstOrNull()?.items.orEmpty() else tmdb.search(query)
    }
    LoadView(load, retry) { all ->
        val results = all.filter {
            when (kind) {
                SearchKind.All -> true
                SearchKind.Films -> it.ref.type == MediaType.MOVIE
                SearchKind.Shows -> it.ref.type == MediaType.TV
            }
        }
        LazyVerticalGrid(
            columns = columns,
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = Tokens.Space.s, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (query.isNotEmpty() && results.isEmpty()) {
                    EmptyState(Icons.Rounded.Search, "Nothing found", "No ${if (kind == SearchKind.All) "titles" else kind.label.lowercase()} match “$query”. Try another spelling.")
                } else {
                    SectionHeader(if (query.isEmpty()) "Trending this week" else "${results.size} results")
                }
            }
            itemsIndexed(results, key = { _, it -> it.ref.toString() }) { i, item ->
                PosterCard(item, width = null, onClick = { onOpen(item) }, modifier = if (i == 0 && focusFirst != null) Modifier.focusRequester(focusFirst) else Modifier)
            }
        }
    }
}

/** Last 8 searches, newest first. */
object RecentSearches {
    private const val KEY = "recent"
    private fun prefs(c: Context) = c.getSharedPreferences("search", Context.MODE_PRIVATE)
    fun load(c: Context): List<String> = prefs(c).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    fun add(c: Context, q: String): List<String> {
        val next = (listOf(q) + load(c).filterNot { it.equals(q, ignoreCase = true) }).take(8)
        prefs(c).edit { putString(KEY, next.joinToString("\n")) }
        return next
    }
    fun clear(c: Context): List<String> {
        prefs(c).edit { remove(KEY) }
        return emptyList()
    }
}
