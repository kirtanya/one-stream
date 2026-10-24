package com.kirtanya.reel.core.ratings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ratings cache, kept as one small JSON file by the app. Two maps:
 * - [posters]: TMDB key ("movie:550") → IMDb id + IMDb rating (from Cinemeta), for the pill on posters;
 * - [full]: IMDb id → OMDb's IMDb / Rotten Tomatoes / Metascore, for title pages (OMDb's free tier is
 *   1,000 calls a day, so it is never asked for posters and answers are kept a week).
 * The IMDb id of a TMDB title never changes, so it is kept even after the rating goes stale: a refresh then
 * costs one Cinemeta call instead of TMDB + Cinemeta.
 */
class RatingsCache(
    posters: Map<String, PosterEntry> = emptyMap(),
    full: Map<String, FullEntry> = emptyMap(),
    seasons: Map<String, SeasonEntry> = emptyMap(),
) {
    /** One season's episode ratings from OMDb (episode number → IMDb), keyed "tt…:season". */
    @Serializable
    data class SeasonEntry(val episodes: Map<Int, Double> = emptyMap(), val at: Long = 0, val ok: Boolean = true)

    @Serializable
    data class PosterEntry(val imdbId: String? = null, val imdb: Double? = null, val at: Long = 0, val noImdbId: Boolean = false)

    @Serializable
    data class FullEntry(
        val ratings: TitleRatings? = null,
        val at: Long = 0,
        /** False when OMDb failed and [ratings] is Cinemeta's IMDb alone: worth asking OMDb again sooner. */
        val complete: Boolean = true,
    )

    @Serializable
    private data class Snapshot(
        val posters: Map<String, PosterEntry> = emptyMap(),
        val full: Map<String, FullEntry> = emptyMap(),
        val seasons: Map<String, SeasonEntry> = emptyMap(),
    )

    private val posters = LinkedHashMap(posters)
    private val full = LinkedHashMap(full)
    private val seasons = LinkedHashMap(seasons)

    @Synchronized fun season(imdbId: String, season: Int): SeasonEntry? = seasons[seasonKey(imdbId, season)]

    @Synchronized fun putSeason(imdbId: String, season: Int, entry: SeasonEntry) {
        val k = seasonKey(imdbId, season)
        this.seasons.remove(k)
        this.seasons[k] = entry
        trim(this.seasons, MAX_SEASONS)
    }

    /** The poster entry, fresh or not (the caller decides with [isFresh]); null when never fetched. */
    @Synchronized fun poster(key: String): PosterEntry? = posters[key]

    @Synchronized fun full(imdbId: String): FullEntry? = full[imdbId]

    @Synchronized fun putPoster(key: String, entry: PosterEntry) {
        posters.remove(key)
        posters[key] = entry
        trim(posters, MAX_POSTERS)
    }

    @Synchronized fun putFull(imdbId: String, entry: FullEntry) {
        full.remove(imdbId)
        full[imdbId] = entry
        trim(full, MAX_FULL)
    }

    @Synchronized fun encode(): String = json.encodeToString(Snapshot.serializer(), Snapshot(LinkedHashMap(posters), LinkedHashMap(full), LinkedHashMap(seasons)))

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** A known rating moves slowly: a week. */
        const val POSTER_HIT_MS = 7 * DAY_MS
        /** No rating yet (new release, Cinemeta down): try again in two days. */
        const val POSTER_MISS_MS = 2 * DAY_MS
        const val FULL_HIT_MS = 7 * DAY_MS
        const val FULL_MISS_MS = 1 * DAY_MS
        /** About 200 KB on disk at most; the oldest go first. */
        const val MAX_POSTERS = 4000
        const val MAX_FULL = 600
        const val MAX_SEASONS = 400
        /** Episode ratings settle slowly once aired; a failed / empty answer is retried after a day. */
        const val SEASON_HIT_MS = 7 * DAY_MS

        fun seasonKey(imdbId: String, season: Int) = "$imdbId:$season"

        fun isFresh(e: SeasonEntry, now: Long): Boolean = isFresh(e.at, now, if (e.ok && e.episodes.isNotEmpty()) SEASON_HIT_MS else FULL_MISS_MS)

        private val json = Json { ignoreUnknownKeys = true }

        /** A cache file's text → cache; anything unreadable starts empty (it's only a cache). */
        fun decode(text: String?): RatingsCache {
            if (text.isNullOrBlank()) return RatingsCache()
            val s = runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrNull() ?: return RatingsCache()
            return RatingsCache(s.posters, s.full, s.seasons)
        }

        /** Saved at [savedAt], still good at [now] for [ttlMs]? A clock that went backwards counts as stale. */
        fun isFresh(savedAt: Long, now: Long, ttlMs: Long): Boolean = now >= savedAt && now - savedAt < ttlMs

        fun isFresh(e: PosterEntry, now: Long): Boolean = isFresh(e.at, now, if (e.imdb != null) POSTER_HIT_MS else POSTER_MISS_MS)

        fun isFresh(e: FullEntry, now: Long): Boolean =
            isFresh(e.at, now, if (e.complete && e.ratings != null && !e.ratings.isEmpty) FULL_HIT_MS else FULL_MISS_MS)

        private fun <V> trim(map: LinkedHashMap<String, V>, max: Int) {
            val it = map.keys.iterator()
            while (map.size > max && it.hasNext()) { it.next(); it.remove() }
        }
    }
}
