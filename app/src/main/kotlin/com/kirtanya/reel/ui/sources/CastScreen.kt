package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.launcher.CastChoice
import com.kirtanya.reel.core.launcher.CastChoice.Decision
import com.kirtanya.reel.core.launcher.CastRequest
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LocalRenderData
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle
import kotlinx.coroutines.delay

/**
 * "Play on TV" from One Launcher on the phone (onestream://cast/play): runs the app's own source
 * search for the film / episode, picks the hinted source (or the Recommended one this device can
 * stream) and opens the player, as if it had been chosen on the Sources page. [onStarted] runs once
 * the player is opening (the caller swaps this screen for the title page, so Back from the player
 * lands there). Back / Cancel stop the search.
 */
@Composable
fun CastScreen(request: CastRequest, isTv: Boolean, onBack: () -> Unit, onStarted: () -> Unit, onOpenSources: () -> Unit) {
    val (load, retry) = rememberTitle(request.ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (load) {
            is Load.Ready -> CastSearchBody(load.value, request, isTv, onBack, onStarted, onOpenSources)
            is Load.Loading -> CastStarting(null, request, null, isTv, onBack)
            is Load.Failed -> {
                val focus = remember { FocusRequester() }
                if (isTv) RequestFocusOnce(focus)
                EmptyState(
                    Icons.Rounded.Refresh, "Couldn't load this title", load.message,
                    Modifier.align(Alignment.Center).then(if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier),
                ) { ReelButton("Try again", { retry() }, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) }
            }
        }
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun CastSearchBody(d: TitleDetails, request: CastRequest, isTv: Boolean, onBack: () -> Unit, onStarted: () -> Unit, onOpenSources: () -> Unit) {
    val context = LocalContext.current
    val req = remember(d, request) { requestFor(d, request.season, request.episode) }
    val prefs = remember(isTv) { rankPrefs(context, isTv) }
    val fixed = LocalRenderData.current
    val search: SourceSearch? = if (fixed?.sourceSearch != null) fixed.sourceSearch.invoke() else rememberSourceSearch(req, isTv).first
    // Slow addons can take 30 s each: after the launcher's own wait, choose from what has arrived.
    var timedOut by remember(req.key) { mutableStateOf(false) }
    if (fixed == null) LaunchedEffect(req.key) {
        delay(LauncherPage.SOURCES_TIMEOUT_MS)
        timedOut = true
    }
    val decision = remember(search, timedOut) {
        if (search == null && !timedOut) Decision.Wait
        else CastChoice.decide(search?.sources.orEmpty(), search?.done == true || timedOut, request) { SourceRanker.fits(it, prefs) }
    }
    val (episodes, _) = rememberSeason(d.summary.ref.id, request.season)
    val ep = (episodes as? Load.Ready)?.value?.firstOrNull { it.number == request.episode }
    var started by remember(req.key) { mutableStateOf(false) }
    LaunchedEffect(decision) {
        val take = decision as? Decision.Take ?: return@LaunchedEffect
        if (started) return@LaunchedEffect
        started = true
        val s = request.season
        val e = request.episode
        val epNumber = if (s != null && e != null) EpisodeNumber(s, e) else null
        // Exactly what choosing it on the Sources page does (the player resumes from history itself),
        // or from the phone's position when the phone handed playback over ("Send to TV").
        play(context, playbackFor(d, epNumber, ep?.name, ep?.stillPath, take.source), request.startMs)
        onStarted()
    }
    if (decision == Decision.Nothing) CastNothing(d, request, isTv, onOpenSources)
    else CastStarting(d, request, search, isTv, onBack)
}

/** "Starting on your TV…": the title (and poster once TMDB answers), a spinner, Cancel. */
@Composable
internal fun CastStarting(d: TitleDetails?, request: CastRequest, search: SourceSearch?, isTv: Boolean, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(focus)
    val status = when {
        search == null || search.total == 0 -> "Finding a source…"
        else -> "Finding a source… ${search.total - search.pending.size} of ${search.total} sources answered"
    }
    val subtitle = if (request.season != null && request.episode != null) "Season ${request.season} · Episode ${request.episode}" else d?.summary?.year?.toString()
    val posterWidth = if (isTv) 168.dp else 112.dp
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    Box(Modifier.fillMaxSize().padding(horizontal = side), contentAlignment = Alignment.Center) {
        val poster: @Composable () -> Unit = {
            val shape = RoundedCornerShape(Tokens.Radius.m)
            val frame = Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(shape).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
            if (d?.summary?.posterPath != null) RemoteImage(TmdbImages.poster(d.summary.posterPath, "w342"), frame)
            else Box(frame.background(Tokens.Colors.surface2))
        }
        val textAlign = if (isTv) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center
        val text: @Composable (Alignment.Horizontal) -> Unit = { align ->
            Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text(if (isTv) "Starting on your TV…" else "Starting…", style = Tokens.Type.label, color = Tokens.Colors.accent)
                Text(d?.summary?.title ?: " ", style = Tokens.Type.display, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
                if (subtitle != null) Text(subtitle, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                Spacer(Modifier.height(Tokens.Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Tokens.Colors.accent, strokeWidth = 2.dp)
                    Text(status, style = Tokens.Type.body, color = Tokens.Colors.textSecondary, textAlign = textAlign)
                }
                Spacer(Modifier.height(Tokens.Space.s))
                ReelButton("Cancel", onCancel, Modifier.focusRequester(focus), kind = ButtonKind.Secondary, icon = Icons.Rounded.Close)
            }
        }
        if (isTv) {
            Row(Modifier.widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xxl)) {
                poster()
                Box(Modifier.weight(1f)) { text(Alignment.Start) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xl)) {
                poster()
                text(Alignment.CenterHorizontally)
            }
        }
    }
}

/** Nothing this device can play came back: say so, offer the Sources page (filters, services, retry). */
@Composable
private fun CastNothing(d: TitleDetails, request: CastRequest, isTv: Boolean, onOpenSources: () -> Unit) {
    val focus = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(focus)
    val label = CastChoice.label(d.summary.title, request.season, request.episode)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            Icons.Rounded.SearchOff, "Couldn't find a source",
            "None of your sources has $label ready to play here.",
            if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier,
        ) { ReelButton("Open Sources", onOpenSources, Modifier.focusRequester(focus), kind = ButtonKind.Primary, icon = Icons.AutoMirrored.Rounded.List) }
    }
}
