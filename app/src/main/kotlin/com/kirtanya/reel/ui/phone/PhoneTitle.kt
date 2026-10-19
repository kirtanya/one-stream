package com.kirtanya.reel.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.title.ProviderChip
import com.kirtanya.reel.ui.title.detailsMeta
import com.kirtanya.reel.ui.title.rememberProviderOpener
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun PhoneTitle(ref: TitleRef, onBack: () -> Unit, onPlay: (season: Int?, episode: Int?) -> Unit) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.bg)) {
        LoadView(load, retry) { d -> TitleBody(d, onPlay) }
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(Tokens.Space.s),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Tokens.Colors.scrim, contentColor = Tokens.Colors.text),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
    }
}

@Composable
private fun TitleBody(d: TitleDetails, onPlay: (Int?, Int?) -> Unit) {
    val gutter = Tokens.Space.phoneGutter
    var season by rememberSaveable { mutableStateOf(d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val openProvider = rememberProviderOpener(d.providers.link)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Tokens.Space.xxl)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                RemoteImage(TmdbImages.backdrop(d.summary.backdropPath, "w780"), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tokens.Colors.bg)))
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = gutter).offset(y = (-40).dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                verticalAlignment = Alignment.Bottom,
            ) {
                RemoteImage(TmdbImages.poster(d.summary.posterPath, "w185"), Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(Tokens.Radius.m)))
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Text(d.summary.title, style = Tokens.Type.title, color = Tokens.Colors.text)
                    Text(detailsMeta(d), style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
                    if (d.genres.isNotEmpty()) Text(d.genres.take(3).joinToString(" · "), style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = gutter).offset(y = (-24).dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                d.tagline?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accent) }
                if (d.summary.overview.isNotBlank()) Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.text)
                val firstSeason = d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number
                ReelButton(
                    if (d.seasons.isEmpty()) "Play" else "Play S${firstSeason} E1",
                    { if (d.seasons.isEmpty()) onPlay(null, null) else onPlay(firstSeason, 1) },
                    kind = ButtonKind.Primary,
                    icon = Icons.Rounded.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { WhereToWatch(d, openProvider) }
        if (d.seasons.isNotEmpty()) {
            item {
                SectionTitle("Episodes")
                LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    items(d.seasons, key = { it.number }) { s ->
                        ReelButton(s.name, { season = s.number }, kind = if (s.number == season) ButtonKind.Primary else ButtonKind.Ghost)
                    }
                }
                Spacer(Modifier.height(Tokens.Space.m))
            }
            when (episodes) {
                Load.Loading -> item { LoadingBox(Modifier.height(160.dp)) }
                is Load.Failed -> item { ReelButton("Couldn't load episodes. Try again", { retryEpisodes() }, kind = ButtonKind.Ghost, modifier = Modifier.padding(horizontal = gutter)) }
                is Load.Ready -> items(episodes.value, key = { "${it.season}x${it.number}" }) { e -> EpisodeRow(e) { onPlay(e.season, e.number) } }
            }
        }
        if (d.cast.isNotEmpty()) {
            item {
                SectionTitle("Cast")
                LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                    items(d.cast) { c ->
                        Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RemoteImage(TmdbImages.profile(c.profilePath), Modifier.size(64.dp).clip(CircleShape))
                            Text(c.name, style = Tokens.Type.caption, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhereToWatch(d: TitleDetails, openProvider: (com.kirtanya.reel.core.tmdb.Provider) -> Unit) {
    SectionTitle("Where to watch")
    Column(Modifier.padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        if (d.providers.isEmpty) {
            Text("Not on any streaming service in India right now.", style = Tokens.Type.body, color = Tokens.Colors.textMuted)
        }
        d.providers.groups.forEach { (label, providers) ->
            Text(label, style = Tokens.Type.label, color = Tokens.Colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                providers.forEach { p -> ProviderChip(p, onClick = { openProvider(p) }) }
            }
        }
        if (!d.providers.isEmpty) Text("Availability in India · data by JustWatch", style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
    }
}

@Composable
private fun EpisodeRow(e: Episode, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.xs)
            .focusCard(onClick = onClick)
            .padding(vertical = Tokens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        RemoteImage(TmdbImages.still(e.stillPath), Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.Radius.s)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text("${e.number}. ${e.name}", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.runtimeMin?.let { Text("${it}m", style = Tokens.Type.caption, color = Tokens.Colors.textMuted) }
            if (e.overview.isNotBlank()) Text(e.overview, style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = Tokens.Type.heading,
        color = Tokens.Colors.text,
        modifier = Modifier.padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.m),
    )
}
