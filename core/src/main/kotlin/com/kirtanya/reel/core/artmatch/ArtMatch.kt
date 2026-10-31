package com.kirtanya.reel.core.artmatch

import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Artwork (and ids) for things played without a TMDB title: a magnet, a .torrent, a download, a link.
 * The name is parsed ([ReleaseNames]), searched on TMDB, scored ([TitleMatcher]) and, when confident,
 * the entry's [Playback] gets the title's name, ids, poster / backdrop and, for an episode, its still
 * and the next episode. The history key and title key never change (resume and per-title settings
 * hang off them), nor does the source.
 */
object ArtMatch {
    /** Worth a lookup: no TMDB title and no artwork yet (a season pack gets artwork only, see [apply]). */
    fun needsMatch(p: Playback): Boolean =
        p.tmdbId == null && !p.markedOnly && p.posterPath == null && p.backdropPath == null && p.stillPath == null

    /**
     * The name to search for. The torrent's name says the title; the file's own name (a season pack's
     * episode) can add which episode it is.
     */
    fun releaseName(p: Playback): ReleaseName? {
        val fromTitle = ReleaseNames.parse(p.title)
        val fromFile = p.source.filename?.let(ReleaseNames::parse)
        val base = fromTitle ?: fromFile ?: return null
        return if (fromFile?.episode != null && base.episode == null && (base.season == null || base.season == fromFile.season)) {
            base.copy(season = fromFile.season, episode = fromFile.episode)
        } else base
    }

    /**
     * [p] filled in from its match. A show with no episode in the name (a season pack) only gets the
     * artwork and name: a TMDB id without an episode would send source and subtitle searches for "the
     * show", which addons answer wrongly.
     */
    fun apply(p: Playback, m: Matched, details: TitleDetails?, episodes: List<Episode>?, name: ReleaseName): Playback {
        val title = details?.summary?.title ?: m.title
        val poster = details?.summary?.posterPath ?: m.posterPath
        val backdrop = details?.summary?.backdropPath ?: m.backdropPath
        val year = details?.summary?.year ?: m.year
        if (m.type == MediaType.TV && name.episode == null) {
            return p.copy(title = title, year = year, posterPath = poster, backdropPath = backdrop)
        }
        val episode = if (m.type == MediaType.TV) EpisodeNumber(name.season ?: 1, name.episode ?: 1) else null
        val ep = episode?.let { e -> episodes?.firstOrNull { it.season == e.season && it.number == e.episode } }
        return p.copy(
            title = title,
            subtitle = episode?.let { e -> listOfNotNull(e.label, ep?.name?.takeIf { it.isNotBlank() }).joinToString(" · ") } ?: p.subtitle,
            tmdbType = m.type.path,
            tmdbId = m.id,
            imdbId = details?.imdbId ?: m.imdbId ?: p.imdbId,
            year = year,
            episode = episode,
            posterPath = poster,
            backdropPath = backdrop,
            stillPath = ep?.stillPath,
            next = episode?.let { e -> details?.let { nextEpisode(it.seasons, e, it.lastAired) } },
        )
    }

    /**
     * What gets saved when the player saves [incoming] (often a fresh, unmatched Playback for a file
     * played again) over [existing]: the match already found is kept, with the new source.
     */
    fun keepMatch(incoming: Playback, existing: Playback?): Playback {
        if (existing == null || existing.key != incoming.key || !needsMatch(incoming) || needsMatch(existing)) return incoming
        return incoming.copy(
            title = existing.title, subtitle = existing.subtitle, tmdbType = existing.tmdbType, tmdbId = existing.tmdbId,
            imdbId = existing.imdbId, year = existing.year, episode = existing.episode, posterPath = existing.posterPath,
            backdropPath = existing.backdropPath, stillPath = existing.stillPath, next = existing.next,
        )
    }

    /** A match as cached: enough to fill a film in without asking TMDB again. */
    @Serializable
    data class Matched(
        val type: MediaType,
        val id: Int,
        val title: String,
        val year: Int? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val imdbId: String? = null,
    )
}

/**
 * Lookups already made, by [ReleaseName.lookupKey], including "no match", so a name TMDB doesn't know
 * isn't searched again on every app start. Kept [TTL_MS] (a week); stored as one small JSON file.
 */
class ArtMatchCache(entries: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(val match: ArtMatch.Matched? = null, val score: Double? = null, val at: Long = 0)

    private val map = LinkedHashMap(entries)

    @Synchronized fun get(key: String, now: Long): Entry? = map[key]?.takeIf { now - it.at in 0 until TTL_MS }

    @Synchronized fun put(key: String, entry: Entry) {
        map.remove(key)
        map[key] = entry
    }

    @Synchronized fun size() = map.size

    /** Fresh entries only, the newest [MAX_ENTRIES]. */
    @Synchronized fun encode(now: Long): String {
        val keep = map.entries.filter { now - it.value.at in 0 until TTL_MS }.sortedByDescending { it.value.at }.take(MAX_ENTRIES)
            .associate { it.key to it.value }
        return json.encodeToString(SERIALIZER, keep)
    }

    companion object {
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 400
        private val json = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), Entry.serializer())

        fun decode(text: String?): ArtMatchCache =
            ArtMatchCache(text?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty())
    }
}

/** TMDB as the matcher needs it (the app's TmdbRepository; fakes in tests and screen renders). */
interface ArtLookup {
    suspend fun search(type: MediaType, query: String, year: Int?): List<MatchCandidate>
    suspend fun details(ref: TitleRef): TitleDetails
    suspend fun season(tvId: Int, season: Int): List<Episode>
}

/**
 * Finds and applies a match for one Playback. Network failures throw (nothing is cached, so the next
 * run tries again); "no match" is cached like a match.
 */
class ArtResolver(private val lookup: ArtLookup, private val cache: ArtMatchCache, private val clock: () -> Long) {
    data class Result(
        /** The filled-in Playback, or null when there's no confident match. */
        val playback: Playback?,
        val outcome: String,
        val score: Double? = null,
        val cached: Boolean = false,
        /** The clean title searched for (telemetry: never the raw file name, which can carry a path). */
        val searched: String? = null,
        /** How many TMDB calls it took (the background pass rations them). */
        val calls: Int = 0,
    )

    suspend fun resolve(p: Playback): Result {
        if (!ArtMatch.needsMatch(p)) return Result(null, "notNeeded")
        val name = ArtMatch.releaseName(p) ?: return Result(null, "noName")
        var calls = 0
        val hit = cache.get(name.lookupKey, clock())
        val matched: ArtMatch.Matched
        val score: Double?
        if (hit != null) {
            matched = hit.match ?: return Result(null, "none", hit.score, cached = true, searched = name.title)
            score = hit.score
        } else {
            val type = if (name.isTv) MediaType.TV else MediaType.MOVIE
            var outcome = TitleMatcher.choose(name, lookup.search(type, name.title, null).also { calls++ })
            // Page one of a plain search can miss an old film behind newer namesakes: ask again with the year.
            if (outcome.candidate == null && name.year != null && outcome.reason != "ambiguous") {
                outcome = TitleMatcher.choose(name, lookup.search(type, name.title, name.year).also { calls++ })
            }
            val c = outcome.candidate
            if (c == null) {
                cache.put(name.lookupKey, ArtMatchCache.Entry(null, outcome.score, clock()))
                return Result(null, outcome.reason, outcome.score, searched = name.title, calls = calls)
            }
            matched = ArtMatch.Matched(c.type, c.id, c.title, c.year, c.posterPath, c.backdropPath)
            score = outcome.score
        }
        val ref = TitleRef(matched.type, matched.id)
        // Details give the IMDb id (subtitles) and seasons (Up next); without them the artwork still shows.
        val wantsDetails = matched.imdbId == null || (matched.type == MediaType.TV && name.episode != null)
        val details = if (wantsDetails) quietly { lookup.details(ref) }.also { calls++ } else null
        val episodes = if (matched.type == MediaType.TV && name.episode != null) {
            quietly { lookup.season(matched.id, name.season ?: 1) }.also { calls++ }
        } else null
        val full = matched.copy(imdbId = matched.imdbId ?: details?.imdbId)
        if (hit == null || full != hit.match) cache.put(name.lookupKey, ArtMatchCache.Entry(full, score, hit?.at ?: clock()))
        return Result(ArtMatch.apply(p, full, details, episodes, name), "matched", score, cached = hit != null, searched = full.title, calls = calls)
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
