package com.kirtanya.reel.ui.phone

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ContinueRow
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.ServicesRow
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun PhoneHome(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit, onSearch: () -> Unit, onDownloads: () -> Unit) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(Unit) { tmdb.home() }
    val continueItems = rememberContinueWatching()
    val gutter = Tokens.Space.phoneGutter

    LoadView(load, retry) { rows ->
        val hero = rows.first().items.take(6)
        val pager = rememberPagerState { hero.size }
        val current = hero.getOrNull(pager.currentPage)
        val glow by rememberAmbientColor(TmdbImages.poster(current?.posterPath, "w185"))

        Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
            AmbientGlow(glow, height = 700.dp, strength = 0.5f)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Tokens.Space.bottomBarClearance + Tokens.Space.xxxl),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
            ) {
                item(key = "header") {
                    Row(Modifier.statusBarsPadding().padding(start = gutter, end = Tokens.Space.s, top = Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                        com.kirtanya.reel.ui.common.BrandMark(36.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.width(Tokens.Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(greeting(), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                            Text("One Stream", style = Tokens.Type.title, color = Tokens.Colors.text)
                        }
                        GlassIconButton(Icons.Outlined.Search, "Search", onSearch)
                        GlassIconButton(Icons.Outlined.Download, "Downloads", onDownloads)
                    }
                }
                item(key = "hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = gutter), pageSpacing = Tokens.Space.s) { page ->
                            HeroCard(hero[page], onOpen = onOpen, onPlay = { item ->
                                if (item.ref.type == MediaType.MOVIE) onOpenDest(Dest.Sources(item.ref)) else onOpen(item)
                            })
                        }
                        PagerDots(hero.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                if (continueItems.isNotEmpty()) {
                    item(key = "continue") { ContinueRow(continueItems, cardWidth = 220.dp, sidePadding = gutter, onOpenDest = onOpenDest) }
                }
                item(key = "services") {
                    ServicesRow(gutter, 52.dp) { p -> onOpenDest(Dest.Collection(p.name, provider = p)) }
                }
                items(rows, key = { it.title }) { row ->
                    PosterRow(row.title, row.items, posterWidth = 104.dp, sidePadding = gutter, onOpen = onOpen, onSeeAll = { onOpenDest(Dest.Collection(row.title, items = row.items)) })
                }
            }
        }
    }
}

/** Big poster card: pills, title, Play + Details. Film → its sources; show → its page. */
@Composable
private fun HeroCard(item: TitleSummary, onOpen: (TitleSummary) -> Unit, onPlay: (TitleSummary) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(5f / 6f)
            .focusCard(shape = shape, scale = 1f, onClick = { onOpen(item) })
            .border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape),
    ) {
        RemoteImage(TmdbImages.poster(item.posterPath, "w780"), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Tokens.Colors.canvas.copy(alpha = 0.96f))))
        Column(Modifier.align(Alignment.BottomStart).padding(Tokens.Space.l), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                Pill("Trending", accent = true)
                Pill(if (item.ref.type == MediaType.TV) "Series" else "Film", onGlass = true)
                item.year?.let { Pill(it.toString(), onGlass = true) }
                item.rating?.let { Pill("★ %.1f".format(it), onGlass = true) }
            }
            Text(item.title, style = Tokens.Type.display, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.overview.isNotBlank()) Text(item.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                ReelButton("Play", { onPlay(item) }, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
                ReelButton("Details", { onOpen(item) }, kind = ButtonKind.Glass, icon = Icons.Outlined.Info)
            }
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 16.dp else 6.dp, label = "dot")
            Box(
                Modifier.height(6.dp).width(w).clip(RoundedCornerShape(Tokens.Radius.pill))
                    .background(if (i == current) Tokens.Gradients.brand else Brush.linearGradient(listOf(Tokens.Colors.surface3, Tokens.Colors.surface3))),
            )
        }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..22 -> "Good evening"
    else -> "Late-night show?"
}
