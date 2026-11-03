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
import androidx.compose.runtime.collectAsState
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
import com.kirtanya.reel.core.library.DownloadTag
import com.kirtanya.reel.core.library.LastPlayed
import com.kirtanya.reel.core.library.SourceMarks
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.SourceSwap
import com.kirtanya.reel.core.library.SwapKind
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.FilterChip
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceView
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
import com.kirtanya.reel.ui.common.ErrorBox
import com.kirtanya.reel.ui.common.LoadingBox
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.TitleLogo
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.phone.SkeletonBlock
import com.kirtanya.reel.ui.phone.SkeletonHost
import com.kirtanya.reel.ui.phone.skeleton
import com.kirtanya.reel.ui.phone.skeletonHero
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
    /** The player's position when it asked for another source (its Change source): the pick plays from there. */
    resumeAtMs: Long? = null,
) {
    val (load, retry) = rememberTitle(ref)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (load) {
            // Phone: the page's own shape (header, switch, pick, rows) shimmering; TV keeps the plain spinner (nothing animates there).
            Load.Loading -> if (isTv) LoadingBox() else SourcesSkeleton()
            is Load.Failed -> ErrorBox(load.message, { retry() })
            is Load.Ready -> SourcesBody(load.value, season, episode, isTv, download || replacing != null, replacing, resumeAtMs, onBack)
        }
        if (!isTv) com.kirtanya.reel.ui.common.TopFade()
        if (!isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.statusBarsPadding().padding(Tokens.Space.s))
    }
}

@Composable
private fun SourcesBody(d: TitleDetails, season: Int?, episode: Int?, isTv: Boolean, startDownload: Boolean, replacing: String?, resumeAtMs: Long?, onDone: () -> Unit) {
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
    // Phone: Download → At home / On this phone (docs/HOME_DISK.md §17.3), with the network rule.
    val where = com.kirtanya.reel.ui.homedisk.rememberDownloadFlow(label, origin, isTv)

    var downloading by remember { mutableStateOf(startDownload) }
    val action = when {
        replacing != null -> SourceAction.REPLACE
        downloading -> SourceAction.DOWNLOAD
        else -> SourceAction.PLAY
    }
    var filters by remember(req.key) { mutableStateOf(fixed?.sourceFilters ?: SourceFilters()) }
    var confirm by remember { mutableStateOf<Source?>(null) }

    val app = ReelApp.get(context)
    // The user's own marks and history: favourite / starred sources, what was played last, what's downloaded.
    val marks by app.sourceMarks.marks.collectAsState()
    val historyEntries by app.history.entries.collectAsState()
    val liveTorrents by app.engine.torrents.collectAsState()
    val torrents = fixed?.torrents ?: liveTorrents
    val last = remember(historyEntries, req) {
        LastPlayed(
            same = historyEntries.firstOrNull { it.playback.key == req.key }?.playback?.source,
            latestOfShow = historyEntries.firstOrNull { it.playback.titleKey == SourceMarks.titleKey(req) }?.playback?.source,
        )
    }
    val downloads = remember(torrents) {
        torrents.filter { it.mode == com.kirtanya.reel.engine.TorrentMode.DOWNLOAD }.associate {
            it.infoHash.lowercase() to DownloadTag(it.progress, it.phase == TorrentPhase.FINISHED || it.phase == TorrentPhase.SEEDING)
        }
    }
    // Player-owned (debrid placeholders): sources whose addon is still downloading them say "Fetching…" and go last.
    val notReadyTick by com.kirtanya.reel.player.NotReadyMemory.changes.collectAsState()
    fun viewOf(s: Source) = SourceBrowser.view(
        s, SourceMarks.tags(s, req, marks, last, if (s.kind == com.kirtanya.reel.core.sources.SourceKind.TORRENT) s.infoHash?.lowercase()?.let(downloads::get) else null) +
            listOfNotNull(com.kirtanya.reel.player.NotReadyMemory.tag(s)),
    )
    var options by remember { mutableStateOf<SourceView?>(null) }

    // Starred sources first; the ranker's order otherwise.
    val ranked = remember(search, marks, notReadyTick) {
        com.kirtanya.reel.core.player.NotReadySources.demote(SourceMarks.order(search?.sources.orEmpty(), marks, req), com.kirtanya.reel.player.NotReadyMemory::isNotReady)
    }
    // Playing: recommend one this device can stream; downloading: size doesn't matter. The user's favourite wins if it came back.
    val best = remember(ranked, action, marks) {
        val canUse = { s: Source -> (action != SourceAction.PLAY || SourceRanker.fits(s, prefs)) && !com.kirtanya.reel.player.NotReadyMemory.isNotReady(s.key) }
        SourceMarks.favourite(ranked, marks, req)?.takeIf(canUse) ?: SourceBrowser.recommended(search?.sources.orEmpty(), canUse)
    }
    val bestView = remember(best, marks, last, downloads, notReadyTick) { best?.let(::viewOf) }
    // Trailers, teasers and other extras get their own section at the end; filters and counts cover the real thing only.
    val rows = remember(ranked, best, marks, last, downloads) { ranked.filter { it.key != best?.key && !it.isExtra }.map(::viewOf) }
    val extras = remember(ranked, marks, last, downloads) { ranked.filter { it.isExtra }.map(::viewOf) }
    val facets = remember(rows) { SourceBrowser.facets(rows) }
    LaunchedEffect(facets) { filters = SourceBrowser.prune(filters, facets) }
    val shown = remember(rows, filters) { SourceBrowser.apply(rows, filters) }
    if (fixed?.confirmReplace == true && replacing != null) LaunchedEffect(best) { confirm = best }

    val mainFocus = remember { FocusRequester() }
    if (isTv && best != null) RequestFocusOnce(mainFocus, key = best.key)
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val maxWidth = if (isTv) 1000.dp else 720.dp
    val art = listOfNotNull(ep?.stillPath?.let { TmdbImages.still(it, "original") }).ifEmpty { heroBackdrops(d) }
    val bottom = if (isTv) Tokens.Space.tvSafeV else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Tokens.Space.xxl

    // Used once: the first pick carries on from the player's position; later picks resume from history
    // (the player saves where it stopped), so a stale position never comes back.
    var handedOver by remember(resumeAtMs) { mutableStateOf(resumeAtMs) }
    fun playPicked(s: Source) {
        val inApp = s.kind != com.kirtanya.reel.core.sources.SourceKind.EXTERNAL
        play(context, playbackOf(s), startAtMs = handedOver.takeIf { inApp })
        if (inApp) handedOver = null
    }
    fun choose(s: Source) {
        when {
            action == SourceAction.PLAY || s.kind == com.kirtanya.reel.core.sources.SourceKind.EXTERNAL -> playPicked(s)
            replacing != null -> confirm = s
            else -> where.download(s)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // TV: the picture fills the room behind the list; phone: it's the top of the page.
        if (isTv) {
            Box(Modifier.fillMaxSize()) {
                HeroImage(art, Modifier.fillMaxSize())
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
                        // "At home · change": the remembered choice, and the way back to the sheet.
                        if (downloading) where.rememberedLine()?.let { ReelButton(it, where::change, kind = ButtonKind.Ghost, icon = Icons.Rounded.Download) }
                    }
                    Status(search)
                }
            }
            item(key = "best") {
                Box(Modifier.padding(horizontal = side)) {
                    when {
                        bestView != null -> RecommendedCard(
                            bestView, SourceRanker.fits(bestView.source, prefs), action, isTv, mainFocus,
                            onClick = { choose(bestView.source) }, modifier = Modifier.widthIn(max = maxWidth),
                            onMore = { options = bestView },
                        )
                        search?.done == true -> NothingFound(search, rescan, Modifier.widthIn(max = maxWidth))
                        // Providers are still answering and nothing has come back: the pick's shape, not a hole.
                        !isTv -> PickSkeleton()
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
                        } + if (isTv) " · hold OK for options" else " · long-press for options",
                        style = Tokens.Type.label, color = Tokens.Colors.textSecondary,
                        modifier = Modifier.padding(horizontal = side),
                    )
                }
            }
            items(shown, key = { it.source.key }) { v ->
                SourceRow(
                    v, fits = SourceRanker.fits(v.source, prefs), action = action, onClick = { choose(v.source) },
                    modifier = Modifier.padding(horizontal = side).widthIn(max = maxWidth),
                    onLongClick = { options = v },
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
            if (extras.isNotEmpty()) {
                item(key = "extras-title") {
                    Text(
                        "Trailers & extras · ${extras.size}",
                        style = Tokens.Type.label, color = Tokens.Colors.textSecondary,
                        modifier = Modifier.padding(horizontal = side).padding(top = Tokens.Space.l),
                    )
                }
                items(extras, key = { "extra:" + it.source.key }) { v ->
                    SourceRow(
                        v, fits = SourceRanker.fits(v.source, prefs), action = action, onClick = { choose(v.source) },
                        modifier = Modifier.padding(horizontal = side).widthIn(max = maxWidth),
                        onLongClick = { options = v },
                    )
                }
            }
            if (search?.done == true && ranked.isNotEmpty()) {
                item(key = "again") { Box(Modifier.padding(horizontal = side)) { ReelButton("Search again", rescan, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) } }
            }
        }
    }

    options?.let { v ->
        SourceOptionsDialog(
            v, req, SourceMarks.markFor(marks, req, v.source), label,
            onPlay = { playPicked(v.source) },
            onDownload = { where.download(v.source) },
            onDismiss = { options = null },
            onPlayOnTv = if (isTv || app.tvRemote.paired == null || v.source.kind == com.kirtanya.reel.core.sources.SourceKind.EXTERNAL) null
                else ({ com.kirtanya.reel.tvremote.SendToTv.sendAndTell(app, playbackOf(v.source), 0) }),
        )
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
            HeroImage(art, Modifier.fillMaxSize())
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
            search.sources.isNotEmpty() && search.sources.all { it.isExtra } -> "Only trailers and extras found"
            search.sources.isNotEmpty() -> "Only services have this"
            else -> "Nothing found"
        },
        when {
            search.total == 0 -> "Add addons or plugins in Settings to find places to play this."
            allFailed -> "Check your connection and search again."
            search.sources.isNotEmpty() && search.sources.all { it.isExtra } -> "Your sources only had trailers or extras for this. They're listed below."
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

/** The Recommended card's shape while the first answers are on their way (phone). */
@Composable
private fun PickSkeleton() {
    SkeletonHost {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).skeleton(Tokens.Radius.l, Tokens.Colors.surface1).padding(Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                SkeletonBlock(110.dp, 24.dp, radius = Tokens.Radius.pill)
                SkeletonBlock(40.dp, 24.dp, radius = Tokens.Radius.pill)
            }
            SkeletonBlock(120.dp, 20.dp)
            SkeletonBlock(null, 14.dp, Modifier.fillMaxWidth())
            SkeletonBlock(null, Tokens.Size.buttonLarge, Modifier.fillMaxWidth(), radius = Tokens.Radius.pill)
        }
    }
}

/** While the title loads on the Sources page (phone): header block, Play | Download switch, then the pick. */
@Composable
private fun SourcesSkeleton() {
    SkeletonHost {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            Box(Modifier.fillMaxWidth().height(Tokens.Cinema.sourcesHeader).skeletonHero()) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Tokens.Space.phoneGutter), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    SkeletonBlock(150.dp, 14.dp)
                    SkeletonBlock(220.dp, 32.dp)
                }
            }
            Row(Modifier.padding(horizontal = Tokens.Space.phoneGutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                SkeletonBlock(104.dp, Tokens.Size.button, radius = Tokens.Radius.pill)
                SkeletonBlock(132.dp, Tokens.Size.button, radius = Tokens.Radius.pill)
            }
            Box(Modifier.padding(horizontal = Tokens.Space.phoneGutter)) { PickSkeleton() }
        }
    }
}
