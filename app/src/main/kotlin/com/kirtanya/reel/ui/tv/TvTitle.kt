package com.kirtanya.reel.ui.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.EpisodeFacts
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.EnterRowAtStart
import com.kirtanya.reel.ui.common.GenreChips
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.RingAvatar
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.VideoRow
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.openVideo
import com.kirtanya.reel.ui.common.rememberWatchlistToggle
import com.kirtanya.reel.ui.common.rowStart
import com.kirtanya.reel.ui.common.watchlistIcon
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.EpisodeOptions
import com.kirtanya.reel.ui.title.PlayAction
import com.kirtanya.reel.ui.title.heroBackdrops
import com.kirtanya.reel.ui.title.rememberEpisodeProgress
import com.kirtanya.reel.ui.title.rememberPlayAction
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import com.kirtanya.reel.ui.title.runPlayAction
import kotlinx.coroutines.launch

/**
 * TV title page, Netflix / Hotstar style: the backdrop on the right melting into black on the left, the logo, facts, scores,
 * a three-line story and the actions on top; below, rows (episodes, reviews, cast, more like this) that the page glides
 * to as ↓ moves ([TvColumnGlide]) and that slide under a fixed start line ([TvRowPivot]).
 */
@Composable
fun TvTitle(ref: TitleRef, initialSeason: Int? = null, onPlay: (season: Int?, episode: Int?) -> Unit, onOpen: (TitleSummary) -> Unit = {}, onOpenDest: (Dest) -> Unit = {}) {
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
        is Load.Ready -> TvTitleBody(load.value, initialSeason, onPlay, onOpen, onOpenDest)
    }
}

/** Where focus is, for the page's two jobs (dimming the backdrop, scrolling back to the top). Read only while drawing. */
private class HeroFocus {
    /** True while focus is in the header (Play and the buttons). */
    var focused by mutableStateOf(true)

    /** Not dimmed before Play first takes focus (the page opening isn't "focus left the hero"). */
    var hadFocus = false
}

@Composable
private fun TvTitleBody(d: TitleDetails, initialSeason: Int?, onPlay: (Int?, Int?) -> Unit, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit) {
    val context = LocalContext.current
    val action = rememberPlayAction(d)
    val ratings = com.kirtanya.reel.ui.common.rememberTitleRatings(d)
    val reviews = com.kirtanya.reel.ui.title.rememberReviews(d.summary.ref)
    var season by rememberSaveable { mutableStateOf(initialSeason?.takeIf { s -> d.seasons.any { it.number == s } } ?: d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val episodeImdb = if (d.summary.ref.type == MediaType.TV) com.kirtanya.reel.ui.title.rememberEpisodeImdb(d, season) else emptyMap()
    val progress = if (d.summary.ref.type == MediaType.TV) rememberEpisodeProgress(d.summary.ref.id) else emptyMap()
    val (saved, toggleSaved) = rememberWatchlistToggle(d.summary)
    val (watchedAll, toggleWatched) = com.kirtanya.reel.ui.title.rememberTitleWatched(d)
    val playFocus = remember { FocusRequester() }
    RequestFocusOnce(playFocus)
    val openSources: (EpisodeNumber?) -> Unit = { ep -> onPlay(ep?.season, ep?.episode) }
    var episodeOptions by remember { mutableStateOf<Episode?>(null) }
    episodeOptions?.let { e ->
        EpisodeOptions(d, e, progress[EpisodeNumber(e.season, e.number)], onPlay = { s, n -> onPlay(s, n) }, onOpenDest = onOpenDest, onDismiss = { episodeOptions = null })
    }
    val side = Tokens.Space.tvSafeH
    val hero = remember { HeroFocus() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The episode in focus, whose story shows under the row (the cards carry only a still and a name).
    val focusedEpisode = remember { mutableStateOf<Episode?>(null) }
    val canvas = Tokens.Colors.canvas

    // Once focus leaves the header (reviews, episodes, cast…) the backdrop dims to almost black so rows read on any picture.
    // One draw op over the picture already drawn, its alpha animated in the draw phase only: no layer, no recomposition.
    val dim = remember { Animatable(0f) }
    LaunchedEffect(hero) {
        snapshotFlow { hero.focused }.collect { inHero -> dim.animateTo(if (inHero) 0f else DIM, tween(TvMotion.heroFadeMs, easing = TvMotion.easing)) }
    }

    Box(Modifier.fillMaxSize()) {
        // The backdrop fills the screen as one still (an 8 s slideshow is an animation while idle, which the TV chip pays for).
        // Its left side melts into the canvas (baked scrim), so the text sits on black and the picture lives on the right.
        HeroImage(heroBackdrops(d).take(1), Modifier.fillMaxSize())
        HeroScrims(top = false, start = true)
        Box(Modifier.fillMaxSize().drawBehind { if (dim.value > 0f) drawRect(canvas, alpha = dim.value) })

        TvColumnGlide(reveal = RowReveal) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(vertical = Tokens.Space.tvSafeV),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                item(key = "hero", contentType = "hero") {
                    Column(
                        Modifier.onFocusChanged { f ->
                            if (f.hasFocus || hero.hadFocus) hero.focused = f.hasFocus
                            // Back up from the rows: glide to the very top so the whole header shows, not just the buttons.
                            if (f.hasFocus && hero.hadFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) scope.launch { listState.glideToTop() }
                            if (f.hasFocus) hero.hadFocus = true
                        }.padding(horizontal = side),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                    ) {
                        // The header sits low, like a cinema poster: the picture shows above it and the next row peeks below.
                        Spacer(Modifier.height(Tokens.Cinema.tvTitleTop))
                        Column(Modifier.fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            TitleLogo(d.logoPath, d.summary.title, Tokens.Type.hero, Tokens.Cinema.logoMaxWidthTv, Tokens.Cinema.logoMaxHeightTv, textMaxLines = 2)
                            // Under the name, as on Netflix: year · runtime or seasons · certificate · genres, then the scores.
                            com.kirtanya.reel.ui.common.Eyebrow(d, maxLines = 1)
                            com.kirtanya.reel.ui.common.RatingsRow(ratings, d.summary.rating)
                            if (d.summary.overview.isNotBlank()) {
                                Text(d.summary.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // Buttons get the full width: Resume labels and five actions don't fit in the text column.
                        Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
                            ReelButton(action.label, { runPlayAction(context, action, openSources) }, Modifier.focusRequester(playFocus), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
                            d.trailer?.let { t -> ReelButton("Trailer", { openVideo(context, t) }, kind = ButtonKind.Glass, icon = Icons.Rounded.Movie, large = true) }
                            ReelButton(if (saved) "In My list" else "My list", toggleSaved, kind = ButtonKind.Glass, icon = watchlistIcon(saved), large = true)
                            val ep = (action as? PlayAction.Resume)?.entry?.playback?.episode ?: (action as? PlayAction.Choose)?.episode
                            ReelButton("All sources", { openSources(ep) }, kind = ButtonKind.Glass, icon = Icons.Rounded.Hub, large = true)
                            // The whole film or show (every aired episode); the seasons have no watched line of their own on TV.
                            ReelButton(if (watchedAll) "Watched" else "Mark watched", toggleWatched, kind = ButtonKind.Glass, icon = if (watchedAll) Icons.Rounded.Check else Icons.Rounded.Visibility, large = true)
                        }
                        if (action is PlayAction.Resume) GradientProgress(action.entry.progress, Modifier.width(320.dp), height = 3.dp)
                    }
                }
                // Films: reviews straight under the buttons (the approved TV layout); ↓ from the buttons lands here.
                // Series: seasons and episodes come first, reviews after them.
                if (d.seasons.isEmpty() && reviews.items.isNotEmpty()) {
                    item(key = "reviews", contentType = "reviews") { EnterRowAtStart { TvRowPivot(side) { com.kirtanya.reel.ui.title.ReviewsRow(reviews, side, 260.dp) } } }
                }
                if (d.seasons.isNotEmpty()) {
                    item(key = "seasons", contentType = "seasons") {
                        EnterRowAtStart {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                SectionHeader("Episodes", Modifier.padding(horizontal = side))
                                // Seasons as text tabs (like the top bar), not a row of grey chips; the tab inset lines the text up with the header.
                                LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side - Tokens.Space.m), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                    itemsIndexed(d.seasons, key = { _, s -> s.number }, contentType = { _, _ -> "tab" }) { i, s ->
                                        TvTab(s.name, null, selected = s.number == season, Modifier.rowStart(i == 0)) { season = s.number }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "episodes", contentType = "episodes") {
                        EnterRowAtStart {
                            when (episodes) {
                                Load.Loading -> LoadingBox(Modifier.height(160.dp))
                                is Load.Failed -> ReelButton("Couldn't load episodes · Try again", { retryEpisodes() }, Modifier.padding(horizontal = side), kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh)
                                is Load.Ready -> Column {
                                    TvRowPivot(side) {
                                        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                                            itemsIndexed(episodes.value, key = { _, e -> "${e.season}x${e.number}" }, contentType = { _, _ -> "episode" }) { i, e ->
                                                EpisodeCardTv(
                                                    e, progress[EpisodeNumber(e.season, e.number)], first = i == 0,
                                                    onFocus = { focusedEpisode.value = e }, onLongClick = { episodeOptions = e }, onClick = { onPlay(e.season, e.number) },
                                                )
                                            }
                                        }
                                    }
                                    EpisodeInfo(focusedEpisode, episodes.value.firstOrNull(), episodeImdb, Modifier.padding(horizontal = side))
                                }
                            }
                        }
                    }
                }
                if (d.seasons.isNotEmpty() && reviews.items.isNotEmpty()) {
                    item(key = "reviews", contentType = "reviews") { EnterRowAtStart { TvRowPivot(side) { com.kirtanya.reel.ui.title.ReviewsRow(reviews, side, 260.dp) } } }
                }
                val extras = d.videos.filter { it != d.trailer }
                if (extras.isNotEmpty()) item(key = "extras", contentType = "videos") { EnterRowAtStart { TvRowPivot(side) { VideoRow(extras, side, Tokens.Cinema.thumbWidthTv) } } }
                if (d.cast.isNotEmpty()) {
                    item(key = "cast", contentType = "cast") {
                        EnterRowAtStart {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                SectionHeader("Cast", Modifier.padding(horizontal = side))
                                TvRowPivot(side) {
                                    LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.s), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                                        itemsIndexed(d.cast, key = { i, c -> "$i:${c.id}" }, contentType = { _, _ -> "person" }) { i, c ->
                                            RingAvatar(
                                                TmdbImages.profile(c.profilePath), c.name, 60.dp, modifier = Modifier.rowStart(i == 0), sublabel = c.character, placeholder = c.name.take(1),
                                                onClick = { c.id?.let { onOpenDest(Dest.Person(it, c.name)) } },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (d.collection != null) {
                    item(key = "franchise", contentType = "posters") { EnterRowAtStart { com.kirtanya.reel.ui.title.FranchiseRow(d, side, Tokens.Cinema.posterTv, onOpen, onOpenDest, tv = true) } }
                }
                if (d.recommendations.isNotEmpty()) {
                    item(key = "more", contentType = "posters") { EnterRowAtStart { TvPosterRow("More like this", d.recommendations, side, Tokens.Cinema.posterTv, onOpen) } }
                }
                if (d.genreList.isNotEmpty()) {
                    item(key = "genres", contentType = "genres") {
                        EnterRowAtStart {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                SectionHeader("Browse by genre", Modifier.padding(horizontal = side))
                                TvRowPivot(side) { GenreChips(d.genreList, side, onOpen = { g -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = d.summary.ref.type)) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How far the backdrop darkens when focus is below the header. */
private const val DIM = 0.9f

/** Under a focused item the page also shows its caption / the episode story: scroll that far into view too. */
private val RowReveal = 88.dp

/** Back to the top over the page glide time (a fixed-length tween, not the list's default spring). */
private suspend fun LazyListState.glideToTop() {
    if (firstVisibleItemIndex == 0) animateScrollBy(-firstVisibleItemScrollOffset.toFloat(), tween(TvMotion.columnGlideMs, easing = TvMotion.easing))
    else animateScrollToItem(0)
}

private val EPISODE_WIDTH = 240.dp
private val EPISODE_SHAPE = RoundedCornerShape(Tokens.Radius.media)

/**
 * An episode: its still with a thin progress line (or a tick when seen) and its name under it; nothing else, so the row stays
 * light. The story, runtime and IMDb score of the focused one show under the row ([EpisodeInfo]).
 */
@Composable
private fun EpisodeCardTv(e: Episode, watched: WatchEntry?, first: Boolean, onFocus: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(EPISODE_WIDTH), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Box(
            Modifier.rowStart(first).fillMaxWidth().aspectRatio(16f / 9f)
                .focusCard(shape = EPISODE_SHAPE, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick)
                .border(Tokens.Size.hairline, Tokens.Colors.hairline, EPISODE_SHAPE),
        ) {
            // w500: the card is 240 dp wide = 480 px on the TV.
            RemoteImage(TmdbImages.still(e.stillPath, "w500"), Modifier.fillMaxSize())
            when {
                watched?.finished == true -> Pill("Watched", Modifier.align(Alignment.TopStart).padding(Tokens.Space.s), icon = Icons.Rounded.Check, accent = true)
                watched != null -> GradientProgress(watched.progress, Modifier.align(Alignment.BottomCenter).padding(Tokens.Space.s), height = 3.dp, track = Tokens.Colors.glass)
            }
        }
        Text(EpisodeFacts.title(e), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The focused episode's runtime, date, IMDb score and story, two lines: the same height whatever is focused. */
@Composable
private fun EpisodeInfo(focused: State<Episode?>, fallback: Episode?, imdb: Map<Int, Double>, modifier: Modifier = Modifier) {
    val e = focused.value ?: fallback ?: return
    Column(modifier.fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            com.kirtanya.reel.ui.title.EpisodeImdb(imdb[e.number])
            Text(EpisodeFacts.facts(e), style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(e.overview, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
