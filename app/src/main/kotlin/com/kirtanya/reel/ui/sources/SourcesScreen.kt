package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.rememberTitle

/**
 * Every playable option for a film or episode, best first, filling in as addons and
 * plugins answer. Same screen on phone (touch) and TV (remote).
 */
@Composable
fun SourcesScreen(ref: TitleRef, season: Int?, episode: Int?, isTv: Boolean, onBack: () -> Unit) {
    val (load, retry) = rememberTitle(ref)
    LoadView(load, retry) { d -> SourcesBody(d, season, episode, isTv, onBack) }
}

@Composable
private fun SourcesBody(d: TitleDetails, season: Int?, episode: Int?, isTv: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val req = remember(d, season, episode) { requestFor(d, season, episode) }
    val prefs = remember(isTv) { rankPrefs(context, isTv) }
    val (search, rescan) = rememberSourceSearch(req, isTv)
    val (episodes, _) = com.kirtanya.reel.ui.title.rememberSeason(d.summary.ref.id, season)
    val ep = (episodes as? com.kirtanya.reel.ui.common.Load.Ready)?.value?.firstOrNull { it.number == episode }
    val epNumber = if (season != null && episode != null) EpisodeNumber(season, episode) else null
    fun playbackOf(s: com.kirtanya.reel.core.sources.Source) = playbackFor(d, epNumber, ep?.name, ep?.stillPath, s)
    val best = bestOf(search)
    val label = d.summary.title + if (season != null) " · S$season E$episode" else ""
    val playFocus = remember { FocusRequester() }
    if (isTv && best != null) RequestFocusOnce(playFocus, key = best.key)

    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    Box(Modifier.fillMaxSize().background(Tokens.Colors.bg)) {
        if (isTv) {
            RemoteImage(TmdbImages.backdrop(d.summary.backdropPath), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Tokens.Colors.scrim))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = side, vertical = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s), modifier = Modifier.widthIn(max = 900.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isTv) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Tokens.Colors.text) }
                        Text(label, style = if (isTv) Tokens.Type.display else Tokens.Type.title, color = Tokens.Colors.text, maxLines = 2)
                    }
                    Status(search?.let { it.total - it.pending.size }, search?.total, search?.done ?: false, search?.sources?.size ?: 0, search?.failed.orEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        ReelButton(
                            "Play best",
                            { best?.let { play(context, playbackOf(it)) } },
                            kind = ButtonKind.Primary,
                            icon = Icons.Rounded.PlayArrow,
                            enabled = best != null,
                            modifier = if (isTv) Modifier.focusRequester(playFocus) else Modifier,
                        )
                        ReelButton("Search again", rescan, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh, enabled = search?.done == true)
                    }
                }
            }
            itemsIndexed(search?.sources.orEmpty(), key = { _, s -> s.key }) { _, s ->
                SourceRow(
                    s,
                    fits = SourceRanker.fits(s, prefs),
                    onPlay = { play(context, playbackOf(s)) },
                    onDownload = { download(context, s, label) },
                    modifier = Modifier.widthIn(max = 1000.dp),
                )
            }
        }
    }
}

@Composable
private fun Status(answered: Int?, total: Int?, done: Boolean, found: Int, failed: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs), modifier = Modifier.widthIn(max = 600.dp)) {
        val text = when {
            total == null -> "Getting sources ready…"
            total == 0 -> "None of your sources handle this yet. Add addons or plugins in Settings."
            !done -> "Searching… $answered of $total sources answered · $found found"
            found == 0 -> "Nothing found. Try “Search again”, or add more sources in Settings."
            else -> "$found found from $total sources"
        }
        Text(text, style = Tokens.Type.body, color = Tokens.Colors.textMuted)
        if (total != null && total > 0 && !done) {
            LinearProgressIndicator(
                progress = { (answered ?: 0).toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(Tokens.Size.progressBar).clip(RoundedCornerShape(Tokens.Radius.pill)),
                color = Tokens.Colors.accent,
                trackColor = Tokens.Colors.surfaceHigh,
            )
        }
        if (failed.isNotEmpty()) {
            Text(
                "Didn't answer: " + failed.entries.joinToString { "${it.key} (${it.value.take(60)})" },
                style = Tokens.Type.caption,
                color = Tokens.Colors.textMuted,
                maxLines = 2,
            )
        }
    }
}
