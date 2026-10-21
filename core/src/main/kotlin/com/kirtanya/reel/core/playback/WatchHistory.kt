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

/** One day of the History page: "Today", "Yesterday", then "Mon 19 Oct" (year added when not this year). */
data class HistoryDay(val date: java.time.LocalDate, val label: String, val entries: List<WatchEntry>)

object HistoryDays {
    /** [entries] grouped by the local day they were last watched, newest day first, newest first within a day. */
    fun group(entries: List<WatchEntry>, zone: java.time.ZoneId, now: Long): List<HistoryDay> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries.sortedByDescending { it.updatedAt }
            .groupBy { java.time.Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }
            .map { (date, list) -> HistoryDay(date, label(date, today), list) }
    }

    fun label(date: java.time.LocalDate, today: java.time.LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(
            java.time.format.DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", java.util.Locale.ENGLISH),
        )
    }
}

/** "Mark watched" / "Mark unwatched" without playing (the long-press options sheet). */
object WatchMarks {
    /**
     * [entries] with [playback] counted as finished, newest. A film / episode already in history keeps
     * its real source (so "Last played" still knows it); otherwise [playback] is the stand-in from
     * [Playback.watchedMarker]. [runtimeMs] unknown → 1 ms, still finished.
     */
    fun watched(entries: List<WatchEntry>, playback: Playback, runtimeMs: Long?, now: Long): List<WatchEntry> {
        val old = entries.firstOrNull { it.playback.key == playback.key }
        val duration = old?.durationMs?.takeIf { it > 0 } ?: runtimeMs?.takeIf { it > 0 } ?: 1L
        val p = old?.playback?.takeUnless { it.markedOnly }?.copy(next = playback.next ?: old.playback.next) ?: playback
        return listOf(WatchEntry(p, duration, duration, now)) + entries.filterNot { it.playback.key == playback.key }
    }

    /** Forgets [key] (a film or one episode): it plays from the start and Continue watching moves back. */
    fun unwatched(entries: List<WatchEntry>, key: String): List<WatchEntry> = entries.filterNot { it.playback.key == key }
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
