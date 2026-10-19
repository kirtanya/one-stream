package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.title.EpisodeCard
import com.kirtanya.reel.ui.title.ProviderChip
import com.kirtanya.reel.ui.title.detailsMeta
import com.kirtanya.reel.ui.title.rememberProviderOpener
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun TvTitle(ref: TitleRef, onPlay: (season: Int?, episode: Int?) -> Unit) {
    val (load, retry) = rememberTitle(ref)
    LoadView(load, retry) { d -> TvTitleBody(d, onPlay) }
}

@Composable
private fun TvTitleBody(d: TitleDetails, onPlay: (Int?, Int?) -> Unit) {
    var season by rememberSaveable { mutableStateOf(d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val openProvider = rememberProviderOpener(d.providers.link)
    val firstAction = remember { FocusRequester() }
    RequestFocusOnce(firstAction)
    val firstSeason = d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number

    Box(Modifier.fillMaxSize()) {
        RemoteImage(TmdbImages.backdrop(d.summary.backdropPath), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tokens.Colors.bg, 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.8f to Tokens.Colors.bg)))

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item {
                Column(Modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    Spacer(Modifier.height(48.dp))
                    Text(d.summary.title, style = Tokens.Type.display, color = Tokens.Colors.text, maxLines = 2)
                    Text(detailsMeta(d), style = Tokens.Type.label, color = Tokens.Colors.textMuted)
                    if (d.genres.isNotEmpty()) Text(d.genres.take(3).joinToString(" · "), style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
                    Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    ReelButton(
                        if (d.seasons.isEmpty()) "Play" else "Play S${firstSeason} E1",
                        { if (d.seasons.isEmpty()) onPlay(null, null) else onPlay(firstSeason, 1) },
                        kind = ButtonKind.Primary,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.focusRequester(firstAction),
                    )
                    val all = d.providers.groups.flatMap { (label, list) -> list.map { label to it } }
                    if (all.isEmpty()) {
                        Text("Not on any streaming service in India right now.", style = Tokens.Type.body, color = Tokens.Colors.textMuted)
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                            contentPadding = PaddingValues(vertical = Tokens.Space.s),
                        ) {
                            itemsIndexed(all) { i, (label, p) ->
                                ProviderChip(p, label = if (label == "Stream") p.name else "$label · ${p.name}", onClick = { openProvider(p) })
                            }
                        }
                        Text("Where to watch in India · data by JustWatch", style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
                    }
                }
            }
            if (d.seasons.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), contentPadding = PaddingValues(vertical = Tokens.Space.s)) {
                        itemsIndexed(d.seasons, key = { _, s -> s.number }) { i, s ->
                            ReelButton(s.name, { season = s.number }, kind = if (s.number == season) ButtonKind.Primary else ButtonKind.Ghost)
                        }
                    }
                }
                item {
                    when (episodes) {
                        Load.Loading -> LoadingBox(Modifier.height(180.dp))
                        is Load.Failed -> ReelButton("Couldn't load episodes. Try again", { retryEpisodes() }, kind = ButtonKind.Ghost)
                        is Load.Ready -> LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                            contentPadding = PaddingValues(vertical = Tokens.Space.s),
                        ) {
                            items(episodes.value, key = { "${it.season}x${it.number}" }) { e ->
                                EpisodeCard(e, width = 240.dp, onClick = { onPlay(e.season, e.number) })
                            }
                        }
                    }
                }
            }
        }
    }
}
