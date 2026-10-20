package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.title.heroBackdrops
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
import androidx.compose.runtime.LaunchedEffect
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
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun SourcesBody(d: TitleDetails, season: Int?, episode: Int?, isTv: Boolean) {
    val context = LocalContext.current
    val req = remember(d, season, episode) { requestFor(d, season, episode) }
    val prefs = remember(isTv) { rankPrefs(context, isTv) }
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current?.sourceSearch
    val (search, rescan) = if (fixed != null) fixed() to {} else rememberSourceSearch(req, isTv)
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
    val art = listOfNotNull(ep?.stillPath?.let { TmdbImages.still(it, "original") }).ifEmpty { heroBackdrops(d) }
    val bottom = if (isTv) Tokens.Space.tvSafeV else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Tokens.Space.xxl

    Box(Modifier.fillMaxSize()) {
        // TV: the picture fills the room behind the list; phone: it's the top of the page.
        if (isTv) {
            Box(Modifier.fillMaxSize()) {
                HeroImage(art, Modifier.fillMaxSize(), kenBurns = false)
                HeroScrims(top = false, start = true)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            item(key = "header") {
                val heading = @Composable {
                    Column(Modifier.widthIn(max = maxWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        TitleLogo(
                            d.logoPath, d.summary.title, if (isTv) Tokens.Type.hero else Tokens.Type.display,
                            if (isTv) Tokens.Cinema.logoMaxWidthTv else Tokens.Cinema.logoMaxWidthPhone,
                            if (isTv) Tokens.Cinema.logoMaxHeightTv * 0.8f else Tokens.Cinema.logoMaxHeightPhone * 0.8f,
                            textMaxLines = 2,
                        )
                        if (epNumber != null) {
                            Text(
                                listOfNotNull(epNumber.label, ep?.name).joinToString(" · "),
                                style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (isTv) {
                    Box(Modifier.padding(start = side, end = side, top = Tokens.Space.xxxl)) { heading() }
                } else {
                    Box(Modifier.fillMaxWidth().height(Tokens.Cinema.sourcesHeader)) {
                        HeroImage(art, Modifier.fillMaxSize(), kenBurns = false)
                        HeroScrims(top = true)
                        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = side)) { heading() }
                    }
                }
            }
            item(key = "status") { Status(search, Modifier.padding(horizontal = side)) }
            item(key = "best") {
                Box(Modifier.padding(horizontal = side)) {
                    when {
                        best != null -> BestCard(best, SourceRanker.fits(best, prefs), glow, Modifier.widthIn(max = maxWidth), playFocus, isTv,
                            onPlay = { play(context, playbackOf(best)) }, onDownload = { download(context, best, label) })
                        search?.done == true -> EmptyState(
                            Icons.Rounded.Hub,
                            if (search.total == 0) "No sources for this yet" else "Nothing found",
                            if (search.total == 0) "Add addons or plugins in Settings to find places to play this."
                            else if (search.total == 1) "Your only source didn't have this. Try again later, or add more sources in Settings."
                            else "None of your ${search.total} sources had this. Try again later, or add more sources in Settings.",
                            Modifier.widthIn(max = maxWidth),
                            action = { ReelButton("Search again", rescan, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) },
                        )
                    }
                }
            }
            if ((search?.sources?.size ?: 0) > 1) {
                item(key = "filters") {
                    // Chips count what the list below shows (everything but the Best match card).
                    val others = search!!.sources.filter { it.key != best?.key }
                    val chips = SourceFilter.entries.filter { f -> f == SourceFilter.All || others.any(f.test) }
                    LaunchedEffect(chips) { if (filter !in chips) filter = SourceFilter.All }
                    LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        items(chips) { f ->
                            ReelButton(
                                "${f.label} · ${others.count(f.test)}",
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
                    modifier = Modifier.padding(horizontal = side).widthIn(max = maxWidth),
                )
            }
            if (search?.done == true && search.sources.isNotEmpty()) {
                item(key = "again") { Box(Modifier.padding(horizontal = side)) { ReelButton("Search again", rescan, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) } }
            }
        }
    }
}

@Composable
private fun Status(search: SourceSearch?, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        val answered = search?.let { it.total - it.pending.size }
        val text = when {
            search == null -> "Getting your sources ready…"
            search.total == 0 -> "None of your sources handle this yet."
            !search.done -> "Searching · $answered of ${search.total} sources · ${search.sources.size} found"
            else -> "${search.sources.size} found from ${search.total} ${if (search.total == 1) "source" else "sources"}"
        }
        Text(text, style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (search == null || (search.total > 0 && !search.done)) GradientProgress(if (search == null) 0f else (answered ?: 0).toFloat() / search.total, height = 3.dp)
        if (search != null && search.failed.isNotEmpty()) {
            // Names only: the reasons are long and technical (the full list is in the addon's settings).
            Text(
                (if (search.failed.size == 1) "Didn't answer: " else "${search.failed.size} didn't answer: ") + search.failed.keys.joinToString(", "),
                style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
    isTv: Boolean,
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Pill("Best match", icon = Icons.Rounded.AutoAwesome, accent = true)
            Pill(source.quality.label)
            Pill(if (source.kind == SourceKind.TORRENT) "P2P" else "Direct")
        }
        Text(releaseName(source.title), style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Pill(source.provider)
            source.sizeBytes?.let { Pill(formatBytes(it)) }
            source.seeders?.let { Pill(com.kirtanya.reel.ui.plural(it, "seeder")) }
            if (source.subtitles.isNotEmpty()) Pill(com.kirtanya.reel.ui.plural(source.subtitles.size, "subtitle"))
        }
        if (!fits) Text("Too big to stream on this device — pick a smaller one below.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
            // TV: a wide button would grow over its neighbour when focused (it lifts by 6 %).
            ReelButton("Play", onPlay, (if (isTv) Modifier.widthIn(min = 200.dp) else Modifier.weight(1f)).focusRequester(playFocus), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
            if (source.kind != SourceKind.EXTERNAL) GlassIconButton(Icons.Rounded.Download, "Download", onDownload)
        }
    }
}
