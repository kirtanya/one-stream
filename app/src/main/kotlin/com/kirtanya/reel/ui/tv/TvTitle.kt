package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GenreChips
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.MetaLine
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.RingAvatar
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.VideoRow
import com.kirtanya.reel.ui.common.detailsParts
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.openVideo
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.common.rememberWatchlistToggle
import com.kirtanya.reel.ui.common.watchlistIcon
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.PlayAction
import com.kirtanya.reel.ui.title.heroBackdrops
import com.kirtanya.reel.ui.title.EpisodeOptions
import com.kirtanya.reel.ui.title.rememberEpisodeProgress
import com.kirtanya.reel.ui.title.rememberPlayAction
import com.kirtanya.reel.ui.title.rememberProviderOpener
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import com.kirtanya.reel.ui.title.runPlayAction

@Composable
fun TvTitle(ref: TitleRef, onPlay: (season: Int?, episode: Int?) -> Unit, onOpen: (TitleSummary) -> Unit = {}, onOpenDest: (Dest) -> Unit = {}) {
    val (load, retry) = rememberTitle(ref)
    when (load) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> {
            val focus = remember { FocusRequester() }
            RequestFocusOnce(focus)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Refresh, "Couldn't load this title", load.message, Modifier.fillMaxWidth(0.6f)) {
                    ReelButton("Try again", { retry() }, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                }
            }
        }
        is Load.Ready -> TvTitleBody(load.value, onPlay, onOpen, onOpenDest)
    }
}

@Composable
private fun TvTitleBody(d: TitleDetails, onPlay: (Int?, Int?) -> Unit, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit) {
    val context = LocalContext.current
    val glow by rememberAmbientColor(TmdbImages.poster(d.summary.posterPath, "w185"))
    val action = rememberPlayAction(d)
    var season by rememberSaveable { mutableStateOf(d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val progress = if (d.summary.ref.type == MediaType.TV) rememberEpisodeProgress(d.summary.ref.id) else emptyMap()
    val openProvider = rememberProviderOpener(d.providers.link)
    val (saved, toggleSaved) = rememberWatchlistToggle(d.summary)
    val playFocus = remember { FocusRequester() }
    RequestFocusOnce(playFocus)
    val openSources: (EpisodeNumber?) -> Unit = { ep -> onPlay(ep?.season, ep?.episode) }
    var episodeOptions by remember { mutableStateOf<com.kirtanya.reel.core.tmdb.Episode?>(null) }
    episodeOptions?.let { e ->
        EpisodeOptions(d, e, progress[EpisodeNumber(e.season, e.number)], onPlay = { s, n -> onPlay(s, n) }, onOpenDest = onOpenDest, onDismiss = { episodeOptions = null })
    }
    val side = Tokens.Space.tvSafeH

    Box(Modifier.fillMaxSize()) {
        // The backdrops fill the screen and change slowly (no zoom: the TV is a small 32-bit box).
        HeroImage(heroBackdrops(d), Modifier.fillMaxSize(), kenBurns = false)
        HeroScrims(top = false, start = true)
        AmbientGlow(glow, height = 700.dp, strength = 0.3f)

        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.kirtanya.reel.ui.common.MinimalBringIntoView) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Tokens.Space.tvSafeV),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item(key = "hero") {
                Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                    Spacer(Modifier.height(Tokens.Space.xxxl))
                    Column(Modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        TitleLogo(d.logoPath, d.summary.title, Tokens.Type.hero, Tokens.Cinema.logoMaxWidthTv, Tokens.Cinema.logoMaxHeightTv, textMaxLines = 2)
                        MetaLine(d.summary.rating, detailsParts(d) + listOfNotNull(d.genres.take(2).joinToString(", ").ifEmpty { null }), maxLines = 1)
                        if (d.summary.overview.isNotBlank()) {
                            Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Buttons get the full width: Resume labels and four actions don't fit in the text column.
                    Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
                        ReelButton(action.label, { runPlayAction(context, action, openSources) }, Modifier.focusRequester(playFocus), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
                        d.trailer?.let { t -> ReelButton("Trailer", { openVideo(context, t) }, kind = ButtonKind.Glass, icon = Icons.Rounded.Movie, large = true) }
                        ReelButton(if (saved) "In My list" else "My list", toggleSaved, kind = ButtonKind.Glass, icon = watchlistIcon(saved), large = true)
                        val ep = (action as? PlayAction.Resume)?.entry?.playback?.episode ?: (action as? PlayAction.Choose)?.episode
                        ReelButton("All sources", { openSources(ep) }, kind = ButtonKind.Glass, icon = Icons.Rounded.Hub, large = true)
                    }
                    if (action is PlayAction.Resume) GradientProgress(action.entry.progress, Modifier.width(320.dp), height = 3.dp)
                }
            }
            if (d.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SectionHeader("Episodes", Modifier.padding(horizontal = side))
                        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            items(d.seasons, key = { it.number }) { s ->
                                ReelButton(s.name, { season = s.number }, kind = if (s.number == season) ButtonKind.Primary else ButtonKind.Ghost)
                            }
                        }
                        com.kirtanya.reel.ui.title.SeasonWatched(d, season, side)
                    }
                }
                item(key = "episodes") {
                    when (episodes) {
                        Load.Loading -> LoadingBox(Modifier.height(160.dp))
                        is Load.Failed -> ReelButton("Couldn't load episodes · Try again", { retryEpisodes() }, Modifier.padding(horizontal = side), kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh)
                        is Load.Ready -> LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.m), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
                            itemsIndexed(episodes.value, key = { _, e -> "${e.season}x${e.number}" }) { _, e ->
                                val watched = progress[EpisodeNumber(e.season, e.number)]
                                val shape = RoundedCornerShape(Tokens.Radius.media)
                                Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusCard(shape = shape, scale = Tokens.Motion.posterFocusScale, onLongClick = { episodeOptions = e }, onClick = { onPlay(e.season, e.number) }).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)) {
                                        RemoteImage(TmdbImages.still(e.stillPath, "w780"), Modifier.fillMaxSize())
                                        when {
                                            watched?.finished == true -> Pill("Watched", Modifier.align(Alignment.TopStart).padding(Tokens.Space.s), icon = Icons.Rounded.Check, accent = true)
                                            watched != null -> GradientProgress(watched.progress, Modifier.align(Alignment.BottomCenter).padding(Tokens.Space.s), height = 3.dp, track = Color.White.copy(alpha = 0.2f))
                                        }
                                    }
                                    Text("${e.number}. ${e.name}", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val facts = listOfNotNull(e.runtimeMin?.let { "${it}m" }, e.airDate?.take(4)).joinToString(" · ")
                                    if (facts.isNotEmpty()) Text(facts, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            val providers = d.providers.groups.flatMap { (label, list) -> list.map { label to it } }.distinctBy { it.second.id }
            if (providers.isNotEmpty()) {
                item(key = "where") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SectionHeader("Where to watch in India", Modifier.padding(horizontal = side))
                        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            items(providers, key = { it.second.id }) { (label, p) ->
                                RingAvatar(TmdbImages.logo(p.logoPath, "w154"), p.name, 60.dp, onClick = { openProvider(p) }, sublabel = label)
                            }
                        }
                    }
                }
            }
            val extras = d.videos.filter { it != d.trailer }
            if (extras.isNotEmpty()) item(key = "extras") { VideoRow(extras, side, Tokens.Cinema.thumbWidthTv) }
            if (d.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SectionHeader("Cast", Modifier.padding(horizontal = side))
                        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                            items(d.cast) { c ->
                                RingAvatar(
                                    TmdbImages.profile(c.profilePath), c.name, 60.dp, sublabel = c.character, placeholder = c.name.take(1),
                                    onClick = { c.id?.let { onOpenDest(Dest.Person(it, c.name)) } },
                                )
                            }
                        }
                    }
                }
            }
            if (d.collection != null) {
                item(key = "franchise") { com.kirtanya.reel.ui.title.FranchiseRow(d, side, Tokens.Cinema.posterTv, onOpen, onOpenDest, showText = false) }
            }
            if (d.recommendations.isNotEmpty()) {
                item(key = "more") { PosterRow("More like this", d.recommendations, posterWidth = 112.dp, sidePadding = side, onOpen = onOpen) }
            }
            if (d.genreList.isNotEmpty()) {
                item(key = "genres") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SectionHeader("Browse by genre", Modifier.padding(horizontal = side))
                        GenreChips(d.genreList, side, onOpen = { g -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = d.summary.ref.type)) })
                    }
                }
            }
        }
        }
    }
}
