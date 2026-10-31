package com.kirtanya.reel.ui.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens

/** Whether the whole title (a film, or every aired episode of a show) is watched, and a toggle. Follows marks made anywhere. */
@Composable
fun rememberTitleWatched(d: TitleDetails): Pair<Boolean, () -> Unit> {
    val watched = ReelApp.get(LocalContext.current).watched
    val lookup by watched.state.collectAsState(initial = null)
    val all = remember(lookup, d) { watched.isTitleWatched(d) }
    return all to { watched.markTitle(d, !all) }
}

/** "The Dune Collection": the series' other films, in release order. */
@Composable
fun FranchiseRow(d: TitleDetails, sidePadding: Dp, posterWidth: Dp, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit, showText: Boolean = true) {
    val c = d.collection ?: return
    val catalog = rememberCatalog()
    val (load, _) = rememberLoad(c.id) { catalog.collection(c.id) }
    val parts = (load as? Load.Ready)?.value?.parts.orEmpty()
    if (parts.size < 2) return
    PosterRow(c.name, parts, posterWidth = posterWidth, sidePadding = sidePadding, onOpen = onOpen, showText = showText, onSeeAll = { onOpenDest(Dest.Collection(c.name, items = parts)) })
}
