package com.kirtanya.reel.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.data.TmdbException
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.TitleSkeleton
import com.kirtanya.reel.ui.theme.Tokens

/**
 * A One Launcher link that only knows the IMDb id: finds the title on TMDB, then [onResolved]
 * swaps this for the normal title page. Loading and failure look exactly like the title page's own.
 */
@Composable
fun ImdbTitleLookup(dest: Dest.ImdbTitle, isTv: Boolean, onBack: () -> Unit, onResolved: (TitleRef) -> Unit) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(dest) {
        catalog.findByImdb(dest.imdbId, dest.type)?.ref ?: throw TmdbException("TMDB doesn't know this title (${dest.imdbId}).")
    }
    if (load is Load.Ready) LaunchedEffect(load.value) { onResolved(load.value) }
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (load) {
            is Load.Failed -> {
                val focus = remember { FocusRequester() }
                if (isTv) RequestFocusOnce(focus)
                EmptyState(
                    Icons.Rounded.Refresh, "Couldn't load this title", load.message,
                    Modifier.align(Alignment.Center).then(if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier),
                ) { ReelButton("Try again", { retry() }, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) }
            }
            else -> if (isTv) LoadingBox() else TitleSkeleton()
        }
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}
