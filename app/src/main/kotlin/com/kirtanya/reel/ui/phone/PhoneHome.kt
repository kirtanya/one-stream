package com.kirtanya.reel.ui.phone

import com.kirtanya.reel.ui.home.withoutHidden
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.data.HomeRow
import com.kirtanya.reel.ui.common.BrandMark
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ContinueRow
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.MetaLine
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.common.rememberWatchlist
import com.kirtanya.reel.ui.common.rememberWatchlistToggle
import com.kirtanya.reel.ui.common.summaryParts
import com.kirtanya.reel.ui.common.watchlistIcon
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.home.HeroSource
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.home.RowIds
import com.kirtanya.reel.ui.home.TitleRow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.CloudOff
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Phone Home: a full-bleed hero (the week's trending titles, swipe or wait), then Continue
 * watching, My list, your services and rows of posters. The header floats over the hero.
 */
@Composable
fun PhoneHome(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit, onAdd: () -> Unit) {
    val catalog = rememberCatalog()
    val (load, retry) = rememberLoad(HomeLayout.content) { catalog.home(HomeLayout.content) }
    val continueItems = rememberContinueWatching()
    val myList = rememberWatchlist()
    val because = com.kirtanya.reel.ui.home.rememberBecauseYouWatched()
    val hidden = com.kirtanya.reel.ui.home.rememberHiddenKeys()
    // Shows you follow (My list, Continue watching) with an episode out this week or coming soon.
    val followed = (myList.map { it.ref } + com.kirtanya.reel.ui.home.continueTitles(continueItems).map { it.ref }).filter { it.type == com.kirtanya.reel.core.tmdb.MediaType.TV }
    val newEpisodes = com.kirtanya.reel.ui.home.rememberNewEpisodes(followed)
    val gutter = Tokens.Space.phoneGutter
    val heroHeight = rememberHeroHeight()

    when (load) {
        Load.Loading -> HomeSkeleton(heroHeight, onAdd)
        is Load.Failed -> HomeProblem("Couldn't load today's titles", load.message, onAdd) { retry() }
        is Load.Ready -> {
            // TMDB answered but with nothing to show (e.g. a regional hiccup): say so instead of crashing.
            val rows = load.value.map { it.copy(items = it.items.withoutHidden(hidden)) }.filter { it.items.isNotEmpty() }
            if (rows.isEmpty()) {
                HomeProblem("Nothing to show right now", "Couldn't get today's titles. Check your connection and try again.", onAdd) { retry() }
                return
            }
            // Settings → Home screen picks what the hero shows (falls back to trending when that's empty).
            val hero = when (HomeLayout.hero) {
                HeroSource.MyList -> myList
                HeroSource.Continue -> com.kirtanya.reel.ui.home.continueTitles(continueItems)
                HeroSource.Trending -> emptyList()
            }.ifEmpty { rows.first().items }.take(6)
            val surprise = { (rows.flatMap { it.items } + myList).randomOrNull()?.let(onOpen) }
            val pager = rememberPagerState { hero.size }
            val list = rememberLazyListState()
            // Only while the hero is on screen: no point paging what can't be seen.
            val heroShowing by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
            AutoAdvance(pager, enabled = heroShowing && HomeLayout.heroAutoAdvance)

            LazyColumn(
                Modifier.fillMaxSize().background(Tokens.Colors.canvas),
                state = list,
                contentPadding = PaddingValues(bottom = Tokens.Space.bottomBarClearance + Tokens.Space.l),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                item(key = "hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                        Box(Modifier.fillMaxWidth().height(heroHeight)) {
                            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                                HeroPage(hero[page], pager, page, onOpen = onOpen, onPlay = { item ->
                                    if (item.ref.type == MediaType.MOVIE) onOpenDest(Dest.Sources(item.ref)) else onOpen(item)
                                })
                            }
                            HomeHeader(onAdd, onSurprise = { surprise() })
                        }
                        PagerDots(pager, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                // Rows in the order chosen in Settings → Home screen; hidden ones left out.
                val available = buildList {
                    if (continueItems.isNotEmpty()) add(RowIds.CONTINUE)
                    if (newEpisodes.isNotEmpty()) add(RowIds.NEW_EPISODES)
                    if (myList.isNotEmpty()) add(RowIds.MY_LIST)
                    if (because != null) add(RowIds.BECAUSE)
                    rows.forEach { r -> add(RowIds.tmdb(r.title)) }
                }
                HomeLayout.arrange(available).forEach { id ->
                    item(key = id) {
                        when (id) {
                            RowIds.CONTINUE -> ContinueRow(continueItems, cardWidth = 220.dp, sidePadding = gutter, onOpenDest = onOpenDest)
                            RowIds.MY_LIST -> TitleRow("My list", myList, gutter, onOpen, isTv = false, onSeeAll = { onOpenDest(Dest.Collection("My list", items = myList)) })
                            RowIds.BECAUSE -> because?.let { (title, items) -> TitleRow(title, items.withoutHidden(hidden), gutter, onOpen, isTv = false, onSeeAll = { onOpenDest(Dest.Collection(title, items = items)) }) }
                            RowIds.NEW_EPISODES -> com.kirtanya.reel.ui.home.NewEpisodesRow(newEpisodes, gutter, onOpen, isTv = false)
                            else -> rows.firstOrNull { RowIds.tmdb(it.title) == id }?.let { row ->
                                TitleRow(row.title, row.items, gutter, onOpen, isTv = false, onSeeAll = { onOpenDest(Dest.Collection(row.title, items = row.items)) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hero height: two thirds of the screen, within limits (fits a 360 × 800 dp phone with a row peeking). */
@Composable
internal fun rememberHeroHeight(): Dp {
    val screen = LocalConfiguration.current.screenHeightDp.dp
    return (screen * Tokens.Cinema.heroShare).coerceIn(Tokens.Cinema.heroMin, Tokens.Cinema.heroMax)
}

/** Pages on by itself every few seconds, but never while you're swiping or the hero is scrolled away. */
@Composable
private fun AutoAdvance(pager: PagerState, enabled: Boolean) {
    if (pager.pageCount < 2) return
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager.settledPage, dragged, enabled) {
        if (dragged || !enabled) return@LaunchedEffect
        delay(Tokens.Motion.slideMs.toLong())
        pager.animateScrollToPage((pager.settledPage + 1) % pager.pageCount)
    }
}

/** Brand on the left, Add a link on the right; floats over the hero's top scrim. */
@Composable
private fun HomeHeader(onAdd: () -> Unit, onSurprise: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = Tokens.Space.phoneGutter, end = Tokens.Space.s, top = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(36.dp)
        Spacer(Modifier.width(Tokens.Space.m))
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = Tokens.Type.caption, color = Tokens.Colors.text, maxLines = 1)
            Text("One Stream", style = Tokens.Type.title, color = Tokens.Colors.text, maxLines = 1)
        }
        if (onSurprise != null) GlassIconButton(Icons.Rounded.Shuffle, "Surprise me", onSurprise)
        GlassIconButton(Icons.Rounded.Add, "Add a magnet or .torrent", onAdd)
    }
}

/**
 * One hero page: the backdrop edge to edge (it drifts slower than the swipe), the title's logo,
 * "★ 8.2 · Film · 2024", then Play, My list and Info.
 */
@Composable
private fun HeroPage(item: TitleSummary, pager: PagerState, page: Int, onOpen: (TitleSummary) -> Unit, onPlay: (TitleSummary) -> Unit) {
    val catalog = rememberCatalog()
    val (logo, _) = rememberLoad(item.ref) { catalog.logo(item.ref) }
    val (saved, toggle) = rememberWatchlistToggle(item)
    val art = TmdbImages.backdrop(item.backdropPath) ?: TmdbImages.poster(item.posterPath, "w780")
    Box(Modifier.fillMaxSize().clipToBounds()) {
        HeroImage(
            listOfNotNull(art),
            Modifier.fillMaxSize().graphicsLayer {
                // Parallax: the picture moves at half the speed of the page.
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                translationX = offset * size.width * 0.5f
            },
        )
        HeroScrims(top = true, topHeight = 160.dp)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Tokens.Space.phoneGutter)
                .graphicsLayer {
                    // The text dissolves as its page slides away and the next one's arrives: a cross-fade of
                    // the copy over the swipe's own motion (opacity + a small drift; draw phase only).
                    val away = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    alpha = 1f - away
                    translationX = -away * size.width * 0.12f
                },
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            Box(Modifier.heightIn(min = Tokens.Cinema.logoMaxHeightPhone), contentAlignment = Alignment.BottomStart) {
                when (logo) {
                    Load.Loading -> Unit
                    is Load.Failed -> TitleLogo(null, item.title, Tokens.Type.display, Tokens.Cinema.logoMaxWidthPhone, Tokens.Cinema.logoMaxHeightPhone, textMaxLines = 2)
                    is Load.Ready -> TitleLogo(logo.value, item.title, Tokens.Type.display, Tokens.Cinema.logoMaxWidthPhone, Tokens.Cinema.logoMaxHeightPhone, textMaxLines = 2)
                }
            }
            MetaLine(item.rating, summaryParts(item), maxLines = 1)
            Row(
                Modifier.padding(top = Tokens.Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReelButton("Play", { onPlay(item) }, Modifier.weight(1f).pressScale(), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
                ReelButton("My list", toggle, Modifier.weight(1f).pressScale(), kind = ButtonKind.Glass, icon = watchlistIcon(saved), large = true)
                GlassIconButton(Icons.Outlined.Info, "Details", { onOpen(item) }, Modifier.pressScale())
            }
        }
    }
}

/**
 * While Home loads: the header and the shapes of what's coming (no spinner on an empty page), with one soft
 * shimmer sweeping across them (drawn, so it costs no recomposition). The hero's own shape: a tall tonal
 * block fading into the canvas, the title bar, the facts bar and the two buttons.
 */
@Composable
private fun HomeSkeleton(heroHeight: Dp, onAdd: () -> Unit) {
    SkeletonHost {
        Column(Modifier.fillMaxSize().background(Tokens.Colors.canvas), verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Box(Modifier.fillMaxWidth().height(heroHeight).skeletonHero()) {
                HomeHeader(onAdd)
                Column(Modifier.align(Alignment.BottomStart).padding(Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SkeletonBlock(220.dp, 40.dp, radius = Tokens.Radius.s)
                    SkeletonBlock(140.dp, 16.dp, radius = Tokens.Radius.s)
                    Row(Modifier.padding(top = Tokens.Space.xs), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SkeletonBlock(150.dp, Tokens.Size.buttonLarge, radius = Tokens.Radius.pill)
                        SkeletonBlock(150.dp, Tokens.Size.buttonLarge, radius = Tokens.Radius.pill)
                    }
                }
            }
            repeat(2) {
                Column(Modifier.padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SkeletonBlock(160.dp, 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                        repeat(4) { Box(Modifier.width(104.dp).aspectRatio(2f / 3f).skeleton(Tokens.Radius.m)) }
                    }
                }
            }
        }
    }
}

/** Failed or empty Home: the header stays, with what happened and Try again. */
@Composable
private fun HomeProblem(title: String, body: String, onAdd: () -> Unit, onRetry: () -> Unit) {
    val offline = com.kirtanya.reel.ui.common.rememberOffline()
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        HomeHeader(onAdd)
        EmptyState(
            if (offline) Icons.Rounded.CloudOff else Icons.Rounded.Refresh,
            if (offline) "You're offline" else title,
            if (offline) "Downloads still play. Home comes back when you're connected." else body,
            Modifier.align(Alignment.Center),
        ) {
            ReelButton("Try again", onRetry, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
        }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..22 -> "Good evening"
    else -> "Late-night show?"
}
