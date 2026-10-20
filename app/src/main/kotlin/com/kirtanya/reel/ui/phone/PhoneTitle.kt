package com.kirtanya.reel.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.Backdrop
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RingAvatar
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.PlayAction
import com.kirtanya.reel.ui.title.rememberEpisodeProgress
import com.kirtanya.reel.ui.title.rememberPlayAction
import com.kirtanya.reel.ui.title.rememberProviderOpener
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import com.kirtanya.reel.ui.title.runPlayAction
import com.kirtanya.reel.ui.title.shareTitle

@Composable
fun PhoneTitle(ref: TitleRef, onBack: () -> Unit, onPlay: (season: Int?, episode: Int?) -> Unit) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        LoadView(load, retry) { d -> TitleBody(d, onPlay) }
        com.kirtanya.reel.ui.common.TopFade()
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun TitleBody(d: TitleDetails, onPlay: (Int?, Int?) -> Unit) {
    val context = LocalContext.current
    val gutter = Tokens.Space.phoneGutter
    val glow by rememberAmbientColor(TmdbImages.poster(d.summary.posterPath, "w185"))
    val action = rememberPlayAction(d)
    var season by rememberSaveable { mutableStateOf(d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val progress = if (d.summary.ref.type == MediaType.TV) rememberEpisodeProgress(d.summary.ref.id) else emptyMap()
    val openProvider = rememberProviderOpener(d.providers.link)
    var overviewOpen by rememberSaveable { mutableStateOf(false) }
    val openSources: (EpisodeNumber?) -> Unit = { ep -> onPlay(ep?.season, ep?.episode) }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Material top app bar: appears (solid, with the title) once the header has scrolled away.
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = Tokens.Space.xxxl), verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
        // Cinematic header: backdrop under the status bar, the poster's light spilling into the page.
        item(key = "header") {
            Backdrop(
                TmdbImages.backdrop(d.summary.backdropPath),
                Modifier.fillMaxWidth().height(340.dp).drawBehind {
                    drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 0.15f, size.height), radius = size.width))
                },
            ) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                        Pill(if (d.summary.ref.type == MediaType.TV) "Series" else "Film", onGlass = true)
                        d.summary.year?.let { Pill(it.toString(), onGlass = true) }
                        d.runtimeMin?.let { Pill(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m", onGlass = true) }
                        d.seasons.count { it.number > 0 }.takeIf { it > 0 }?.let { Pill(if (it == 1) "1 season" else "$it seasons", onGlass = true) }
                        d.certification?.let { Pill(it, onGlass = true) }
                        d.summary.rating?.let { Pill("★ %.1f".format(it), accent = true) }
                    }
                    Text(d.summary.title, style = Tokens.Type.display, color = Tokens.Colors.text)
                    if (d.genres.isNotEmpty()) Text(d.genres.take(3).joinToString(" · "), style = Tokens.Type.label, color = Tokens.Colors.textSecondary)
                }
            }
        }
        item(key = "actions") {
            Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                ReelButton(action.label, { runPlayAction(context, action, openSources) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
                if (action is PlayAction.Resume) GradientProgress(action.entry.progress, height = 3.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                    val ep = (action as? PlayAction.Resume)?.entry?.playback?.episode ?: (action as? PlayAction.Choose)?.episode
                    ReelButton("All sources", { openSources(ep) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Rounded.Hub)
                    GlassIconButton(Icons.Rounded.Download, "Download", { openSources(ep) })
                    GlassIconButton(Icons.Rounded.Share, "Share", { shareTitle(context, d) })
                }
                d.tagline?.let { Text("“$it”", style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
                if (d.summary.overview.isNotBlank()) {
                    Text(
                        d.summary.overview,
                        style = Tokens.Type.body,
                        color = Tokens.Colors.textSecondary,
                        maxLines = if (overviewOpen) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.s), scale = 1f, onClick = { overviewOpen = !overviewOpen }),
                    )
                }
            }
        }
        item(key = "where") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                SectionHeader("Where to watch", Modifier.padding(horizontal = gutter))
                val all = d.providers.groups.flatMap { (label, list) -> list.map { label to it } }.distinctBy { it.second.id }
                if (all.isEmpty()) {
                    Text("Not on any streaming service in India right now.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary, modifier = Modifier.padding(horizontal = gutter))
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        items(all, key = { it.second.id }) { (label, p) ->
                            RingAvatar(TmdbImages.logo(p.logoPath, "w154"), p.name, 52.dp, onClick = { openProvider(p) }, sublabel = label)
                        }
                    }
                    Text("Availability in India · data by JustWatch", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, modifier = Modifier.padding(horizontal = gutter))
                }
            }
        }
        if (d.seasons.isNotEmpty()) {
            item(key = "seasons") {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SectionHeader("Episodes", Modifier.padding(horizontal = gutter))
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        items(d.seasons, key = { it.number }) { s ->
                            ReelButton(s.name, { season = s.number }, kind = if (s.number == season) ButtonKind.Primary else ButtonKind.Ghost)
                        }
                    }
                }
            }
            when (episodes) {
                Load.Loading -> item { LoadingBox(Modifier.height(120.dp)) }
                is Load.Failed -> item { ReelButton("Couldn't load episodes · Try again", { retryEpisodes() }, Modifier.padding(horizontal = gutter), kind = ButtonKind.Ghost) }
                is Load.Ready -> items(episodes.value, key = { "${it.season}x${it.number}" }) { e ->
                    EpisodeRow(e, progress[EpisodeNumber(e.season, e.number)], Modifier.padding(horizontal = gutter)) { onPlay(e.season, e.number) }
                }
            }
        }
        if (d.cast.isNotEmpty()) {
            item(key = "cast") {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SectionHeader("Cast", Modifier.padding(horizontal = gutter))
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                        items(d.cast) { c ->
                            RingAvatar(TmdbImages.profile(c.profilePath), c.name, 56.dp, sublabel = c.character, placeholder = c.name.take(1))
                        }
                    }
                }
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(scrolled, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Box(Modifier.fillMaxWidth().background(Tokens.Colors.surface1).statusBarsPadding().height(56.dp).padding(start = 64.dp, end = Tokens.Space.l), contentAlignment = Alignment.CenterStart) {
            Text(d.summary.title, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}

/** Episode card: still (with progress or a watched tick), number + name, runtime, two lines of story. */
@Composable
private fun EpisodeRow(e: Episode, watched: WatchEntry?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    val stillShape = RoundedCornerShape(Tokens.Radius.m)
    Row(
        modifier.fillMaxWidth().focusCard(shape = shape, scale = 1f, onClick = onClick).padding(Tokens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(stillShape).border(Tokens.Size.hairline, Tokens.Colors.hairline, stillShape)) {
            RemoteImage(TmdbImages.still(e.stillPath), Modifier.fillMaxSize())
            when {
                watched?.finished == true -> Pill("Watched", Modifier.align(Alignment.TopStart).padding(Tokens.Space.xs), icon = Icons.Rounded.Check, accent = true)
                watched != null -> GradientProgress(watched.progress, Modifier.align(Alignment.BottomCenter).padding(Tokens.Space.s), height = 3.dp, track = Color.White.copy(alpha = 0.2f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text("${e.number}. ${e.name}", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(e.runtimeMin?.let { "${it}m" }, e.airDate?.take(4)).joinToString(" · "), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
            if (e.overview.isNotBlank()) Text(e.overview, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

