package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.core.browse.TitleFacts
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * TV search: the focused result's quick facts, under the keyboard (where the suggestions are while you type). Only to
 * look at, never focusable: the full name, year, type and length (films) or seasons, episodes and whether more is coming
 * (shows), genres and a few lines of the story. The IMDb rating is on the poster itself. The name and year show at
 * once; the rest arrives once focus has rested on the card for a moment (details are cached), so moving fast fetches
 * nothing.
 */
@Composable
fun TvFocusedDetails(focused: State<TitleSummary?>, modifier: Modifier = Modifier) {
    val t = focused.value ?: return
    val catalog = rememberCatalog()
    var details by remember { mutableStateOf<TitleDetails?>(null) }
    LaunchedEffect(t.ref) {
        details = null
        delay(REST_MS)
        details = runCatching { catalog.details(t.ref) }.getOrNull()
    }
    val d = details?.takeIf { it.summary.ref == t.ref }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Text(t.title, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val parts = d?.let(TitleFacts::basics) ?: listOfNotNull(if (t.ref.type == MediaType.TV) "Series" else "Film", t.year?.toString())
        Text(parts.joinToString("  ·  "), style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        d?.let(TitleFacts::airing)?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        d?.genres?.takeIf { it.isNotEmpty() }?.let {
            Text(it.take(3).joinToString(" · "), style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        t.overview.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How long focus rests on a card before its details are fetched. */
private const val REST_MS = 300L
