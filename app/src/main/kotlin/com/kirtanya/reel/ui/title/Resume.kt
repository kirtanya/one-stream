package com.kirtanya.reel.ui.title

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.player.PlayerActivity

/** What the title page's big button does: resume, play the next episode, or start. */
sealed interface PlayAction {
    val label: String

    /** Straight back into the player with the saved source. */
    data class Resume(val entry: WatchEntry, override val label: String) : PlayAction

    /** Pick a source for this film / episode. */
    data class Choose(val episode: EpisodeNumber?, override val label: String) : PlayAction
}

/** Rule 3 of the design language: resume beats search. */
@Composable
fun rememberPlayAction(d: TitleDetails): PlayAction {
    val history = ReelApp.get(LocalContext.current).history
    val entries by history.entries.collectAsState()
    return remember(d, entries) {
        val titleKey = "${d.summary.ref.type.path}:${d.summary.ref.id}"
        val latest = entries.filter { it.playback.titleKey == titleKey }.maxByOrNull { it.updatedAt }
        val first = d.seasons.firstOrNull { it.number > 0 }?.number?.let { EpisodeNumber(it, 1) }
        when {
            latest != null && !latest.finished && latest.positionMs >= 60_000 -> {
                val left = (latest.remainingMs / 60_000).coerceAtLeast(1)
                PlayAction.Resume(latest, listOfNotNull("Resume", latest.playback.episode?.label, "· $left min left").joinToString(" "))
            }
            latest != null && latest.finished && latest.playback.next != null ->
                PlayAction.Choose(latest.playback.next, "Play ${latest.playback.next!!.label}")
            d.seasons.isNotEmpty() -> PlayAction.Choose(first, "Play ${first?.label ?: ""}".trim())
            else -> PlayAction.Choose(null, "Play")
        }
    }
}

fun runPlayAction(context: Context, action: PlayAction, openSources: (EpisodeNumber?) -> Unit) {
    when (action) {
        is PlayAction.Resume -> context.startActivity(PlayerActivity.forPlayback(context, action.entry.playback))
        is PlayAction.Choose -> openSources(action.episode)
    }
}
