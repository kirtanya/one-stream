package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.DownloadedSet
import com.kirtanya.reel.core.library.Watched
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.title.rememberTitle

/**
 * "Completely downloaded": a green disc with the download-done mark, drawn wherever a film, a show or an episode is
 * fully on the device (cards, My list, search, Continue watching; the title page and episode rows use [DownloadedPill]
 * or this same disc). Only complete downloads get it, never one that is still running or paused. The disc is a plain
 * background (no clip layer or stroked path per card, which TV can't afford); its mark is the canvas colour on the
 * status green, so it reads on any picture and in light and dark.
 */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    val tv = LocalIsTv.current
    Box(
        modifier
            .size(if (tv) 28.dp else 22.dp)
            .background(Tokens.Colors.good, CircleShape)
            .semantics { contentDescription = "Downloaded" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = Tokens.Colors.canvas, modifier = Modifier.size(if (tv) 18.dp else 14.dp))
    }
}

/** The title page's version: the disc and the word, on the page's own surface. */
@Composable
fun DownloadedPill(modifier: Modifier = Modifier, label: String = "Downloaded") {
    Row(
        modifier
            .background(Tokens.Colors.surface2, RoundedCornerShape(percent = 50))
            .padding(start = Tokens.Space.xs, end = Tokens.Space.m, top = Tokens.Space.xs, bottom = Tokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        DownloadedBadge()
        Text(label, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1)
    }
}

/** What is completely downloaded right now: the downloads store, or the fixed set a screen render carries. */
@Composable
fun rememberDownloaded(): DownloadedSet {
    LocalRenderData.current?.downloaded?.let { return it }
    val store = ReelApp.get(LocalContext.current).downloaded
    val set by store.set.collectAsState()
    return set
}

/**
 * Whether this title is completely downloaded: a film once its download finished; a show once every aired episode is
 * (so a show with one finished episode out of ten shows nothing). The show's episode list is only looked up for shows
 * that have at least one finished episode.
 */
@Composable
fun rememberTitleDownloaded(ref: TitleRef): Boolean {
    val set = rememberDownloaded()
    return when (ref.type) {
        MediaType.MOVIE -> set.movie(ref.id)
        MediaType.TV -> set.anyEpisode(ref.id) && showComplete(ref, set)
    }
}

@Composable
private fun showComplete(ref: TitleRef, set: DownloadedSet): Boolean {
    val (load, _) = rememberTitle(ref)
    val d = (load as? Load.Ready)?.value ?: return false
    return set.show(ref.id, Watched.allEpisodes(d.seasons, d.lastAired))
}

/** Whether one episode is completely downloaded (an episode row's badge). */
@Composable
fun rememberEpisodeDownloaded(tvId: Int, season: Int, episode: Int): Boolean = rememberDownloaded().episode(tvId, EpisodeNumber(season, episode))
