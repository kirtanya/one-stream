package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.Backdrop
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle

/** Quick filters over the ranked list. */
private enum class SourceFilter(val label: String, val test: (Source) -> Boolean) {
    All("All", { true }),
    UHD("4K", { (it.quality.resolution ?: 0) >= 2160 }),
    FHD("1080p", { it.quality.resolution == 1080 }),
    HD("720p", { it.quality.resolution == 720 }),
    Direct("Direct", { it.kind == SourceKind.DIRECT }),
    P2P("P2P", { it.kind == SourceKind.TORRENT }),
}

/**
 * Every playable option for a film or episode, best first, filling in as addons and plugins
 * answer. The best one gets a glowing card of its own. Same screen on phone (touch) and TV (remote).
 */
@Composable
fun SourcesScreen(ref: TitleRef, season: Int?, episode: Int?, isTv: Boolean, onBack: () -> Unit) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        LoadView(load, retry) { d -> SourcesBody(d, season, episode, isTv) }
        if (!isTv) com.kirtanya.reel.ui.common.TopFade()
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.l))
    }
}

@Composable
private fun SourcesBody(d: TitleDetails, season: Int?, episode: Int?, isTv: Boolean) {
    val context = LocalContext.current
    val req = remember(d, season, episode) { requestFor(d, season, episode) }
    val prefs = remember(isTv) { rankPrefs(context, isTv) }
    val (search, rescan) = rememberSourceSearch(req, isTv)
    val (episodes, _) = rememberSeason(d.summary.ref.id, season)
    val ep = (episodes as? Load.Ready)?.value?.firstOrNull { it.number == episode }
    val epNumber = if (season != null && episode != null) EpisodeNumber(season, episode) else null
    fun playbackOf(s: Source) = playbackFor(d, epNumber, ep?.name, ep?.stillPath, s)
    val label = d.summary.title + if (season != null) " · S$season E$episode" else ""
    var filter by remember { mutableStateOf(SourceFilter.All) }
    val best = bestOf(search)
    val rest = search?.sources.orEmpty().filter { it.key != best?.key && filter.test(it) }
    val glow by rememberAmbientColor(TmdbImages.poster(d.summary.posterPath, "w185"))
    val playFocus = remember { FocusRequester() }
    if (isTv && best != null) RequestFocusOnce(playFocus, key = best.key)
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val maxWidth = if (isTv) 1000.dp else 720.dp

    Box(Modifier.fillMaxSize()) {
        Backdrop(
            ep?.stillPath?.let { TmdbImages.still(it, "original") } ?: TmdbImages.backdrop(d.summary.backdropPath),
            Modifier.fillMaxWidth().height(if (isTv) 600.dp else 320.dp),
            fromLeft = isTv,
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.xxxl else 200.dp, bottom = Tokens.Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            item(key = "header") {
                Column(Modifier.widthIn(max = maxWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    Text(d.summary.title, style = if (isTv) Tokens.Type.hero else Tokens.Type.display, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (epNumber != null) {
                        Text(listOfNotNull(epNumber.label, ep?.name).joinToString(" · "), style = Tokens.Type.heading, color = Tokens.Colors.textSecondary)
                    }
                    Status(search)
                }
            }
            item(key = "best") {
                when {
                    best != null -> BestCard(best, SourceRanker.fits(best, prefs), glow, Modifier.widthIn(max = maxWidth), playFocus,
                        onPlay = { play(context, playbackOf(best)) }, onDownload = { download(context, best, label) })
                    search?.done == true -> EmptyState(
                        Icons.Rounded.Hub,
                        if (search.total == 0) "No sources for this yet" else "Nothing found",
                        if (search.total == 0) "Add addons or plugins in Settings to find places to play this."
                        else "None of your ${search.total} sources had this. Try again later, or add more sources in Settings.",
                        action = { ReelButton("Search again", rescan, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) },
                    )
                }
            }
            if ((search?.sources?.size ?: 0) > 1) {
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        items(SourceFilter.entries.filter { f -> f == SourceFilter.All || search!!.sources.any(f.test) }) { f ->
                            ReelButton(
                                "${f.label} · ${search!!.sources.count { it.key != best?.key && f.test(it) }}",
                                { filter = f },
                                kind = if (filter == f) ButtonKind.Primary else ButtonKind.Ghost,
                            )
                        }
                    }
                }
            }
            items(rest, key = { it.key }) { s ->
                SourceRow(
                    s,
                    fits = SourceRanker.fits(s, prefs),
                    onPlay = { play(context, playbackOf(s)) },
                    onDownload = { download(context, s, label) },
                    modifier = Modifier.widthIn(max = maxWidth),
                )
            }
            if (search?.done == true && search.sources.isNotEmpty()) {
                item(key = "again") { ReelButton("Search again", rescan, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) }
            }
        }
    }
}

@Composable
private fun Status(search: SourceSearch?) {
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        val answered = search?.let { it.total - it.pending.size }
        val text = when {
            search == null -> "Getting your sources ready…"
            search.total == 0 -> "None of your sources handle this yet."
            !search.done -> "Searching · $answered of ${search.total} sources · ${search.sources.size} found"
            else -> "${search.sources.size} found from ${search.total} sources"
        }
        Text(text, style = Tokens.Type.label, color = Tokens.Colors.textSecondary)
        if (search != null && search.total > 0 && !search.done) GradientProgress((answered ?: 0).toFloat() / search.total, height = 3.dp)
        if (search != null && search.failed.isNotEmpty()) {
            Text("Didn't answer: " + search.failed.entries.joinToString { "${it.key} (${it.value.take(48)})" }, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 2)
        }
    }
}

/** The ranked winner: glowing card with the details that matter and one big Play. */
@Composable
private fun BestCard(
    source: Source,
    fits: Boolean,
    glow: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(28.dp, shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(Tokens.Colors.surface1)
            .border(Tokens.Size.ring, Tokens.Gradients.ring, shape)
            .padding(Tokens.Space.l),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Pill("Best match", icon = Icons.Rounded.AutoAwesome, accent = true)
            Pill(source.quality.label)
            Pill(if (source.kind == SourceKind.TORRENT) "P2P" else "Direct")
        }
        Text(source.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Pill(source.provider)
            source.sizeBytes?.let { Pill(formatBytes(it)) }
            source.seeders?.let { Pill("$it seeders") }
            if (source.subtitles.isNotEmpty()) Pill("${source.subtitles.size} subtitles")
        }
        if (!fits) Text("Too big to stream on this device — pick a smaller one below.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
            ReelButton("Play", onPlay, Modifier.weight(1f).focusRequester(playFocus), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
            if (source.kind != SourceKind.EXTERNAL) GlassIconButton(Icons.Rounded.Download, "Download", onDownload)
        }
    }
}
