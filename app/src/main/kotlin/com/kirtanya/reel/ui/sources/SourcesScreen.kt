package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.SourceSwap
import com.kirtanya.reel.core.library.SwapKind
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.FilterChip
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceBrowser
import com.kirtanya.reel.core.sources.SourceFilters
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.sources.SourceSort
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.LoadView
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.common.rememberAmbientColor
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.heroBackdrops
import com.kirtanya.reel.ui.title.rememberSeason
import com.kirtanya.reel.ui.title.rememberTitle

/**
 * Pick where to play or download a film or episode. The ranker's pick sits on top as a
 * Recommended card with one button; under it, chips filter and sort the rest (which fill in as
 * addons and plugins answer). [download] opens it for downloading; [replacing] (an info-hash) for
 * picking a new source for that download ("Change source" on Downloads). Phone (touch) and TV (remote).
 */
@Composable
fun SourcesScreen(
    ref: TitleRef,
    season: Int?,
    episode: Int?,
    isTv: Boolean,
    onBack: () -> Unit,
    download: Boolean = false,
    replacing: String? = null,
) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        LoadView(load, retry) { d -> SourcesBody(d, season, episode, isTv, download || replacing != null, replacing, onBack) }
        if (!isTv) com.kirtanya.reel.ui.common.TopFade()
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun SourcesBody(d: TitleDetails, season: Int?, episode: Int?, isTv: Boolean, startDownload: Boolean, replacing: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val req = remember(d, season, episode) { requestFor(d, season, episode) }
    val prefs = remember(isTv) { rankPrefs(context, isTv) }
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val (search, rescan) = if (fixed?.sourceSearch != null) fixed.sourceSearch.invoke() to {} else rememberSourceSearch(req, isTv)
    val (episodes, _) = rememberSeason(d.summary.ref.id, season)
    val ep = (episodes as? Load.Ready)?.value?.firstOrNull { it.number == episode }
    val epNumber = if (season != null && episode != null) EpisodeNumber(season, episode) else null
    fun playbackOf(s: Source) = playbackFor(d, epNumber, ep?.name, ep?.stillPath, s)
    val label = d.summary.title + if (epNumber != null) " · ${epNumber.label}" else ""
    val origin = DownloadOrigin(d.summary.ref.type.path, d.summary.ref.id, season, episode, label)

    var downloading by remember { mutableStateOf(startDownload) }
    val action = when {
        replacing != null -> SourceAction.REPLACE
        downloading -> SourceAction.DOWNLOAD
        else -> SourceAction.PLAY
    }
    var filters by remember(req.key) { mutableStateOf(fixed?.sourceFilters ?: SourceFilters()) }
    var confirm by remember { mutableStateOf<Source?>(null) }

    val ranked = search?.sources.orEmpty()
    val best = remember(ranked) { SourceBrowser.recommended(ranked) }
    val bestView = remember(best) { best?.let(SourceBrowser::view) }
    val rows = remember(ranked, best) { ranked.filter { it.key != best?.key }.map(SourceBrowser::view) }
    val facets = remember(rows) { SourceBrowser.facets(rows) }
    LaunchedEffect(facets) { filters = SourceBrowser.prune(filters, facets) }
    val shown = remember(rows, filters) { SourceBrowser.apply(rows, filters) }
    if (fixed?.confirmReplace == true && replacing != null) LaunchedEffect(best) { confirm = best }

    val glow by rememberAmbientColor(TmdbImages.poster(d.summary.posterPath, "w185"))
    val mainFocus = remember { FocusRequester() }
    if (isTv && best != null) RequestFocusOnce(mainFocus, key = best.key)
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val maxWidth = if (isTv) 1000.dp else 720.dp
    val art = listOfNotNull(ep?.stillPath?.let { TmdbImages.still(it, "original") }).ifEmpty { heroBackdrops(d) }
    val bottom = if (isTv) Tokens.Space.tvSafeV else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Tokens.Space.xxl

    fun choose(s: Source) {
        when {
            action == SourceAction.PLAY || s.kind == com.kirtanya.reel.core.sources.SourceKind.EXTERNAL -> play(context, playbackOf(s))
            replacing != null -> confirm = s
            else -> download(context, s, label, origin)
        }
    }

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
            item(key = "header") { Header(d, epNumber, ep?.name, art, action, isTv, side, maxWidth) }
            item(key = "mode") {
                Column(Modifier.padding(horizontal = side).widthIn(max = maxWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                    if (replacing == null) {
                        // Play or download: one page, the action on every card follows this switch.
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            ReelButton("Play", { downloading = false }, kind = if (!downloading) ButtonKind.Primary else ButtonKind.Ghost, icon = Icons.Rounded.PlayArrow)
                            ReelButton("Download", { downloading = true }, kind = if (downloading) ButtonKind.Primary else ButtonKind.Ghost, icon = Icons.Rounded.Download)
                        }
                    }
                    Status(search)
                }
            }
            item(key = "best") {
                Box(Modifier.padding(horizontal = side)) {
                    when {
                        bestView != null -> RecommendedCard(
                            bestView, SourceRanker.fits(bestView.source, prefs), action, glow, isTv, mainFocus,
                            onClick = { choose(bestView.source) }, modifier = Modifier.widthIn(max = maxWidth),
                        )
                        search?.done == true -> NothingFound(search, rescan, Modifier.widthIn(max = maxWidth))
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item(key = "chips") { Chips(facets, filters, { filters = it }, side) }
                item(key = "count") {
                    Text(
                        when {
                            filters.active -> "Showing ${shown.size} of ${rows.size} other sources"
                            else -> com.kirtanya.reel.ui.plural(rows.size, "other source")
                        },
                        style = Tokens.Type.label, color = Tokens.Colors.textSecondary,
                        modifier = Modifier.padding(horizontal = side),
                    )
                }
            }
            items(shown, key = { it.source.key }) { v ->
                SourceRow(
                    v, fits = SourceRanker.fits(v.source, prefs), action = action, onClick = { choose(v.source) },
                    modifier = Modifier.padding(horizontal = side).widthIn(max = maxWidth),
                )
            }
            if (rows.isNotEmpty() && shown.isEmpty()) {
                item(key = "filtered-out") {
                    EmptyState(
                        Icons.Rounded.FilterAltOff, "No sources match these filters",
                        if (best != null) "The recommended source above is still there. Clear the filters to see the rest." else "Clear the filters to see every source.",
                        Modifier.padding(horizontal = side).widthIn(max = maxWidth),
                        action = { ReelButton("Clear filters", { filters = filters.cleared() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.FilterAltOff) },
                    )
                }
            }
            if (search?.done == true && ranked.isNotEmpty()) {
                item(key = "again") { Box(Modifier.padding(horizontal = side)) { ReelButton("Search again", rescan, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) } }
            }
        }
    }

    confirm?.let { s -> ReplaceDialog(replacing!!, s, label, origin, onDismiss = { confirm = null }, onDone = { confirm = null; onDone() }) }
}

/** What's being picked for: "Choose a source to play", then the title (logo) and the episode. */
@Composable
private fun Header(
    d: TitleDetails,
    epNumber: EpisodeNumber?,
    epName: String?,
    art: List<String>,
    action: SourceAction,
    isTv: Boolean,
    side: androidx.compose.ui.unit.Dp,
    maxWidth: androidx.compose.ui.unit.Dp,
) {
    val heading = @Composable {
        Column(Modifier.widthIn(max = maxWidth), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Text(
                when (action) {
                    SourceAction.PLAY -> "Choose a source to play"
                    SourceAction.DOWNLOAD -> "Choose a source to download"
                    SourceAction.REPLACE -> "Choose a new source for this download"
                },
                // On the picture: full text colour, like the title and episode under it.
                style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TitleLogo(
                d.logoPath, d.summary.title, if (isTv) Tokens.Type.hero else Tokens.Type.display,
                if (isTv) Tokens.Cinema.logoMaxWidthTv else Tokens.Cinema.logoMaxWidthPhone,
                if (isTv) Tokens.Cinema.logoMaxHeightTv * 0.7f else Tokens.Cinema.logoMaxHeightPhone * 0.7f,
                textMaxLines = 2,
            )
            if (epNumber != null) {
                Text(
                    listOfNotNull(epNumber.label, epName).joinToString(" · "),
                    style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = if (isTv) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (isTv) {
        Box(Modifier.padding(start = side, end = side, top = Tokens.Space.xxl)) { heading() }
    } else {
        Box(Modifier.fillMaxWidth().height(Tokens.Cinema.sourcesHeader)) {
            HeroImage(art, Modifier.fillMaxSize(), kenBurns = false)
            HeroScrims(top = true)
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = side)) { heading() }
        }
    }
}

/** A slim progress line while providers answer: how many have, how many found, who we're waiting for. */
@Composable
private fun Status(search: SourceSearch?) {
    val status = SourceBrowser.status(search)
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Text(status.headline, style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        status.progress?.let { GradientProgress(it, height = 3.dp) }
        status.detail?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun NothingFound(search: SourceSearch, rescan: () -> Unit, modifier: Modifier) {
    val allFailed = search.total > 0 && search.failed.size == search.total
    EmptyState(
        if (allFailed) Icons.Rounded.CloudOff else Icons.Rounded.Hub,
        when {
            search.total == 0 -> "No sources for this yet"
            allFailed -> "Your sources didn't answer"
            search.sources.isNotEmpty() -> "Only services have this"
            else -> "Nothing found"
        },
        when {
            search.total == 0 -> "Add addons or plugins in Settings to find places to play this."
            allFailed -> "Check your connection and search again."
            search.sources.isNotEmpty() -> "It's on a streaming service: open it from the list below."
            search.total == 1 -> "Your only source didn't have this. Try again later, or add more sources in Settings."
            else -> "None of your ${search.total} sources had this. Try again later, or add more sources in Settings."
        },
        modifier,
        action = { ReelButton("Search again", rescan, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh) },
    )
}

/**
 * Two rows of chips that scroll sideways: sort, quality and type; then provider and language.
 * Within a group several chips can be on (4K or 1080p); the counts are what each chip shows alone.
 */
@Composable
private fun Chips(facets: com.kirtanya.reel.core.sources.SourceFacets, filters: SourceFilters, onChange: (SourceFilters) -> Unit, side: androidx.compose.ui.unit.Dp) {
    @Composable
    fun <T> chip(c: FilterChip<T>, on: Boolean, toggle: () -> Unit) =
        ReelButton("${c.label} · ${c.count}", toggle, kind = if (on) ButtonKind.Primary else ButtonKind.Ghost)

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            item(key = "sort") {
                // One chip cycles the order, so the row stays short on the phone and one D-pad stop on TV.
                val next = SourceSort.entries[(filters.sort.ordinal + 1) % SourceSort.entries.size]
                ReelButton(filters.sort.label, { onChange(filters.copy(sort = next)) }, kind = ButtonKind.Secondary, icon = Icons.AutoMirrored.Rounded.Sort)
            }
            items(facets.qualities, key = { "q:${it.value}" }) { c ->
                chip(c, c.value in filters.qualities) { onChange(filters.copy(qualities = SourceBrowser.toggle(filters.qualities, c.value))) }
            }
            items(facets.types, key = { "t:${it.value}" }) { c ->
                chip(c, c.value in filters.types) { onChange(filters.copy(types = SourceBrowser.toggle(filters.types, c.value))) }
            }
            if (filters.active) item(key = "clear") { ReelButton("Clear", { onChange(filters.cleared()) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.FilterAltOff) }
        }
        if (facets.providers.isNotEmpty() || facets.languages.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                items(facets.providers, key = { "p:${it.value}" }) { c ->
                    chip(c, c.value in filters.providers) { onChange(filters.copy(providers = SourceBrowser.toggle(filters.providers, c.value))) }
                }
                items(facets.languages, key = { "l:${it.value}" }) { c ->
                    chip(c, c.value in filters.languages) { onChange(filters.copy(languages = SourceBrowser.toggle(filters.languages, c.value))) }
                }
            }
        }
    }
}

/** "Replace this download?" with what's lost; replaces only after the new one has started. */
@Composable
private fun ReplaceDialog(oldHash: String, next: Source, label: String, origin: DownloadOrigin, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val engine = ReelApp.get(context).engine
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val old = (fixed?.torrents ?: engine.torrents.value).firstOrNull { it.infoHash.equals(oldHash, ignoreCase = true) }
    val kind = SourceSwap.kind(oldHash, old?.fileIndex, next)
    val finished = old?.phase == TorrentPhase.FINISHED || old?.phase == TorrentPhase.SEEDING
    val text = SourceSwap.confirm(kind, label, finished, old?.let { formatBytes(it.doneBytes) })
    if (text == null) {
        // Same file, or a service link: nothing to replace.
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(context, if (kind == SwapKind.SAME_FILE) "That's the source it's already using" else "This source can't be downloaded", android.widget.Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = { Text(text.title, style = Tokens.Type.heading, color = Tokens.Colors.text) },
        text = { Text(text.body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
        confirmButton = {
            ReelButton(text.confirm, {
                download(context, next, label, origin, replacing = if (kind == SwapKind.SAME_TORRENT) null else oldHash)
                onDone()
            }, kind = ButtonKind.Primary, icon = Icons.Rounded.Download)
        },
        dismissButton = { ReelButton("Keep current", onDismiss, kind = ButtonKind.Ghost) },
    )
}
