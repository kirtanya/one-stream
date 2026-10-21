package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.library.Watched
import com.kirtanya.reel.core.library.WatchedCount
import com.kirtanya.reel.core.library.WatchedMark
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Watched ticks for films and episodes (files/watched.json). Finishing something in the player
 * ticks it (via [recordHistory]); marking by hand also updates the watch history so Continue
 * watching moves on. Synced phone → TV with the sources; the TV merges (newest change wins).
 */
class WatchedStore(context: Context, private val history: WatchHistoryStore) {
    private val store = JsonListStore(context, "watched.json", WatchedMark.serializer())

    /** Every mark, unwatched ones included (what sync sends). */
    val all: StateFlow<List<WatchedMark>> get() = store.items

    /** A lookup that stays current: `watched.isWatched` in a composable via `collectAsState`. */
    val state: Flow<WatchedLookup> = combine(store.items, history.entries) { marks, entries ->
        WatchedLookup(marks.associateBy { it.key }, entries.associateBy { it.playback.key })
    }.distinctUntilChanged()

    fun isWatched(key: String): Boolean = lookup().isWatched(key)

    fun isWatchedFlow(key: String): Flow<Boolean> = state.map { it.isWatched(key) }.distinctUntilChanged()

    /** A film, or every aired episode of a show. */
    fun markTitle(d: TitleDetails, watched: Boolean) = when (d.summary.ref.type) {
        MediaType.MOVIE -> mark(d, listOf(null), watched)
        MediaType.TV -> mark(d, Watched.allEpisodes(d.seasons, d.lastAired), watched)
    }

    fun markEpisode(d: TitleDetails, ep: EpisodeNumber, watched: Boolean) = mark(d, listOf(ep), watched)

    fun markSeason(d: TitleDetails, season: Int, watched: Boolean) = mark(d, Watched.season(d.seasons, season, d.lastAired), watched)

    /** "Watched up to here": [ep] and everything before it. */
    fun markUpTo(d: TitleDetails, ep: EpisodeNumber) = mark(d, Watched.upTo(d.seasons, ep, d.lastAired), true)

    /** "3 of 10" for a season, or the whole show when [season] is null. */
    fun count(d: TitleDetails, season: Int? = null): WatchedCount {
        val l = lookup()
        val eps = if (season == null) Watched.allEpisodes(d.seasons, d.lastAired) else Watched.season(d.seasons, season, d.lastAired)
        return Watched.count(d.summary.ref.id, eps, l::isWatched)
    }

    /** Merges marks sent by the phone. Returns how many keys changed state. */
    fun mergeFrom(incoming: List<WatchedMark>): Int {
        val before = lookup()
        store.update { Watched.merge(it, incoming, now()) }
        val after = lookup()
        return (incoming.map { it.key }.toSet()).count { before.isWatched(it) != after.isWatched(it) }
    }

    /** Called when the history changes: finished entries become marks, so they sync to the TV too. */
    fun recordHistory(entries: List<WatchEntry>) {
        val add = Watched.fromHistory(store.items.value.associateBy { it.key }, entries)
        if (add.isNotEmpty()) store.update { Watched.merge(it, add, now()) }
    }

    private fun mark(d: TitleDetails, eps: List<EpisodeNumber?>, watched: Boolean) {
        if (eps.isEmpty()) return
        val ref = d.summary.ref
        val titleKey = "${ref.type.path}:${ref.id}"
        val t = now()
        val keys = eps.map { Playback.keyFor(ref.type.path, ref.id, it) }
        store.update { list -> Watched.merge(list, keys.map { WatchedMark(it, titleKey, watched, t) }, t) }
        if (watched) {
            // History gets the latest one, so Continue watching shows the episode after it.
            val last = eps.last()
            val key = keys.last()
            val existing = history.get(key)?.playback
            val p = existing ?: Playback.watchedMarker(
                titleKey, key, d.summary.title, ref.type.path, ref.id, last,
                last?.let { nextEpisode(d.seasons, it, d.lastAired) },
                d.summary.posterPath, d.summary.backdropPath,
            )
            history.markWatched(p, d.runtimeMin?.let { it * 60_000L })
        } else {
            keys.forEach(history::markUnwatched)
        }
    }

    private fun lookup() = WatchedLookup(store.items.value.associateBy { it.key }, history.entries.value.associateBy { it.playback.key })

    private fun now() = System.currentTimeMillis()
}

/** A snapshot of marks + history that answers "is this watched?". */
data class WatchedLookup(val marks: Map<String, WatchedMark>, val history: Map<String, WatchEntry>) {
    fun isWatched(key: String) = Watched.isWatched(key, marks, history)
}
