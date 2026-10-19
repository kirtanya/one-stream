package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens

/** "See all" for a Home row, or everything streaming on one service. */
@Composable
fun CollectionScreen(dest: Dest.Collection, isTv: Boolean, onBack: () -> Unit, onOpen: (TitleSummary) -> Unit) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(dest) { dest.items ?: dest.provider?.let { tmdb.onService(it.id) }.orEmpty() }
    val first = (load as? Load.Ready)?.value?.firstOrNull()
    val glow by rememberAmbientColor(TmdbImages.poster(first?.posterPath, "w185"))
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val firstFocus = remember { FocusRequester() }
    if (isTv && first != null) RequestFocusOnce(firstFocus, key = first.ref)

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(glow)
        LoadView(load, retry) { items ->
            LazyVerticalGrid(
                columns = if (isTv) GridCells.Fixed(7) else GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.tvSafeV else 0.dp, bottom = Tokens.Space.xxxl),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                    ) {
                        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                            dest.provider?.let { RingAvatar(TmdbImages.logo(it.logoPath, "w154"), null, if (isTv) 72.dp else 56.dp) }
                            Column {
                                Text(dest.title, style = if (isTv) Tokens.Type.hero else Tokens.Type.display, color = Tokens.Colors.text)
                                Text(
                                    if (dest.provider != null) "Streaming in India · ${items.size} titles" else "${items.size} titles",
                                    style = Tokens.Type.body,
                                    color = Tokens.Colors.textSecondary,
                                )
                            }
                        }
                    }
                }
                itemsIndexed(items, key = { _, it -> it.ref.toString() }) { i, item ->
                    PosterCard(item, width = null, onClick = { onOpen(item) }, modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                }
            }
        }
    }
}
