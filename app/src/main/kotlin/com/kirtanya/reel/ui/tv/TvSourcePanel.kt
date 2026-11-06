package com.kirtanya.reel.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.SourceMarks
import com.kirtanya.reel.core.player.TitleSourcePage
import com.kirtanya.reel.core.player.TitleSourceRow
import com.kirtanya.reel.core.player.TitleSources
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.rememberSourceSearch
import com.kirtanya.reel.ui.sources.requestFor
import com.kirtanya.reel.ui.theme.Tokens

/**
 * The sources of one film or episode, ready for the TV: the ranked [page], and what picking a row does. The title page
 * holds one for a film (its list sits on the page); [TvSourcePanel] holds one for the episode it was opened on.
 */
@Stable
class TvSourcePick(
    val page: TitleSourcePage,
    val expanded: Boolean,
    val expand: () -> Unit,
    val rescan: () -> Unit,
    val play: (Source) -> Unit,
    val download: (Source) -> Unit,
)

/**
 * Searches for [season] × [episode] (null for a film) and ranks the answers the way the Sources page does: the user's
 * stars first, debrid "still downloading" ones last, services after everything that plays here. Playing hands over
 * exactly like the Sources page ([com.kirtanya.reel.ui.sources.play]): the player resumes from history.
 */
@Composable
fun rememberTvSourcePick(d: TitleDetails, season: Int?, episode: Int?, episodeName: String?, stillPath: String?): TvSourcePick {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val req = remember(d, season, episode) { requestFor(d, season, episode) }
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val (search, rescan) = if (fixed?.sourceSearch != null) fixed.sourceSearch.invoke() to {} else rememberSourceSearch(req, true)
    // Renders pin the data, and a Mac sandbox has no free space to measure: no size cap there.
    val prefs = remember { if (fixed != null) com.kirtanya.reel.core.sources.RankPrefs() else rankPrefs(context, true) }
    val marks by app.sourceMarks.marks.collectAsState()
    val notReadyTick by com.kirtanya.reel.player.NotReadyMemory.changes.collectAsState()
    val entries by app.history.entries.collectAsState()
    var expanded by remember(req.key) { mutableStateOf(false) }

    val ordered = remember(search, marks, notReadyTick) {
        com.kirtanya.reel.core.player.NotReadySources.demote(SourceMarks.order(search?.sources.orEmpty(), marks, req), com.kirtanya.reel.player.NotReadyMemory::isNotReady)
    }
    // The saved position of this very film / episode: any pick resumes there (the player reads history), so the top row says so.
    val resume = remember(entries, req) {
        entries.firstOrNull { it.playback.key == req.key }?.takeIf { ContinueWatching.resumePositionMs(it) > 0 }?.let { TitleSources.resumeLabel(it.positionMs) }
    }
    val page = remember(search, ordered, expanded, resume) {
        TitleSources.page(search, ordered, expanded, fits = { SourceRanker.fits(it, prefs) }, resume = resume)
    }

    val epNumber = if (season != null && episode != null) EpisodeNumber(season, episode) else null
    val label = d.summary.title + if (epNumber != null) " · ${epNumber.label}" else ""
    val origin = remember(d, season, episode, label) { DownloadOrigin(d.summary.ref.type.path, d.summary.ref.id, season, episode, label) }
    return TvSourcePick(
        page, expanded, expand = { expanded = true }, rescan = rescan,
        play = { s -> com.kirtanya.reel.ui.sources.play(context, com.kirtanya.reel.ui.sources.playbackFor(d, epNumber, episodeName, stillPath, s)) },
        download = { s ->
            if (s.kind == SourceKind.EXTERNAL) android.widget.Toast.makeText(context, "This source can't be downloaded", android.widget.Toast.LENGTH_SHORT).show()
            else com.kirtanya.reel.ui.sources.download(context, s, label, origin)
        },
    )
}

private val ROW_SHAPE = RoundedCornerShape(Tokens.Radius.media)

/**
 * The source rows: best first, "All sources…" at the end, then the search status (or, once done, "Hold OK to download").
 * Cheap rows (two texts, no per-row effects); the ring is the focus. Focus goes to the first row when rows first appear,
 * to [empty] (Search again / Close) while there are none, and to the first extra row after "All sources…".
 */
@Composable
internal fun TvSourceRows(
    pick: TvSourcePick,
    modifier: Modifier,
    first: FocusRequester,
    onPick: (Source) -> Unit,
    /** The panel's Close; null on the title page (nothing to close). */
    onClose: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    holdHint: String = "Hold OK on a source to download it",
) {
    val page = pick.page
    val empty = remember { FocusRequester() }
    val extra = remember { FocusRequester() }
    if (page.rows.isNotEmpty()) RequestFocusOnce(first) else RequestFocusOnce(empty)
    if (pick.expanded) RequestFocusOnce(extra)
    com.kirtanya.reel.ui.common.WithBringIntoView(com.kirtanya.reel.ui.common.MinimalBringIntoView) {
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            itemsIndexed(page.rows, key = { _, r -> r.key }, contentType = { _, _ -> "source" }) { i, r ->
                val focus = when {
                    i == 0 -> Modifier.focusRequester(first)
                    pick.expanded && i == TitleSources.TOP -> Modifier.focusRequester(extra)
                    else -> Modifier
                }
                TvSourceRow(r, focus, onClick = { onPick(r.source) }, onLongClick = { pick.download(r.source) })
            }
            if (page.more > 0) item(key = "all", contentType = "all") { TvAllSourcesRow(page.more, pick.expand) }
            val empty0 = page.empty
            when {
                empty0 != null -> item(key = "empty", contentType = "empty") {
                    Column(Modifier.padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Text(empty0.title, style = Tokens.Type.heading, color = Tokens.Colors.text)
                        Text(empty0.body, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            ReelButton("Search again", pick.rescan, Modifier.focusRequester(empty), kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                            if (onClose != null) ReelButton("Close", onClose, kind = ButtonKind.Ghost)
                        }
                    }
                }
                page.rows.isEmpty() -> item(key = "searching", contentType = "empty") {
                    Column(Modifier.padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Text(page.searching ?: "Searching…", style = Tokens.Type.label, color = Tokens.Colors.textSecondary)
                        // Something always takes focus inside the panel, so Back and the remote have a place to be.
                        if (onClose != null) ReelButton("Close", onClose, Modifier.focusRequester(empty), kind = ButtonKind.Ghost)
                    }
                }
                else -> item(key = "status", contentType = "status") {
                    Text(
                        page.searching ?: holdHint, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary,
                        modifier = Modifier.padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
                    )
                }
            }
        }
    }
}

/** One source: "1080p · 4.2 GB · HDR" over "Torrentio · 142 seeders · Hindi, English"; "Resume 42:10" on the right when it applies. */
@Composable
private fun TvSourceRow(r: TitleSourceRow, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .focusCard(shape = ROW_SHAPE, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = Tokens.Space.m, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.detail, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.tooBig) Text("Too big for this device", style = Tokens.Type.caption, color = Tokens.Colors.warn, maxLines = 1)
        }
        r.resume?.let { resume ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Tokens.Colors.text, modifier = Modifier.size(16.dp))
                Text(resume, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TvAllSourcesRow(more: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).focusCard(shape = ROW_SHAPE, onClick = onClick).padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("All sources", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1)
        Text("$more more", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
        // The rest opens in place below, so a down chevron says what OK does (the user asked for an arrow here).
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, null,
            tint = Tokens.Colors.textSecondary, modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The sources of one episode as a panel over the title page (docs/TV_CLEAN_REDESIGN.md §5): ~40 % of the width on the right,
 * a solid surface. OK plays (or, with [download], downloads); hold OK downloads; Back closes. Focus can't leave it while it's
 * open ([focusProperties] onExit cancels every move), and the title page puts it back on the episode afterwards.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvSourcePanel(d: TitleDetails, episode: Episode, download: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val pick = rememberTvSourcePick(d, episode.season, episode.number, episode.name, episode.stillPath)
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Tokens.Colors.scrim)) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(PANEL_SHARE)
                .background(Tokens.Colors.surface1)
                .border(Tokens.Size.hairline, Tokens.Colors.hairline)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(start = Tokens.Space.xl, end = Tokens.Space.m, top = Tokens.Space.tvSafeV, bottom = Tokens.Space.tvSafeV),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Column(Modifier.padding(end = Tokens.Space.m), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                Text(if (download) "Choose a source to download" else "Choose a source to play", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
                Text(
                    listOfNotNull(EpisodeNumber(episode.season, episode.number).label, episode.name.ifBlank { null }).joinToString(" · "),
                    style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(d.summary.title, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvSourceRows(
                pick, Modifier.weight(1f).fillMaxWidth(), first,
                onPick = { s ->
                    if (download) { pick.download(s); onClose() } else pick.play(s)
                },
                onClose = onClose,
                holdHint = if (download) "OK downloads the source you pick" else "Hold OK on a source to download it",
            )
        }
    }
}

/** The panel's share of the screen's width. */
private const val PANEL_SHARE = 0.4f
