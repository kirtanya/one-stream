package com.kirtanya.reel.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.metaLine
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun PhoneHome(onOpen: (TitleSummary) -> Unit) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(Unit) { tmdb.home() }
    LoadView(load, retry) { rows ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.xl),
        ) {
            item {
                Text("Reel", style = Tokens.Type.display, color = Tokens.Colors.text, modifier = Modifier.padding(horizontal = Tokens.Space.phoneGutter))
            }
            rows.firstOrNull()?.items?.firstOrNull()?.let { hero -> item { Hero(hero, onOpen) } }
            items(rows, key = { it.title }) { row ->
                PosterRow(row.title, row.items, posterWidth = 112.dp, sidePadding = Tokens.Space.phoneGutter, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun Hero(item: TitleSummary, onOpen: (TitleSummary) -> Unit) {
    Box(
        Modifier
            .padding(horizontal = Tokens.Space.phoneGutter)
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .focusCard(onClick = { onOpen(item) }),
    ) {
        RemoteImage(TmdbImages.backdrop(item.backdropPath, "w780"), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Tokens.Colors.scrim)))
        Column(Modifier.align(Alignment.BottomStart).padding(Tokens.Space.l)) {
            Text("Trending now", style = Tokens.Type.caption, color = Tokens.Colors.accent)
            Text(item.title, style = Tokens.Type.title, color = Tokens.Colors.text, maxLines = 2)
            Text(metaLine(item), style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
        }
    }
}
