package com.kirtanya.reel.ui.phone

import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Mic
import com.kirtanya.reel.ui.common.focusCard
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.ui.common.ErrorBox
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.nav.Dest
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
fun PhoneSearch(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit = {}, initialQuery: String = "") {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var debounced by rememberSaveable { mutableStateOf(initialQuery) }
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    var recents by remember { mutableStateOf(RecentSearches.load(context)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        delay(400)
        debounced = query.trim()
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    val gutter = Tokens.Space.phoneGutter

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.3f)
        Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            Text("Search", style = Tokens.Type.display, color = Tokens.Colors.text, modifier = Modifier.padding(start = gutter, top = Tokens.Space.l))
            SearchField(query, { query = it }, Modifier.padding(horizontal = gutter).focusRequester(focus), onSubmit = {
                if (query.isNotBlank()) recents = RecentSearches.add(context, query.trim())
            })
            KindChips(kind, { kind = it }, Modifier.padding(horizontal = gutter))
            SearchResults(
                debounced, kind, GridCells.Fixed(3), gutter, Tokens.Space.bottomBarClearance + Tokens.Space.xl,
                onOpen = { item ->
                    if (debounced.isNotEmpty()) recents = RecentSearches.add(context, debounced)
                    onOpen(item)
                },
                // Nothing typed yet: recent searches and genres to browse, above what's trending.
                browse = {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
                        if (recents.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                SectionHeader("Recent", action = "Clear", onAction = { recents = RecentSearches.clear(context) })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                    recents.forEach { r -> ReelButton(r, { query = r; debounced = r }, Modifier.widthIn(max = 280.dp), kind = ButtonKind.Ghost, icon = Icons.Rounded.History) }
                                }
                            }
                        }
                        GenreBrowser(kind, rows = 2, onOpen = { g, type -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = type)) })
                    }
                },
            )
        }
    }
}

/** All · Films · Shows. Scrolls sideways rather than squeezing at large font sizes. */
@Composable
fun KindChips(kind: SearchKind, onChange: (SearchKind) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        items(SearchKind.entries) { k -> ReelButton(k.label, { onChange(k) }, kind = if (kind == k) ButtonKind.Primary else ButtonKind.Ghost) }
    }
}

/**
 * "Browse by genre": film genres (or show genres when Shows is picked) as chips that scroll
 * sideways: two rows on the phone (each chip as wide as its name), one focusable row on TV.
 * Each opens the genre's most popular titles.
 */
@Composable
fun GenreBrowser(kind: SearchKind, rows: Int, onOpen: (Genre, MediaType) -> Unit, modifier: Modifier = Modifier, sidePadding: Dp = 0.dp) {
    val catalog = rememberCatalog()
    val type = if (kind == SearchKind.Shows) MediaType.TV else MediaType.MOVIE
    val (genres, _) = rememberLoad(type) { catalog.genres(type) }
    val list = (genres as? Load.Ready)?.value.orEmpty()
    if (list.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader(if (type == MediaType.TV) "Browse shows by genre" else "Browse films by genre", Modifier.padding(horizontal = sidePadding))
        if (rows <= 1) {
            LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                items(list, key = { it.id }) { g -> ReelButton(g.name, { onOpen(g, type) }, kind = ButtonKind.Ghost) }
            }
        } else {
            // Rows scroll together; a lazy grid would size every chip in a column alike.
            val perRow = (list.size + rows - 1) / rows
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = sidePadding), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                list.chunked(perRow).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        line.forEach { g -> ReelButton(g.name, { onOpen(g, type) }, kind = ButtonKind.Ghost) }
                    }
                }
            }
        }
    }
}

/** Pill search field: icon, text, clear button. On TV it gets the focus ring like every other control. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onSubmit: () -> Unit = {}, height: Dp = 48.dp, placeholder: String = "Films, shows, people…", voice: Boolean = true) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.hasFocus }.fillMaxWidth().height(height).clip(shape).background(Tokens.Colors.surface2)
            .border(if (focused) Tokens.Size.focusBorder else Tokens.Size.hairline, if (focused) Tokens.Colors.accentBright else Tokens.Colors.hairlineStrong, shape)
            .padding(horizontal = Tokens.Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Tokens.Colors.textSecondary)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (value.isEmpty() && voice) {
            // Voice search (the TV remote's mic and the phone's): the first thing heard fills the field.
            val context = LocalContext.current
            val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onChange(it); onSubmit() }
            }
            val intent = remember {
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a film, show or person")
            }
            if (remember { intent.resolveActivity(context.packageManager) != null }) {
                Icon(
                    Icons.Rounded.Mic, "Search by voice", tint = Tokens.Colors.textSecondary,
                    modifier = Modifier.minimumInteractiveComponentSize().size(28.dp).focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = {
                        runCatching { listen.launch(intent) }
                    }),
                )
            }
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Clear", tint = Tokens.Colors.textSecondary,
                modifier = Modifier.minimumInteractiveComponentSize().size(24.dp).clip(RoundedCornerShape(Tokens.Radius.pill)).clickable(interactionSource = null, indication = null) { onChange("") },
            )
        }
    }
}

/**
 * Results grid for [query] (filtered by [kind]); what's trending when it's empty, with [browse]
 * (recent searches, genres) above it. Shared by phone and TV.
 */
@Composable
fun SearchResults(
    query: String,
    kind: SearchKind,
    columns: GridCells,
    sidePadding: Dp,
    bottomPadding: Dp,
    onOpen: (TitleSummary) -> Unit,
    focusFirst: FocusRequester? = null,
    browse: (@Composable () -> Unit)? = null,
    /** TV: cards per row. Down from any card lands on the first card of the next row (not the one below). */
    rowStartOnDown: Int? = null,
    /** TV: posters without the name and meta line under them (the focused one is named elsewhere, see [onFocusItem]). */
    showText: Boolean = true,
    /** TV: the poster that just took focus. */
    onFocusItem: ((TitleSummary) -> Unit)? = null,
) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(query) {
        if (query.isEmpty()) catalog.home().firstOrNull()?.items.orEmpty() else catalog.search(query)
    }
    val hidden = com.kirtanya.reel.ui.home.rememberHiddenKeys()
    val results = (load as? Load.Ready)?.value.orEmpty().filter { it.ref.toString() !in hidden }.filter {
        when (kind) {
            SearchKind.All -> true
            SearchKind.Films -> it.ref.type == MediaType.MOVIE
            SearchKind.Shows -> it.ref.type == MediaType.TV
        }
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cardFocus = remember(results) { results.map { FocusRequester() } }
    // Grid items before the first card: the genre browser (no query) and the heading.
    val lead = (if (query.isEmpty() && browse != null) 1 else 0) + 1
    // TV (rowStartOnDown is TV's): the grid glides to the focused row over the page glide time, not Compose's spring.
    val tv = rowStartOnDown != null
    val grid: @Composable () -> Unit = {
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = Tokens.Space.s, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
    ) {
        if (query.isEmpty() && browse != null) item(span = { GridItemSpan(maxLineSpan) }, key = "browse", contentType = "browse") { browse() }
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading", contentType = "heading") {
            when (load) {
                Load.Loading -> LoadingBox(Modifier.height(160.dp))
                is Load.Failed -> ErrorBox(load.message, { retry() }, Modifier.height(200.dp))
                is Load.Ready -> when {
                    query.isNotEmpty() && results.isEmpty() ->
                        EmptyState(Icons.Rounded.Search, "Nothing found", "No ${if (kind == SearchKind.All) "titles" else kind.label.lowercase()} match “$query”. Try another spelling.")
                    query.isEmpty() -> SectionHeader("Trending this week")
                    else -> SectionHeader(com.kirtanya.reel.ui.plural(results.size, "result"))
                }
            }
        }
        itemsIndexed(results, key = { _, it -> it.ref.toString() }, contentType = { _, _ -> "poster" }) { i, item ->
            val first = if (i == 0 && focusFirst != null) Modifier.focusRequester(focusFirst) else Modifier
            val rowJump = rowStartOnDown?.let { cols ->
                Modifier.focusRequester(cardFocus[i]).onPreviewKeyEvent { e ->
                    val next = (i / cols + 1) * cols
                    if (e.key != Key.DirectionDown || next >= results.size) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) scope.launch {
                        // The row may not be composed yet: bring it in, then focus its first card.
                        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == lead + next }) gridState.scrollToItem(lead + next)
                        repeat(5) {
                            androidx.compose.runtime.withFrameNanos { }
                            if (runCatching { cardFocus[next].requestFocus(androidx.compose.ui.focus.FocusDirection.Down) }.getOrDefault(false)) return@launch
                        }
                    }
                    true
                }
            } ?: Modifier
            PosterCard(item, width = null, onClick = { onOpen(item) }, modifier = first.then(rowJump), showText = showText, onFocus = onFocusItem?.let { f -> { f(item) } })
        }
    }
    }
    if (tv) com.kirtanya.reel.ui.tv.TvColumnGlide(reveal = Tokens.Space.s, content = grid) else grid()
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
