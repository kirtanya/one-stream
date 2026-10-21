package com.kirtanya.reel.ui.title

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.player.PlayerActivity
import com.kirtanya.reel.ui.common.OptionAction
import com.kirtanya.reel.ui.common.OptionsHeader
import com.kirtanya.reel.ui.common.OptionsSheet
import com.kirtanya.reel.ui.nav.Dest

/**
 * Long-press on an episode (phone list, TV row): Play from start, Download, Mark watched (or Mark
 * unwatched once it's watched). Play from start replays the source it was last played with; an
 * episode never played (or only marked watched) opens its sources.
 */
@Composable
fun EpisodeOptions(
    show: TitleDetails,
    e: Episode,
    watched: WatchEntry?,
    onPlay: (season: Int, episode: Int) -> Unit,
    onOpenDest: (Dest) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val history = ReelApp.get(context).history
    val ep = EpisodeNumber(e.season, e.number)
    val label = ep.label
    val played = watched?.takeUnless { it.playback.markedOnly }
    val actions = listOf(
        OptionAction(Icons.Rounded.Replay, "Play from start", {
            if (played != null) {
                val p = played.playback
                com.kirtanya.reel.ui.homedisk.gatedPlay(context, p.source, p.title) { context.startActivity(PlayerActivity.forPlayback(context, p, 0)) }
            } else {
                onPlay(e.season, e.number)
            }
        }),
        OptionAction(Icons.Rounded.Download, "Download", { onOpenDest(Dest.Sources(show.summary.ref, e.season, e.number, download = true)) }),
        if (watched?.finished == true) {
            OptionAction(Icons.Rounded.RemoveDone, "Mark unwatched", {
                history.markUnwatched(watched.playback.key)
                Toast.makeText(context, "Marked $label as unwatched", Toast.LENGTH_SHORT).show()
            })
        } else {
            OptionAction(Icons.Rounded.TaskAlt, "Mark watched", {
                val s = show.summary
                // A played episode keeps its real source; one never played gets a stand-in nothing plays.
                val p = watched?.playback ?: Playback.watchedMarker(
                    titleKey = "tv:${s.ref.id}", key = Playback.keyFor("tv", s.ref.id, ep), title = s.title,
                    tmdbType = "tv", tmdbId = s.ref.id, episode = ep, next = nextEpisode(show.seasons, ep, show.lastAired),
                    posterPath = s.posterPath, backdropPath = s.backdropPath, stillPath = e.stillPath,
                )
                history.markWatched(p, runtimeMs = e.runtimeMin?.let { it * 60_000L })
                Toast.makeText(context, "Marked $label as watched", Toast.LENGTH_SHORT).show()
            })
        },
    )
    OptionsSheet(
        OptionsHeader("$label · ${e.name}", show.summary.title, TmdbImages.still(e.stillPath, "w300") ?: TmdbImages.backdrop(show.summary.backdropPath, "w300")),
        actions, onDismiss,
    )
}
