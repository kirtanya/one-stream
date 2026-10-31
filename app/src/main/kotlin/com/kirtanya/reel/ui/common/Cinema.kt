package com.kirtanya.reel.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.WatchlistItem
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.core.tmdb.Video
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

// The cinematic layer (docs/DESIGN_STYLE.md §3): full-bleed heroes with scrims, title logos,
// the "★ 8.4 · 2024 · 2h 19m" line, trailers, My list.

/**
 * A title's logo artwork (TMDB) in place of its name, fitted inside [maxWidth] × [maxHeight] and
 * anchored bottom-start, always in its own colours: logos carry their own styling (gradients, 3D,
 * textures), so it is never recoloured (the user's call, 0.9.1). The name as text when there's no
 * logo or it fails to load.
 */
@Composable
fun TitleLogo(
    logoPath: String?,
    title: String,
    style: TextStyle,
    maxWidth: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    textMaxLines: Int = 3,
    textAlign: TextAlign? = null,
) {
    var failed by remember(logoPath) { mutableStateOf(false) }
    if (logoPath == null || failed) {
        Text(title, style = style, color = Tokens.Colors.text, maxLines = textMaxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val request = remember(logoPath) { ImageRequest.Builder(context).data(TmdbImages.titleLogo(logoPath)).build() }
    AsyncImage(
        model = request,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        alignment = Alignment.BottomStart,
        onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
        modifier = modifier.width(maxWidth).height(maxHeight),
    )
}

/**
 * Full-bleed picture for a hero: cycles through [urls] with a slow cross-fade. No zoom drift: a full-screen
 * layer animating while idle costs the TV chip ~half its frame rate. A tonal wash when there's no picture at all. Scrims are the caller's ([HeroScrims]).
 */
@Composable
fun HeroImage(urls: List<String>, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, fadeMs: Int = Tokens.Motion.slideFadeMs) {
    if (urls.isEmpty()) {
        Box(modifier.background(Brush.verticalGradient(listOf(Tokens.Colors.accentDeep, Tokens.Colors.canvas))))
        return
    }
    var index by remember(urls) { mutableIntStateOf(0) }
    if (urls.size > 1) {
        LaunchedEffect(urls) {
            while (true) {
                delay(Tokens.Motion.slideMs.toLong())
                index = (index + 1) % urls.size
            }
        }
    }
    Box(modifier.clipToBounds()) {
        Crossfade(urls[index.coerceIn(0, urls.lastIndex)], Modifier.fillMaxSize(), animationSpec = tween(fadeMs), label = "hero") { url ->
            RemoteImage(url, Modifier.fillMaxSize(), alignment = alignment)
        }
    }
}

/** The canvas fades over a hero: bottom (where the text sits), top (status bar), left on TV. */
@Composable
fun BoxScope.HeroScrims(top: Boolean = true, start: Boolean = false, topHeight: Dp = 140.dp) {
    if (start) Box(Modifier.matchParentSize().background(Tokens.Gradients.heroStart))
    Box(Modifier.matchParentSize().background(Tokens.Gradients.heroBottom))
    if (top) Box(Modifier.fillMaxWidth().height(topHeight).background(Tokens.Gradients.heroTop))
}

/**
 * "★ 8.2 · 2024 · 2h 46m · UA": the rating in the accent colour, then the facts. Breaks only
 * between parts, never inside one ("2h 46m" stays together).
 */
@Composable
fun MetaLine(rating: Double?, parts: List<String>, modifier: Modifier = Modifier, style: TextStyle = Tokens.Type.label, maxLines: Int = 2) {
    if (rating == null && parts.none { it.isNotBlank() }) return
    val text = buildAnnotatedString {
        if (rating != null) {
            withStyle(SpanStyle(color = Tokens.Colors.accentBright)) { append("★ %.1f".format(rating)) }
        }
        parts.filter { it.isNotBlank() }.forEachIndexed { i, p ->
            if (rating != null || i > 0) append("  ·  ")
            append(p.replace(' ', ' '))
        }
    }
    Text(text, style = style, color = Tokens.Colors.textSecondary, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** "2h 46m" / "48m". */
fun runtimeLabel(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

/** The facts after the rating, for a title page: year, runtime or seasons, certificate. */
fun detailsParts(d: TitleDetails): List<String> = listOfNotNull(
    d.summary.year?.toString(),
    if (d.summary.ref.type == MediaType.TV) d.seasons.count { it.number > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
    else d.runtimeMin?.let(::runtimeLabel),
    d.certification,
)

/** For a card or hero without details: "Film · 2024". */
fun summaryParts(t: TitleSummary): List<String> = listOfNotNull(if (t.ref.type == MediaType.TV) "Series" else "Film", t.year?.toString())

// ─── My list ───────────────────────────────────────────────────────────────────────────────

/** Whether [title] is on My list, and a toggle (shows a short confirmation). */
@Composable
fun rememberWatchlistToggle(title: TitleSummary): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val store = ReelApp.get(context).watchlist
    val saved by remember(title.ref) { store.isSaved(title.ref) }.collectAsState(initial = store.contains(title.ref))
    return saved to {
        val now = store.toggle(title)
        Toast.makeText(context, if (now) "Added to My list" else "Removed from My list", Toast.LENGTH_SHORT).show()
    }
}

/** Adds a title to My list or takes it off, with a short confirmation: long-press on a poster. */
@Composable
fun rememberWatchlistToggler(): (TitleSummary) -> Unit {
    val context = LocalContext.current
    val store = ReelApp.get(context).watchlist
    return remember(store) {
        { title ->
            val now = store.toggle(title)
            Toast.makeText(context, if (now) "Added “${title.title}” to My list" else "Removed “${title.title}” from My list", Toast.LENGTH_SHORT).show()
        }
    }
}

/** My list, newest first, as cards. */
@Composable
fun rememberWatchlist(): List<TitleSummary> {
    val store = ReelApp.get(LocalContext.current).watchlist
    val items by store.items.collectAsState(initial = emptyList())
    return remember(items) { items.mapNotNull(WatchlistItem::toSummary) }
}

private fun WatchlistItem.toSummary(): TitleSummary? = ref?.let { TitleSummary(it, title, year, posterPath, backdropPath, rating = null, overview = "") }

fun watchlistIcon(saved: Boolean): ImageVector = if (saved) Icons.Rounded.Check else Icons.Rounded.Add

// ─── Trailers ──────────────────────────────────────────────────────────────────────────────

/** Opens a TMDB video in YouTube (the app on phone and TV, else the browser). */
fun openVideo(context: Context, video: Video) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(video.youtubeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Install YouTube to watch the trailer", Toast.LENGTH_SHORT).show()
    }
}

/** "Trailers & extras": 16:9 thumbnails with a play mark, name underneath. */
@Composable
fun VideoRow(videos: List<Video>, sidePadding: Dp, thumbWidth: Dp, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null) {
    if (videos.isEmpty()) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(Tokens.Radius.media)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        SectionHeader("Trailers & extras", Modifier.padding(horizontal = sidePadding))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            items(videos, key = { it.key }) { v ->
                Column(Modifier.width(thumbWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Box(
                        Modifier
                            .then(if (firstFocus != null && v == videos.first()) Modifier.focusRequester(firstFocus) else Modifier)
                            .rowStart(v == videos.first())
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .focusCard(shape = shape, onClick = { openVideo(context, v) })
                            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
                    ) {
                        RemoteImage(v.thumbnailUrl, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tokens.Colors.scrim)))
                        Box(
                            Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape)
                                .background(Tokens.Colors.glass).border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, null, tint = Tokens.Colors.text, modifier = Modifier.size(22.dp)) }
                    }
                    Text(v.name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.type, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

// ─── Phone action tiles ────────────────────────────────────────────────────────────────────

/** Icon over a short label (My list, Trailer, Download…): equal-width tiles under Play. */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Column(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = Tokens.Cinema.actionTileHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .focusCard(shape = RoundedCornerShape(Tokens.Radius.m), onClick = { if (enabled) onClick() })
            .padding(vertical = Tokens.Space.s, horizontal = Tokens.Space.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = if (active) Tokens.Colors.accentBright else Tokens.Colors.text, modifier = Modifier.size(24.dp))
        Text(label, style = Tokens.Type.caption, color = if (active) Tokens.Colors.accentBright else Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ─── Screen transitions ────────────────────────────────────────────────────────────────────

/** Screen to screen: the new one fades in with a slight zoom while the old one fades out. */
fun <S> AnimatedContentTransitionScope<S>.pageTransition(): ContentTransform =
    (fadeIn(tween(Tokens.Motion.pageMs)) + scaleIn(tween(Tokens.Motion.pageMs), initialScale = Tokens.Motion.pageZoom)) togetherWith
        fadeOut(tween(Tokens.Motion.pageMs / 2))

/**
 * TV: no transition. Fading + zooming a full 1080p screen in over 320 ms drops the TV chip to a slideshow
 * (the new page also loads its data during it), so the new screen simply appears.
 */
fun <S> AnimatedContentTransitionScope<S>.tvPageTransition(): ContentTransform =
    (EnterTransition.None togetherWith ExitTransition.None).using(null)

/** Row of the title's genres as chips: each opens the genre. */
@Composable
fun GenreChips(genres: List<com.kirtanya.reel.core.tmdb.Genre>, sidePadding: Dp, onOpen: (com.kirtanya.reel.core.tmdb.Genre) -> Unit, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    LazyRow(
        modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(genres, key = { it.id }) { g -> ReelButton(g.name, { onOpen(g) }, Modifier.rowStart(g == genres.first()), kind = ButtonKind.Ghost) }
    }
}
