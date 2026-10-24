package com.kirtanya.reel.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtanya.reel.core.ratings.MetaBand
import com.kirtanya.reel.core.ratings.RatingRules
import com.kirtanya.reel.core.ratings.TitleRatings
import com.kirtanya.reel.core.ratings.Tomato
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.theme.BrandColors
import com.kirtanya.reel.ui.theme.Tokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * IMDb's look: a yellow pill, the bold "IMDb" wordmark in a black box, then the score in black.
 * [onPoster] = the small, fixed-size one for poster corners (a logo-like mark, so it doesn't grow with the
 * font scale and still fits an 80 dp poster); otherwise the title page's bigger one, which does.
 */
@Composable
fun ImdbPill(rating: Double, modifier: Modifier = Modifier, onPoster: Boolean = false) {
    val density = LocalDensity.current
    // Fixed size on posters: dp turned into sp at this font scale, so 1.3× fonts don't push it past the poster.
    fun size(v: Float): TextUnit = if (onPoster) with(density) { v.dp.toSp() } else v.sp
    val word = if (onPoster) 9f else 12f
    val score = if (onPoster) 11f else 14f
    Row(
        modifier
            .clip(RoundedCornerShape(if (onPoster) Tokens.Radius.xs else 6.dp))
            .background(BrandColors.imdbYellow)
            .padding(start = if (onPoster) 3.dp else 4.dp, end = if (onPoster) 5.dp else 8.dp, top = if (onPoster) 2.dp else 3.dp, bottom = if (onPoster) 2.dp else 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "IMDb ${RatingRules.imdbLabel(rating)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (onPoster) 3.dp else 6.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(if (onPoster) 2.dp else 3.dp)).background(BrandColors.imdbBlack).padding(horizontal = if (onPoster) 3.dp else 4.dp, vertical = if (onPoster) 1.dp else 2.dp)) {
            Text(
                "IMDb", color = BrandColors.imdbYellow, maxLines = 1,
                style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = size(word), lineHeight = size(word + 3f), letterSpacing = (-0.2).sp),
            )
        }
        Text(
            RatingRules.imdbLabel(rating), color = BrandColors.imdbBlack, maxLines = 1,
            style = TextStyle(fontFamily = Tokens.fontFamily, fontWeight = FontWeight.Bold, fontSize = size(score), lineHeight = size(score + 3f)),
        )
    }
}

/**
 * The IMDb pill in a poster's corner: there at once when the rating is cached, else fetched for this
 * poster (only while it's on screen) and faded in. Nothing when IMDb has no rating.
 */
@Composable
fun PosterImdbBadge(ref: TitleRef, modifier: Modifier = Modifier) {
    val catalog = rememberCatalog()
    val cached = remember(ref) { catalog.cachedImdb(ref) }
    var rating by remember(ref) { mutableStateOf(cached) }
    LaunchedEffect(ref) {
        val fresh = runCatching { catalog.posterImdb(ref) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
        if (fresh != null) rating = fresh
    }
    val visible = remember(ref) { MutableTransitionState(cached != null) }
    visible.targetState = rating != null
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(Tokens.Motion.stateMs)), exit = fadeOut()) {
        rating?.let { ImdbPill(it, onPoster = true) }
    }
}

/** The title page's ratings, or null while loading / unknown. Never blocks the page. */
@Composable
fun rememberTitleRatings(d: TitleDetails): Load<TitleRatings?> {
    val catalog = rememberCatalog()
    val state = produceState<Load<TitleRatings?>>(Load.Loading, d.summary.ref, d.imdbId) {
        value = Load.Ready(runCatching { catalog.titleRatings(d) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null })
    }
    return state.value
}

/**
 * IMDb · Rotten Tomatoes · Metascore, only the ones that exist. Before they arrive the row keeps its
 * height (so the hero doesn't jump); when none exist it shows TMDB's own score, labelled as TMDB's.
 */
@Composable
fun RatingsRow(load: Load<TitleRatings?>, tmdbRating: Double?, modifier: Modifier = Modifier) {
    val r = (load as? Load.Ready)?.value
    val height = Modifier.heightIn(min = 28.dp)
    if (load is Load.Loading) {
        Spacer(modifier.then(height))
        return
    }
    if (r == null || r.isEmpty) {
        if (tmdbRating != null) {
            Row(modifier.then(height), verticalAlignment = Alignment.CenterVertically) {
                Text("★ ${RatingRules.imdbLabel(tmdbRating)}", style = Tokens.Type.label, color = Tokens.Colors.accentBright)
                Text("  TMDB", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
            }
        }
        return
    }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(Tokens.Motion.stateMs))) {
        Row(height, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
            r.imdb?.let { ImdbPill(it) }
            r.rottenTomatoes?.let { TomatoScore(it) }
            r.metascore?.let { MetascoreBadge(it) }
        }
    }
}

/** Rotten Tomatoes: the red tomato (Fresh) or the green splat (Rotten), then "86%". */
@Composable
fun TomatoScore(percent: Int, modifier: Modifier = Modifier) {
    val fresh = RatingRules.tomato(percent) == Tomato.FRESH
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Rotten Tomatoes $percent%, ${if (fresh) "Fresh" else "Rotten"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        if (fresh) FreshTomato(20.dp) else RottenSplat(20.dp)
        Text("$percent%", style = Tokens.Type.label.copy(fontWeight = FontWeight.Bold), color = Tokens.Colors.text, maxLines = 1)
    }
}

/** Metacritic: the score on its colour square, then "Metascore". */
@Composable
fun MetascoreBadge(score: Int, modifier: Modifier = Modifier) {
    val bg = when (RatingRules.metaBand(score)) {
        MetaBand.FAVORABLE -> BrandColors.metaGreen
        MetaBand.MIXED -> BrandColors.metaYellow
        MetaBand.UNFAVORABLE -> BrandColors.metaRed
    }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Metascore $score" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s - Tokens.Space.xxs),
    ) {
        Box(
            Modifier.widthIn(min = 26.dp).heightIn(min = 24.dp).clip(RoundedCornerShape(Tokens.Radius.xs)).background(bg).padding(horizontal = Tokens.Space.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(score.toString(), style = Tokens.Type.label.copy(fontWeight = FontWeight.Bold), color = BrandColors.metaText, maxLines = 1)
        }
        Text("Metascore", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
    }
}

/** A red tomato with a green star of a leaf on top (Rotten Tomatoes' Fresh mark), drawn, no artwork. */
@Composable
private fun FreshTomato(size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(BrandColors.tomatoRed, radius = s * 0.42f, center = Offset(s * 0.5f, s * 0.56f))
        // Soft highlight, so it reads as round rather than a red dot.
        drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.28f), radius = s * 0.09f, center = Offset(s * 0.33f, s * 0.44f))
        val leaf = Path()
        val c = Offset(s * 0.5f, s * 0.2f)
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val r = if (i % 2 == 0) s * 0.22f else s * 0.08f
            val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            if (i == 0) leaf.moveTo(p.x, p.y) else leaf.lineTo(p.x, p.y)
        }
        leaf.close()
        drawPath(leaf, BrandColors.tomatoLeaf)
    }
}

/** A green splat (Rotten Tomatoes' Rotten mark): a blob with droplets around it. */
@Composable
private fun RottenSplat(size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s * 0.5f, s * 0.52f)
        drawCircle(BrandColors.splatGreen, radius = s * 0.3f, center = c)
        // Lumpy edge and flung drops at uneven angles / sizes.
        listOf(Triple(10.0, 0.26f, 0.13f), Triple(75.0, 0.3f, 0.11f), Triple(140.0, 0.25f, 0.14f), Triple(200.0, 0.3f, 0.1f), Triple(265.0, 0.27f, 0.13f), Triple(320.0, 0.3f, 0.1f))
            .forEach { (deg, dist, r) ->
                val a = Math.toRadians(deg)
                drawCircle(BrandColors.splatGreen, radius = s * r, center = Offset(c.x + (s * dist * cos(a)).toFloat(), c.y + (s * dist * sin(a)).toFloat()))
            }
        listOf(Triple(40.0, 0.46f, 0.05f), Triple(170.0, 0.47f, 0.045f), Triple(290.0, 0.46f, 0.05f))
            .forEach { (deg, dist, r) ->
                val a = Math.toRadians(deg)
                drawCircle(BrandColors.splatGreen, radius = s * r, center = Offset(c.x + (s * dist * cos(a)).toFloat(), c.y + (s * dist * sin(a)).toFloat()))
            }
    }
}

/** The quiet line above a title's name: "FILM · 2024 · 2h 46m · UA · SCIENCE FICTION, ADVENTURE"; breaks only between parts. */
@Composable
fun Eyebrow(d: TitleDetails, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val parts = com.kirtanya.reel.core.tmdb.TitleEyebrow.parts(d)
    Text(
        parts.joinToString("  ·  ") { it.replace(' ', ' ') },
        style = Tokens.Type.caption.copy(letterSpacing = 1.2.sp),
        color = Tokens.Colors.textSecondary,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
