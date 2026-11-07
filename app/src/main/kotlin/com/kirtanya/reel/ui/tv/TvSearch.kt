package com.kirtanya.reel.ui.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.browse.KeyPage
import com.kirtanya.reel.core.browse.SearchKey
import com.kirtanya.reel.core.browse.SearchKeys
import com.kirtanya.reel.core.browse.SearchSuggest
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.GenreBrowser
import com.kirtanya.reel.ui.phone.RecentSearches
import com.kirtanya.reel.ui.phone.SearchKind
import com.kirtanya.reel.ui.phone.SearchResults
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Posters across the results (111 dp wide, like the collection grids: big enough to recognise without a name under them). */
private const val COLUMNS = 4

/** Space between the keyboard column and the results. */
private val ColumnGap = Tokens.Space.xl

/** Suggestion rows are always reserved, so a suggestion appearing never moves anything. */
private val SuggestionHeight = 36.dp

/**
 * TV search with its own on-screen keyboard (Netflix / YouTube / Prime Video style), so typing never opens the system
 * keyboard and everything is D-pad. Left column: the query, [TvKeyboard], a few suggestions (what was searched before,
 * then titles the search already found; no request per key). Right column: All · Films · Shows and the poster grid
 * (each poster with its IMDb rating, no text), genres to browse until you type. While a result has focus, its facts
 * replace the suggestions under the keys ([TvFocusedDetails]). Results follow the typing after a short pause. ► off the
 * keyboard's right edge goes to the results, ◄ from their first column comes back; Back from the results goes to the keys, and on the keys it's the app's (twice to exit).
 */
@Composable
fun TvSearch(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit = {}, initialQuery: String = "") {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var debounced by rememberSaveable { mutableStateOf(initialQuery) }
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    var page by rememberSaveable { mutableStateOf(KeyPage.LETTERS) }
    var recents by remember { mutableStateOf(RecentSearches.load(context)) }
    var titles by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(query) {
        // Typing waits for a pause; clearing the field shows what's trending at once.
        if (query.isNotBlank()) delay(500)
        debounced = query.trim()
    }
    val startKey = remember { FocusRequester() }
    val searchKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    RequestFocusOnce(startKey)
    // The focused poster; read only by the info line, so a focus move redraws one line of text.
    val focused = remember { mutableStateOf<TitleSummary?>(null) }
    LaunchedEffect(debounced, kind) { focused.value = null }

    val suggestions = remember(query, recents, titles) { SearchSuggest.rank(query, recents, titles) }
    val hints = remember(query, recents, titles) { SearchSuggest.nextKeys(query, listOf(recents, titles)).toSet() }
    fun saveRecent(q: String) { if (q.isNotBlank()) recents = RecentSearches.add(context, q.trim()) }
    fun toResults() = runCatching { firstResult.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
    // Stable, so typing recomposes the query line and the keys that changed, not a new lambda per key.
    val onKey = remember {
        { k: SearchKey ->
            when (k) {
                SearchKey.Mode -> page = SearchKeys.toggle(page)
                SearchKey.Search -> { saveRecent(query); debounced = query.trim(); toResults(); Unit }
                else -> query = SearchKeys.press(query, k)
            }
        }
    }
    val exitRight = remember { { toResults() } }
    val keyboard = remember { FocusRequester() }
    var keysFocused by remember { mutableStateOf(false) }
    LaunchedEffect(keysFocused) { if (keysFocused) focused.value = null }
    fun toKeyboard() { runCatching { keyboard.requestFocus() } }
    // Back from anywhere but the keys goes to the keys first; on the keys, the app's Back (press twice to exit) runs.
    BackHandler(enabled = !keysFocused) { toKeyboard() }
    val side = Tokens.Space.tvSafeH

    Row(Modifier.fillMaxSize().background(Tokens.Colors.canvas).padding(horizontal = side)) {
        Column(Modifier.width(TvKeyboardWidth).padding(top = Tokens.Space.tvSafeV), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            QueryLine(query, onVoice = { query = it; debounced = it.trim(); saveRecent(it) })
            // One group that remembers its last key: ◄ off the results' first column and Back from the results land there.
            TvKeyboard(
                page, hints, onKey, exitRight, startKey, searchKey,
                Modifier.focusRequester(keyboard).focusRestorer(startKey).focusGroup().onFocusChanged { keysFocused = it.hasFocus },
            )
            // Under the keys: the focused result's facts while you browse the results, the suggestions while you type.
            if (focused.value != null) TvFocusedDetails(focused, Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.xs))
            else Column(Modifier.height(SuggestionHeight * SearchSuggest.MAX + Tokens.Space.xs * (SearchSuggest.MAX - 1)), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                suggestions.forEach { s ->
                    Suggestion(s, fromHistory = s in recents) {
                        query = s; debounced = s.trim(); saveRecent(s)
                        // The word is in; Search is the next thing to press (a suggestion row may vanish once the text changes).
                        runCatching { searchKey.requestFocus(FocusDirection.Enter) }
                    }
                }
            }
        }
        Spacer(Modifier.width(ColumnGap))
        Column(Modifier.weight(1f).padding(top = Tokens.Space.tvSafeV)) {
            // The same text tabs as the rest of the TV.
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                SearchKind.entries.forEach { k -> TvTab(k.label, null, selected = kind == k) { kind = k } }
            }
            SearchResults(
                debounced, kind, GridCells.Fixed(COLUMNS), 0.dp, Tokens.Space.tvSafeV,
                onOpen = { item -> saveRecent(debounced); onOpen(item) },
                focusFirst = firstResult, tvColumns = COLUMNS, onExitLeft = ::toKeyboard,
                showText = false, onFocusItem = { focused.value = it },
                onResults = { list -> titles = list.map { it.title } },
                browse = { TvRowPivot(0.dp) { GenreBrowser(kind, rows = 1, onOpen = { g, type -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = type)) }) } },
            )
        }
    }
}

/**
 * What's typed so far, above the keys: a search icon, the text (its end, when it's long), a still caret. Empty, it asks
 * for a film, show or person and offers the voice search (the first thing heard fills the query).
 */
@Composable
private fun QueryLine(query: String, onVoice: (String) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Row(
        Modifier.fillMaxWidth().height(Tokens.Size.buttonLarge).clip(shape).background(Tokens.Colors.surface2).padding(start = Tokens.Space.l, end = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Tokens.Colors.textSecondary)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // The caret is drawn, not blinked: nothing animates while idle.
            if (query.isEmpty()) {
                Caret()
                Text("Films, shows, people…", style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Tokens.Space.xs))
            } else {
                // The end is what's being typed: when it doesn't fit, show the last characters.
                Text(if (query.length > SHOWN) "…" + query.takeLast(SHOWN - 1) else query, style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false))
                Caret()
            }
        }
        if (query.isEmpty()) Voice(onVoice)
    }
}

/** How many characters of the query fit on the line (the rest is cut from the start). */
private const val SHOWN = 24

@Composable
private fun Caret() {
    Box(Modifier.padding(start = 1.dp).width(2.dp).height(20.dp).background(Tokens.Colors.accentBright))
}

@Composable
private fun Voice(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onResult)
    }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a film, show or person")
    }
    if (!remember { intent.resolveActivity(context.packageManager) != null }) return
    Box(Modifier.size(Tokens.Size.iconButton).focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = { runCatching { listen.launch(intent) } }), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Mic, "Search by voice", tint = Tokens.Colors.textSecondary, modifier = Modifier.size(22.dp))
    }
}

/** One suggestion: a recent search (history icon) or a title the search found; OK puts it in the query. */
@Composable
private fun Suggestion(text: String, fromHistory: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(SuggestionHeight).focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = onPick).padding(horizontal = Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Icon(if (fromHistory) Icons.Rounded.History else Icons.Rounded.Search, null, tint = Tokens.Colors.textSecondary, modifier = Modifier.size(18.dp))
        Text(text, style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
