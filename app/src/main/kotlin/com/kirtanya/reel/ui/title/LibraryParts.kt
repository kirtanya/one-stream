package com.kirtanya.reel.ui.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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

/** Under the season chips: "3 of 10 watched" and Mark season watched / unwatched. */
@Composable
fun SeasonWatched(d: TitleDetails, season: Int?, sidePadding: Dp) {
    if (season == null) return
    val watched = ReelApp.get(LocalContext.current).watched
    // Recount whenever a tick changes (here, in the player or from the phone).
    val lookup by watched.state.collectAsState(initial = null)
    val count = remember(lookup, d, season) { watched.count(d, season) }
    if (count.total == 0) return
    Row(Modifier.padding(horizontal = sidePadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Text(
            if (count.all) "All ${count.total} watched" else "${count.watched} of ${count.total} watched",
            style = Tokens.Type.label, color = if (count.all) Tokens.Colors.accentBright else Tokens.Colors.textSecondary,
            modifier = Modifier.weight(1f), maxLines = 1,
        )
        ReelButton(
            if (count.all) "Mark unwatched" else "Mark season watched",
            { watched.markSeason(d, season, !count.all) },
            kind = ButtonKind.Ghost, icon = if (count.all) Icons.Rounded.Undo else Icons.Rounded.Check,
        )
    }
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
