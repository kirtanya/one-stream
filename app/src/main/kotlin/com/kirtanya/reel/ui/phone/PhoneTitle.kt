package com.kirtanya.reel.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
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
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ActionTile
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GenreChips
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RingAvatar
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.VideoRow
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.openVideo
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
import com.kirtanya.reel.ui.title.shareTitle

@Composable
fun PhoneTitle(
    ref: TitleRef,
    initialSeason: Int? = null,
    onBack: () -> Unit,
    onPlay: (season: Int?, episode: Int?) -> Unit,
    onOpen: (TitleSummary) -> Unit = {},
    onOpenDest: (Dest) -> Unit = {},
) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (load) {
            Load.Loading -> TitleSkeleton()
            is Load.Failed -> EmptyState(
                Icons.Rounded.Refresh, "Couldn't load this title", load.message, Modifier.align(Alignment.Center),
            ) { ReelButton("Try again", { retry() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) }
            is Load.Ready -> TitleBody(load.value, initialSeason, onPlay, onOpen, onOpenDest)
        }
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun TitleBody(d: TitleDetails, initialSeason: Int?, onPlay: (Int?, Int?) -> Unit, onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit) {
    val context = LocalContext.current
    val gutter = Tokens.Space.phoneGutter
    val action = rememberPlayAction(d)
    val ratings = com.kirtanya.reel.ui.common.rememberTitleRatings(d)
    val reviews = com.kirtanya.reel.ui.title.rememberReviews(d.summary.ref)
    var season by rememberSaveable { mutableStateOf(initialSeason?.takeIf { s -> d.seasons.any { it.number == s } } ?: d.seasons.firstOrNull { it.number > 0 }?.number ?: d.seasons.firstOrNull()?.number) }
    val (episodes, retryEpisodes) = rememberSeason(d.summary.ref.id, season)
    val episodeImdb = if (d.summary.ref.type == MediaType.TV) com.kirtanya.reel.ui.title.rememberEpisodeImdb(d, season) else emptyMap()
    val progress = if (d.summary.ref.type == MediaType.TV) rememberEpisodeProgress(d.summary.ref.id) else emptyMap()
    val (saved, toggleSaved) = rememberWatchlistToggle(d.summary)
    val (watchedAll, toggleWatched) = com.kirtanya.reel.ui.title.rememberTitleWatched(d)
    var overviewOpen by rememberSaveable { mutableStateOf(false) }
    val openSources: (EpisodeNumber?) -> Unit = { ep -> onPlay(ep?.season, ep?.episode) }
    var episodeOptions by remember { mutableStateOf<Episode?>(null) }
    episodeOptions?.let { e ->
        EpisodeOptions(d, e, progress[EpisodeNumber(e.season, e.number)], onPlay = { s, n -> onPlay(s, n) }, onOpenDest = onOpenDest, onDismiss = { episodeOptions = null })
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Tokens.Space.xxl

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Material top app bar: appears (solid, with the title) once the header has scrolled away.
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom), verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            // Cinematic header: the backdrops edge to edge (cycling slowly), the poster's light spilling in, the logo.
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(Tokens.Cinema.titleHeader)) {
                    HeroImage(heroBackdrops(d), Modifier.fillMaxSize())
                    HeroScrims(top = true)
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        com.kirtanya.reel.ui.common.Eyebrow(d)
                        TitleLogo(d.logoPath, d.summary.title, Tokens.Type.display, Tokens.Cinema.logoMaxWidthPhone, Tokens.Cinema.logoMaxHeightPhone)
                        com.kirtanya.reel.ui.common.RatingsRow(ratings, d.summary.rating)
                    }
                }
            }
            if (d.tagline != null || d.summary.overview.isNotBlank()) {
                item(key = "about") {
                    Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        d.tagline?.let { Text("“$it”", style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
                        if (d.summary.overview.isNotBlank()) {
                            // Three lines, then "More": the whole paragraph is the tap target, so there's no tiny link to hit.
                            var clipped by remember(d.summary.overview) { mutableStateOf(false) }
                            Column(
                                Modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.s), onClick = { overviewOpen = !overviewOpen }),
                                verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                            ) {
                                Text(
                                    d.summary.overview,
                                    style = Tokens.Type.body,
                                    color = Tokens.Colors.textSecondary,
                                    maxLines = if (overviewOpen) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (!overviewOpen) clipped = it.hasVisualOverflow },
                                )
                                if (clipped || overviewOpen) Text(if (overviewOpen) "Less" else "More", style = Tokens.Type.label, color = Tokens.Colors.text)
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    ReelButton(action.label, { runPlayAction(context, action, openSources) }, Modifier.fillMaxWidth().pressScale(0.98f), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
                    if (action is PlayAction.Resume) GradientProgress(action.entry.progress, height = 3.dp)
                    val ep = (action as? PlayAction.Resume)?.entry?.playback?.episode ?: (action as? PlayAction.Choose)?.episode
                    Row(Modifier.fillMaxWidth()) {
                        ActionTile(watchlistIcon(saved), "My list", toggleSaved, Modifier.weight(1f).pressScale(), active = saved)
                        d.trailer?.let { t -> ActionTile(Icons.Rounded.Movie, "Trailer", { openVideo(context, t) }, Modifier.weight(1f).pressScale()) }
                        ActionTile(Icons.Rounded.Hub, "Sources", { openSources(ep) }, Modifier.weight(1f).pressScale())
                        // Seen: the whole film or show (every aired episode); seasons have no watched line of their own. "Seen", not
                        // "Watched": five tiles share 360 dp, and "Watched" cuts off at large text.
                        ActionTile(if (watchedAll) Icons.Rounded.Check else Icons.Rounded.Visibility, "Seen", toggleWatched, Modifier.weight(1f).pressScale(), active = watchedAll)
                        ActionTile(Icons.Rounded.Share, "Share", { shareTitle(context, d) }, Modifier.weight(1f).pressScale())
                    }
                }
            }
            // Films: reviews right under the actions (the approved order: hero, ratings, plot, actions, reviews).
            // Series: after the season's episodes.
            if (d.seasons.isEmpty() && reviews.items.isNotEmpty()) {
                item(key = "reviews") { com.kirtanya.reel.ui.title.ReviewsRow(reviews, gutter, REVIEW_CARD_WIDTH_PHONE) }
            }
            if (d.genreList.isNotEmpty()) {
                item(key = "genres") {
                    GenreChips(d.genreList, gutter, onOpen = { g -> onOpenDest(Dest.Collection(g.name, genre = g, genreType = d.summary.ref.type)) })
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
                    Load.Loading -> item(key = "episodes-loading") { EpisodesSkeleton(Modifier.padding(horizontal = gutter)) }
                    is Load.Failed -> item(key = "episodes-failed") { ReelButton("Couldn't load episodes · Try again", { retryEpisodes() }, Modifier.padding(horizontal = gutter), kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) }
                    is Load.Ready -> items(episodes.value, key = { "${it.season}x${it.number}" }) { e ->
                        EpisodeRow(e, progress[EpisodeNumber(e.season, e.number)], episodeImdb[e.number], Modifier.padding(horizontal = gutter), onLongClick = { episodeOptions = e }) { onPlay(e.season, e.number) }
                    }
                }
            }
            if (d.seasons.isNotEmpty() && reviews.items.isNotEmpty()) {
                item(key = "reviews") { com.kirtanya.reel.ui.title.ReviewsRow(reviews, gutter, REVIEW_CARD_WIDTH_PHONE) }
            }
            // The main trailer has its own button; the rest (teasers, clips) go here.
            val extras = d.videos.filter { it != d.trailer }
            if (extras.isNotEmpty()) {
                item(key = "extras") { VideoRow(extras, gutter, Tokens.Cinema.thumbWidthPhone) }
            }
            if (d.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SectionHeader("Cast", Modifier.padding(horizontal = gutter))
                        LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                            items(d.cast) { c ->
                                RingAvatar(
                                    TmdbImages.profile(c.profilePath), c.name, 56.dp, sublabel = c.character, placeholder = c.name.take(1),
                                    onClick = c.id?.let { id -> { onOpenDest(Dest.Person(id, c.name)) } },
                                )
                            }
                        }
                    }
                }
            }
            if (d.collection != null) {
                item(key = "franchise") { com.kirtanya.reel.ui.title.FranchiseRow(d, gutter, 104.dp, onOpen, onOpenDest) }
            }
            if (d.recommendations.isNotEmpty()) {
                item(key = "more") { PosterRow("More like this", d.recommendations, posterWidth = 104.dp, sidePadding = gutter, onOpen = onOpen) }
            }
        }
        // Fades with opacity only (a graphicsLayer lambda), so crossing the header's edge recomposes nothing.
        val barAlpha by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, androidx.compose.animation.core.tween(180, easing = EmphasisedDecelerate), label = "bar")
        Box(
            Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha }.background(Tokens.Colors.surface1).statusBarsPadding().height(56.dp).padding(start = 64.dp, end = Tokens.Space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(d.summary.title, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Episode card: still (with progress or a watched tick), number + name, runtime, two lines of story. */
@Composable
private fun EpisodeRow(e: Episode, watched: WatchEntry?, imdb: Double?, modifier: Modifier = Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    val stillShape = RoundedCornerShape(Tokens.Radius.m)
    Row(
        modifier.fillMaxWidth().pressScale(0.98f).focusCard(shape = shape, onLongClick = onLongClick, onClick = onClick).padding(Tokens.Space.xs),
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
            Text(com.kirtanya.reel.core.tmdb.EpisodeFacts.title(e), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val facts = com.kirtanya.reel.core.tmdb.EpisodeFacts.facts(e)
            if (facts.isNotEmpty() || imdb != null) {
                // Wraps under the pill at big font sizes rather than cutting the date short.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
                    com.kirtanya.reel.ui.title.EpisodeImdb(imdb)
                    if (facts.isNotEmpty()) Text(facts, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                }
            }
            if (e.overview.isNotBlank()) Text(e.overview, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** While the title loads: the header's shape, a Play-sized bar and the action tiles, so nothing jumps when it arrives. */
@Composable
internal fun TitleSkeleton() {
    SkeletonHost {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Box(Modifier.fillMaxWidth().height(Tokens.Cinema.titleHeader).skeletonHero()) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SkeletonBlock(220.dp, 40.dp)
                    SkeletonBlock(160.dp, 16.dp)
                }
            }
            Column(Modifier.padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                SkeletonBlock(null, 14.dp, Modifier.fillMaxWidth())
                SkeletonBlock(null, 14.dp, Modifier.fillMaxWidth(0.8f))
            }
            Box(Modifier.padding(horizontal = Tokens.Space.phoneGutter).fillMaxWidth().height(Tokens.Size.buttonLarge).skeleton(Tokens.Radius.pill))
        }
    }
}

/** Episode list while a season loads: three rows with the real row's shape. */
@Composable
private fun EpisodesSkeleton(modifier: Modifier = Modifier) {
    SkeletonHost {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            repeat(3) {
                Row(Modifier.padding(Tokens.Space.xs), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).skeleton(Tokens.Radius.m))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SkeletonBlock(null, 14.dp, Modifier.fillMaxWidth(0.8f))
                        SkeletonBlock(null, 12.dp, Modifier.fillMaxWidth(0.5f))
                        SkeletonBlock(null, 12.dp, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private val REVIEW_CARD_WIDTH_PHONE = 264.dp
