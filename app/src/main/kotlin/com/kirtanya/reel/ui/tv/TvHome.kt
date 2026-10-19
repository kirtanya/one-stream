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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ContinueRow
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.PosterRow
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.continueDetail
import com.kirtanya.reel.ui.common.metaLine
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.theme.Tokens
import androidx.compose.foundation.layout.Row
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.common.ServicesRow
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.Backdrop
import kotlinx.coroutines.delay

/** What the big info block and backdrop show for the focused card. [key] identifies the card. */
private data class HomeInfo(
    val key: String,
    val title: String,
    val meta: String,
    val overview: String,
    val backdropPath: String?,
    val posterPath: String?,
    val pills: List<String> = emptyList(),
)

private fun TitleSummary.info() = HomeInfo(
    "poster:$ref", title, metaLine(this), overview, backdropPath, posterPath,
    listOfNotNull(if (ref.type.name == "TV") "Series" else "Film", year?.toString(), rating?.let { "★ %.1f".format(it) }),
)

private fun ContinueItem.info() = HomeInfo(
    "continue:${entry.playback.titleKey}", entry.playback.title, continueDetail(this), entry.playback.subtitle.orEmpty(),
    entry.playback.backdropPath, entry.playback.posterPath, listOf(continueDetail(this)),
)

/**
 * Cinematic home: the focused card's backdrop fills the screen, its details sit top-left,
 * Continue watching and rows of posters below. Like One Launcher's TV home.
 */
@Composable
fun TvHome(onOpen: (TitleSummary) -> Unit, onOpenDest: (Dest) -> Unit) {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    val (load, retry) = rememberLoad(Unit) { tmdb.home() }
    val continueItems = rememberContinueWatching()
    LoadView(load, retry, Modifier.padding(top = TvTopBarHeight)) { rows ->
        val initial = continueItems.firstOrNull()?.info() ?: rows.first().items.first().info()
        var focused by remember { mutableStateOf(initial) }
        var backdrop by remember { mutableStateOf(focused.backdropPath) }
        // Don't reload the big image for every card the remote skips over.
        LaunchedEffect(focused) {
            delay(260)
            backdrop = focused.backdropPath
        }
        val firstCard = remember { FocusRequester() }
        val columnState = rememberLazyListState()
        val continueState = rememberLazyListState()
        // The first poster row's scroll, so focus can come back to poster 0 after the last Continue card goes.
        val firstRowState = rememberLazyListState()
        RequestFocusOnce(firstCard)
        // A Continue watching card was removed (hold OK) or changed: refresh the header and put the
        // remote's focus back on the first card instead of losing it.
        LaunchedEffect(continueItems) {
            if (!focused.key.startsWith("continue:")) return@LaunchedEffect
            val still = continueItems.firstOrNull { "continue:${it.entry.playback.titleKey}" == focused.key }
            if (still != null) {
                focused = still.info()
            } else {
                focused = continueItems.firstOrNull()?.info() ?: rows.first().items.first().info()
                // The first card may have scrolled out (and been disposed): bring it back, then focus it.
                runCatching { columnState.scrollToItem(0) }
                if (continueItems.isNotEmpty()) runCatching { continueState.scrollToItem(0) }
                else runCatching { firstRowState.scrollToItem(0) }
                repeat(10) {
                    withFrameNanos { }
                    // requestFocus() doesn't throw when the node isn't attached; the Boolean overload tells us.
                    if (runCatching { firstCard.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }

        val glow by rememberAmbientColor(TmdbImages.poster(focused.posterPath, "w185"))
        Box(Modifier.fillMaxSize()) {
            // The focused title fills the room: its backdrop, and light in its colour.
            Backdrop(TmdbImages.backdrop(backdrop), Modifier.fillMaxSize(), fromLeft = true)
            AmbientGlow(glow, height = 620.dp, strength = 0.38f)

            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(TvTopBarHeight))
                Column(
                    Modifier.fillMaxWidth(0.52f).height(220.dp).padding(horizontal = Tokens.Space.tvSafeH),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                        focused.pills.forEachIndexed { i, p -> Pill(p, accent = i == 0 && focused.key.startsWith("continue:"), onGlass = true) }
                    }
                    Text(focused.title, style = Tokens.Type.hero, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(focused.overview, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(
                    Modifier.weight(1f),
                    state = columnState,
                    contentPadding = PaddingValues(bottom = Tokens.Space.tvSafeV),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                ) {
                    if (continueItems.isNotEmpty()) {
                        item(key = "continue") {
                            ContinueRow(
                                continueItems,
                                cardWidth = 260.dp,
                                sidePadding = Tokens.Space.tvSafeH,
                                onOpenDest = onOpenDest,
                                onFocus = { focused = it.info() },
                                firstFocus = firstCard,
                                listState = continueState,
                            )
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.title }) { i, row ->
                        PosterRow(
                            title = row.title,
                            items = row.items,
                            posterWidth = 120.dp,
                            sidePadding = Tokens.Space.tvSafeH,
                            onOpen = onOpen,
                            showText = false,
                            onFocus = { focused = it.info() },
                            firstFocus = if (i == 0 && continueItems.isEmpty()) firstCard else null,
                            onSeeAll = { onOpenDest(Dest.Collection(row.title, items = row.items)) },
                            listState = if (i == 0) firstRowState else androidx.compose.foundation.lazy.rememberLazyListState(),
                        )
                        // "On your services" after the first row, like a second hero.
                        if (i == 0) {
                            Spacer(Modifier.height(Tokens.Space.l))
                            ServicesRow(Tokens.Space.tvSafeH, 80.dp) { p -> onOpenDest(Dest.Collection(p.name, provider = p)) }
                        }
                    }
                }
            }
        }
    }
}
