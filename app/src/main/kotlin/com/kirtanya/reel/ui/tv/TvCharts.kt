package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.core.browse.GridNav
import com.kirtanya.reel.core.browse.TopCharts
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.PosterCard
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.home.rememberHiddenKeys
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens

/**
 * One Top chart (Categories → Top charts): up to 100 titles, best first, each poster carrying its number. A page above
 * the tabs like [TvCategoryPage], laid out the same way (heading, a line under it, the grid, the focused title named
 * above the grid). The whole chart is one load (see [TopCharts]) so numbers are final the moment posters show; a hidden
 * title leaves a gap in the numbers rather than renumbering. Whose titles it lists follows Settings → Home screen → Content.
 * Coming back from a title the remote returns to the poster it left.
 */
@Composable
fun TvChartPage(dest: Dest.Chart, onOpen: (TitleSummary) -> Unit) {
    val chart = dest.chart
    val catalog = rememberCatalog()
    val content = HomeLayout.content
    val (load, retry) = rememberLoad(chart to content) { catalog.chart(chart, content) }
    val hidden = rememberHiddenKeys()
    val items = (load as? Load.Ready)?.value.orEmpty()
    val shown = remember(items, hidden) { TopCharts.ranked(items, hidden) }

    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val requesters = rememberRequesters(shown.size)
    val focused = remember { mutableStateOf<TitleSummary?>(null) }
    val wanted = rememberSaveable { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf(false) }

    // The first time posters are there: the poster focused before (coming back from a title), or the first.
    LaunchedEffect(shown.isNotEmpty()) {
        if (shown.isEmpty() || restored) return@LaunchedEffect
        restored = true
        val at = GridNav.restore(wanted.intValue, shown.size)
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == at }) runCatching { grid.scrollToItem(at) }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { requesters[at].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val side = Tokens.Space.tvSafeH
    val retryFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Tokens.Colors.canvas).padding(horizontal = side)) {
        Spacer(Modifier.height(Tokens.Space.tvSafeV))
        Text(chart.label, style = Tokens.Type.hero, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(chart.caption(content), style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = Tokens.Space.s))
        FocusedTitleLine(focused, Modifier.padding(top = Tokens.Space.s))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                shown.isNotEmpty() -> TvColumnGlide(reveal = Tokens.Space.s) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(PosterColumns),
                        state = grid,
                        contentPadding = PaddingValues(top = Tokens.Space.s, bottom = Tokens.Space.tvSafeV),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.title.ref.toString() }, contentType = { _, _ -> "poster" }) { i, ranked ->
                            Box(Modifier.fillMaxWidth()) {
                                PosterCard(
                                    ranked.title, width = null, onClick = { onOpen(ranked.title) },
                                    modifier = Modifier.focusRequester(requesters[i]).gridMoves(i, shown.size, grid, scope, requesters),
                                    showText = false, onFocus = { focused.value = ranked.title; wanted.intValue = i },
                                )
                                RankBadge(ranked.rank, Modifier.align(Alignment.TopEnd))
                            }
                        }
                    }
                }
                load is Load.Failed -> {
                    RequestFocusOnce(retryFocus)
                    EmptyState(Icons.Rounded.Refresh, "Couldn't load ${chart.label}", load.message) {
                        ReelButton("Try again", { retry() }, Modifier.focusRequester(retryFocus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                    }
                }
                load is Load.Loading -> LoadingBox()
                else -> EmptyState(Icons.Rounded.Movie, "Nothing here yet", "TMDB has no titles for this chart right now.")
            }
        }
    }
}

/**
 * A title's place in the chart, on the poster's top-right corner: the IMDb pill holds the top-left and a poster-less card
 * writes its name along the bottom. A solid pill, no alpha.
 */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        "$rank", style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 1,
        modifier = modifier
            .padding(Tokens.Space.s)
            .clip(RoundedCornerShape(Tokens.Radius.xs))
            .background(Tokens.Colors.surface2)
            .padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xxs)
            .semantics { contentDescription = "Number $rank" },
    )
}
