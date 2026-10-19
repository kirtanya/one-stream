package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.metaLine
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Cinematic home: the focused poster's backdrop fills the screen, its details sit top-left,
 * rows of posters below. Like One Launcher's TV home.
 */
@Composable
fun TvHome(onOpen: (TitleSummary) -> Unit) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(Unit) { tmdb.home() }
    LoadView(load, retry, Modifier.padding(top = TvTopBarHeight)) { rows ->
        var focused by remember { mutableStateOf(rows.first().items.first()) }
        var backdrop by remember { mutableStateOf(focused) }
        // Don't reload the big image for every poster the remote skips over.
        LaunchedEffect(focused) {
            delay(260)
            backdrop = focused
        }
        val firstPoster = remember { FocusRequester() }
        RequestFocusOnce(firstPoster)

        Box(Modifier.fillMaxSize()) {
            RemoteImage(TmdbImages.backdrop(backdrop.backdropPath), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tokens.Colors.bg, 0.65f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Tokens.Colors.bg)))

            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(TvTopBarHeight))
                Column(
                    Modifier.fillMaxWidth(0.5f).height(170.dp).padding(horizontal = Tokens.Space.tvSafeH),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                ) {
                    Text(focused.title, style = Tokens.Type.display, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(metaLine(focused), style = Tokens.Type.label, color = Tokens.Colors.textMuted)
                    Text(focused.overview, style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Tokens.Space.tvSafeV),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.title }) { i, row ->
                        PosterRow(
                            title = row.title,
                            items = row.items,
                            posterWidth = 112.dp,
                            sidePadding = Tokens.Space.tvSafeH,
                            onOpen = onOpen,
                            showText = false,
                            onFocus = { focused = it },
                            firstFocus = if (i == 0) firstPoster else null,
                        )
                    }
                }
            }
        }
    }
}
