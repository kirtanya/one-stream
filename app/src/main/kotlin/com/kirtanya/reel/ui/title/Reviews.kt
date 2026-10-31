package com.kirtanya.reel.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirtanya.reel.core.tmdb.Review
import com.kirtanya.reel.core.tmdb.Reviews
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.common.ImdbPill
import com.kirtanya.reel.ui.common.LocalIsTv
import com.kirtanya.reel.ui.common.SectionHeader
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.rememberCatalog
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/** The title's reviews, or [Reviews.NONE] while loading or when TMDB can't be reached (the section hides). */
@Composable
fun rememberReviews(ref: TitleRef): Reviews {
    val catalog = rememberCatalog()
    return produceState(Reviews.NONE, ref) {
        value = runCatching { catalog.reviews(ref) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Reviews.NONE }
    }.value
}

/** Episode number → that episode's IMDb rating, for the season on screen (empty until known). */
@Composable
fun rememberEpisodeImdb(d: TitleDetails, season: Int?): Map<Int, Double> {
    val catalog = rememberCatalog()
    return produceState(emptyMap<Int, Double>(), d.summary.ref, d.imdbId, season) {
        value = emptyMap()
        if (season != null) value = runCatching { catalog.episodeImdb(d, season) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyMap() }
    }.value
}

/** An episode's own IMDb pill (small, the poster size). */
@Composable
fun EpisodeImdb(rating: Double?, modifier: Modifier = Modifier) {
    if (rating != null) ImdbPill(rating, modifier, onPoster = true)
}

/**
 * "Reviews · 12": cards that scroll sideways (avatar letter, author, ★ score, the first lines). Picking one
 * opens the whole review. Nothing at all when there are none.
 */
@Composable
fun ReviewsRow(reviews: Reviews, sidePadding: Dp, cardWidth: Dp, modifier: Modifier = Modifier) {
    if (reviews.items.isEmpty()) return
    var open by remember { mutableStateOf<Review?>(null) }
    open?.let { r -> ReviewSheet(r, onDismiss = { open = null }) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader("Reviews · ${reviews.total}", Modifier.padding(horizontal = sidePadding))
        LazyRow(
            Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            itemsIndexed(reviews.items, key = { i, r -> "$i:${r.author}" }) { _, r ->
                ReviewCard(r, Modifier.width(cardWidth)) { open = r }
            }
        }
    }
}

@Composable
private fun ReviewCard(r: Review, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Column(
        modifier
            .height(REVIEW_CARD_HEIGHT)
            .focusCard(shape = shape, onClick = onClick)
            .background(Tokens.Colors.surface1)
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.m),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        ReviewAuthor(r)
        Text(r.content, style = Tokens.Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Tokens.Colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** Avatar letter, name (one line, shortened when long), and the reviewer's score when they gave one. */
@Composable
private fun ReviewAuthor(r: Review, date: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Tokens.Colors.tonal), contentAlignment = Alignment.Center) {
            Text(r.initial, style = Tokens.Type.caption, color = Tokens.Colors.onTonal, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(r.author, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (date) r.createdAt?.take(10)?.let { d ->
                val label = runCatching { java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)) }.getOrNull()
                if (label != null) Text(label, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
            }
        }
        r.ratingLabel?.let { s ->
            Box(Modifier.clip(RoundedCornerShape(Tokens.Radius.xs)).background(Tokens.Colors.tonal).padding(horizontal = Tokens.Space.s - Tokens.Space.xxs, vertical = Tokens.Space.xxs)) {
                Text(s, style = Tokens.Type.caption, color = Tokens.Colors.onTonal, maxLines = 1)
            }
        }
    }
}

/** The whole review. Phone: a bottom sheet that scrolls. TV: a panel on the right; ↑ / ↓ scroll, Back closes. */
@Composable
fun ReviewSheet(r: Review, onDismiss: () -> Unit, isTv: Boolean = LocalIsTv.current) {
    if (isTv) ReviewPanelTv(r, onDismiss) else ReviewSheetPhone(r, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheetPhone(r: Review, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Colors.surface1,
        shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl),
        dragHandle = { Box(Modifier.padding(top = Tokens.Space.m).size(width = 32.dp, height = Tokens.Size.progressBar).clip(RoundedCornerShape(Tokens.Radius.pill)).background(Tokens.Colors.hairlineStrong)) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            ReviewAuthor(r, date = true)
            Text(r.content, style = Tokens.Type.body, color = Tokens.Colors.text)
        }
    }
}

@Composable
private fun ReviewPanelTv(r: Review, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Tokens.Colors.scrim)) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(REVIEW_PANEL_WIDTH)
                    .background(Tokens.Colors.surface1)
                    .border(Tokens.Size.hairline, Tokens.Colors.hairline)
                    .padding(horizontal = Tokens.Space.xl, vertical = Tokens.Space.tvSafeV),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            ) {
                ReviewAuthor(r, date = true)
                // The text is one focusable block: the remote's ↑ / ↓ scroll it (there's nothing else to move to).
                val shape = RoundedCornerShape(Tokens.Radius.m)
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = when (e.key) { Key.DirectionDown -> SCROLL_STEP; Key.DirectionUp -> -SCROLL_STEP; else -> return@onPreviewKeyEvent false }
                            scope.launch { scroll.animateScrollBy(step) }
                            true
                        }
                        .focusable(interactionSource = interaction)
                        .border(Tokens.Size.focusBorder, if (focused) Tokens.Colors.accentBright else Color.Transparent, shape)
                        .padding(Tokens.Space.m),
                ) {
                    Text(r.content, style = Tokens.Type.body, color = Tokens.Colors.text, modifier = Modifier.verticalScroll(scroll))
                }
                Text("↑ ↓ to read · Back to close", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
            }
        }
    }
}

private val REVIEW_CARD_HEIGHT = 132.dp
private val REVIEW_PANEL_WIDTH = 480.dp
private const val SCROLL_STEP = 180f
