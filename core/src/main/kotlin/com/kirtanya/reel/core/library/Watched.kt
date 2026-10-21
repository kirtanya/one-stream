package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.tmdb.SeasonSummary
import kotlinx.serialization.Serializable

/**
 * "Watched" ticks for a film or an episode, set by hand or by finishing it. Synced phone → TV
 * (newest change per key wins, so the TV's own ticks survive). [watched] = false is a deliberate
 * "unwatched" that beats an older tick or finished history entry.
 */
@Serializable
data class WatchedMark(
    /** Playback key: "movie:550" or "tv:1399:s1e3". */
    val key: String,
    /** "movie:550" / "tv:1399". */
    val titleKey: String,
    val watched: Boolean,
    val changedAt: Long,
)

/** How much of a season or show is watched: "3 of 10". */
data class WatchedCount(val watched: Int, val total: Int) {
    val all: Boolean get() = total > 0 && watched >= total
    val any: Boolean get() = watched > 0
}

object Watched {
    fun merge(local: List<WatchedMark>, incoming: List<WatchedMark>, now: Long): List<WatchedMark> =
        Lww.merge(local, incoming, now, { it.key }, { it.changedAt }, { !it.watched })

    /**
     * Watched or not: the newest of the mark and a finished history entry decides (finishing an
     * episode after marking it unwatched counts again; marking unwatched after finishing it wins).
     */
    fun isWatched(key: String, marks: Map<String, WatchedMark>, history: Map<String, WatchEntry>): Boolean {
        val mark = marks[key]
        val finished = history[key]?.takeIf { it.finished }
        return when {
            mark == null -> finished != null
            finished == null -> mark.watched
            else -> if (finished.updatedAt > mark.changedAt) true else mark.watched
        }
    }

    /** Marks to add when history has finished entries that have no newer mark yet (so they sync too). */
    fun fromHistory(marks: Map<String, WatchedMark>, history: List<WatchEntry>): List<WatchedMark> =
        history.filter { it.finished && it.playback.tmdbId != null }
            .filter { e -> marks[e.playback.key].let { it == null || it.changedAt < e.updatedAt } }
            .map { WatchedMark(it.playback.key, it.playback.titleKey, true, it.updatedAt) }

    fun episodeKey(tvId: Int, ep: EpisodeNumber) = "tv:$tvId:s${ep.season}e${ep.episode}"

    /** Every aired regular episode of a show, in order (specials left out). */
    fun allEpisodes(seasons: List<SeasonSummary>, lastAired: Pair<Int, Int>?): List<EpisodeNumber> =
        seasons.filter { it.number > 0 }.sortedBy { it.number }.flatMap { s ->
            (1..s.episodeCount).map { EpisodeNumber(s.number, it) }
        }.filter { hasAired(it, lastAired) }

    /** One season's aired episodes. */
    fun season(seasons: List<SeasonSummary>, number: Int, lastAired: Pair<Int, Int>?): List<EpisodeNumber> =
        allEpisodes(seasons, lastAired).filter { it.season == number }

    /** Everything up to and including [ep] ("watched up to here"). */
    fun upTo(seasons: List<SeasonSummary>, ep: EpisodeNumber, lastAired: Pair<Int, Int>?): List<EpisodeNumber> =
        allEpisodes(seasons, lastAired).filter { it.season < ep.season || it.season == ep.season && it.episode <= ep.episode }

    fun count(tvId: Int, episodes: List<EpisodeNumber>, isWatched: (String) -> Boolean) =
        WatchedCount(episodes.count { isWatched(episodeKey(tvId, it)) }, episodes.size)

    /** The first unwatched aired episode after the last watched one (for "Continue: S2 E4"), or null if caught up / none watched. */
    fun nextUnwatched(tvId: Int, episodes: List<EpisodeNumber>, isWatched: (String) -> Boolean): EpisodeNumber? {
        val last = episodes.indexOfLast { isWatched(episodeKey(tvId, it)) }
        return if (last < 0) null else episodes.getOrNull(last + 1)
    }
}

/** A title hidden with "Not interested": left out of Home rows, search and recommendations. Synced. */
@Serializable
data class HiddenTitle(
    /** "movie:550" / "tv:1399". */
    val key: String,
    val title: String,
    val posterPath: String? = null,
    val hidden: Boolean = true,
    val changedAt: Long,
)

object Hidden {
    fun merge(local: List<HiddenTitle>, incoming: List<HiddenTitle>, now: Long): List<HiddenTitle> =
        Lww.merge(local, incoming, now, { it.key }, { it.changedAt }, { !it.hidden })

    fun keys(all: List<HiddenTitle>): Set<String> = all.filter { it.hidden }.mapTo(HashSet()) { it.key }
}
