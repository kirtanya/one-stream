package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import kotlinx.serialization.Serializable

/**
 * One title on the watchlist, with enough to draw its card offline. A removal is kept as
 * [removed] = true (a tombstone) so it can travel to the TV and win over an older "add" there.
 */
@Serializable
data class WatchlistItem(
    /** [TitleRef.toString], e.g. "movie:550". */
    val key: String,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** When it was added or removed (ms); the newest change wins in a merge. */
    val changedAt: Long,
    val removed: Boolean = false,
) {
    val ref: TitleRef? get() = Watchlist.parseRef(key)

    companion object {
        fun of(t: TitleSummary, now: Long) = WatchlistItem(t.ref.toString(), t.title, t.year, t.posterPath, t.backdropPath, now)
    }
}

object Watchlist {
    /** Tombstones older than this are dropped (every device has long since seen them). */
    const val TOMBSTONE_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * Merges two copies: per title the newer change wins (an add or a removal), ties go to [incoming].
     * Newest first; old tombstones are dropped. Returns the merged list.
     */
    fun merge(local: List<WatchlistItem>, incoming: List<WatchlistItem>, now: Long): List<WatchlistItem> {
        val byKey = LinkedHashMap<String, WatchlistItem>()
        (local + incoming).forEach { item ->
            val have = byKey[item.key]
            if (have == null || item.changedAt >= have.changedAt) byKey[item.key] = item
        }
        return byKey.values
            .filterNot { it.removed && now - it.changedAt > TOMBSTONE_MS }
            .sortedByDescending { it.changedAt }
    }

    /** Titles to show (no tombstones), newest first. */
    fun visible(all: List<WatchlistItem>) = all.filterNot { it.removed }.sortedByDescending { it.changedAt }

    fun parseRef(key: String): TitleRef? {
        val (type, id) = key.split(":").takeIf { it.size == 2 } ?: return null
        val t = com.kirtanya.reel.core.tmdb.MediaType.entries.firstOrNull { it.path == type } ?: return null
        return id.toIntOrNull()?.let { TitleRef(t, it) }
    }
}
