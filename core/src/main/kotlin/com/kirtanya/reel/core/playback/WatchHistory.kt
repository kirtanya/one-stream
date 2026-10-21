package com.kirtanya.reel.core.playback

import kotlinx.serialization.Serializable

/** One remembered playback position. */
@Serializable
data class WatchEntry(
    val playback: Playback,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Counted as watched: 92 % in, or under 2 minutes left (credits). */
    val finished: Boolean
        get() = durationMs > 0 && (progress >= FINISHED_AT || durationMs - positionMs < 2 * 60_000L && progress > 0.5f)

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        const val FINISHED_AT = 0.92f
    }
}

/** A card in the Continue watching row. */
sealed interface ContinueItem {
    val entry: WatchEntry

    /** Pick up where you left off, with the same source. */
    data class Resume(override val entry: WatchEntry) : ContinueItem

    /** You finished an episode; this is the next one. */
    data class UpNext(override val entry: WatchEntry, val next: EpisodeNumber) : ContinueItem
}

object ContinueWatching {
    private const val MIN_WATCHED_MS = 60_000L
    private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    /** One card per film/show, most recent first. Barely-started and fully-watched films drop out. */
    fun select(entries: List<WatchEntry>, now: Long, limit: Int = 20): List<ContinueItem> =
        entries
            .filter { now - it.updatedAt < MAX_AGE_MS }
            .groupBy { it.playback.titleKey }
            .mapNotNull { (_, list) ->
                // The newest entry that means something decides. Barely-started newer episodes are
                // skipped (they shouldn't hide "Up next"), but a finished one with nothing after it
                // ends the search: you're caught up, so the show drops out.
                val e = list.sortedByDescending { it.updatedAt }.firstOrNull { it.finished || it.positionMs >= MIN_WATCHED_MS }
                    ?: return@mapNotNull null
                if (e.finished) e.playback.next?.let { ContinueItem.UpNext(e, it) } else ContinueItem.Resume(e)
            }
            .sortedByDescending { it.entry.updatedAt }
            .take(limit)

    /** Where to start: a few seconds before where you stopped; from the top if barely started or finished. */
    fun resumePositionMs(entry: WatchEntry?): Long = when {
        entry == null -> 0
        entry.finished -> 0
        entry.positionMs < 30_000 -> 0
        else -> entry.positionMs - 5_000
    }

    /**
     * Where the player starts: exactly at [handedOverMs] when the player handed its position over
     * (Change source: carry on where you were, no step back, even in the first 30 s), else [resumePositionMs].
     */
    fun startPositionMs(handedOverMs: Long?, entry: WatchEntry?): Long =
        handedOverMs?.takeIf { it >= 0 } ?: resumePositionMs(entry)
}

/** The player's "Change source" row (settings panel, phone and TV). */
object PlayerSourceChange {
    /** Shown under the row when it can't be used. */
    const val NO_SOURCES_NOTE = "No other sources for a file"

    /** Only a film or episode known on TMDB has a Sources page; a raw magnet or file doesn't. */
    fun available(playback: Playback): Boolean = playback.tmdbId != null && playback.tmdbType != null
}
