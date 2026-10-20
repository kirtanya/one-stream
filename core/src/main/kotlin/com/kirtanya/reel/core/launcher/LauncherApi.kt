package com.kirtanya.reel.core.launcher

import com.kirtanya.reel.core.library.WatchlistItem
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbImages

/**
 * The read-only API One Launcher (the user's own launcher app) queries through
 * `content://com.kirtanya.reel.launcher/…`. Binding contract: docs/LAUNCHER_API.md — the client is
 * built against these names, so never rename a path, parameter or column.
 */
object LauncherApi {
    const val AUTHORITY = "com.kirtanya.reel.launcher"
    const val API_VERSION = 1

    const val DEFAULT_SEARCH_LIMIT = 20
    const val DEFAULT_CONTINUE_LIMIT = 20
    const val DEFAULT_WATCHLIST_LIMIT = 40
    /** Upper bound on any `limit`, so a bad value can't ask for a huge cursor. */
    const val MAX_LIMIT = 200

    /** Search runs on the caller's binder thread: past this the launcher gets an empty cursor. */
    const val NETWORK_TIMEOUT_MS = 6_000L

    val INFO_COLUMNS = listOf("api", "version")

    /** Column order for search / continue / watchlist / title rows (always all of them). */
    val COLUMNS = listOf(
        "key", "type", "tmdb", "imdb", "title", "subtitle", "year", "rating",
        "poster", "backdrop", "progress", "remaining_min", "link",
    )

    sealed interface Query {
        data object Info : Query
        data class Search(val q: String, val limit: Int) : Query
        data class Continue(val limit: Int) : Query
        data class Watchlist(val limit: Int) : Query
        /** By TMDB id when given, else by IMDb id (TMDB find). */
        data class Title(val type: MediaType, val imdb: String?, val tmdb: Int?) : Query
        /** The launcher's own title page (part 2): see [LauncherPage]. */
        data class Details(val type: MediaType, val tmdb: Int) : Query
        data class Episodes(val tmdb: Int, val season: Int) : Query
        data class Sources(val type: MediaType, val tmdb: Int, val season: Int?, val episode: Int?) : Query
        data object Downloads : Query
        data object Unknown : Query
    }

    /** [path] is the URI path ("/search"); [param] reads a decoded query parameter. */
    fun parse(path: String?, param: (String) -> String?): Query {
        fun limit(default: Int) = param("limit")?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_LIMIT) ?: default
        return when (path?.trim('/')) {
            "info" -> Query.Info
            "search" -> Query.Search(param("q")?.trim().orEmpty(), limit(DEFAULT_SEARCH_LIMIT))
            "continue" -> Query.Continue(limit(DEFAULT_CONTINUE_LIMIT))
            "watchlist" -> Query.Watchlist(limit(DEFAULT_WATCHLIST_LIMIT))
            "title" -> {
                val type = mediaType(param("type")) ?: return Query.Unknown
                val imdb = param("imdb")?.trim()?.takeIf(::isImdbId)
                val tmdb = param("tmdb")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                if (imdb == null && tmdb == null) Query.Unknown else Query.Title(type, imdb, tmdb)
            }
            "details" -> {
                val type = mediaType(param("type")) ?: return Query.Unknown
                Query.Details(type, positive(param("tmdb")) ?: return Query.Unknown)
            }
            "episodes" -> {
                val tmdb = positive(param("tmdb")) ?: return Query.Unknown
                val season = param("season")?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return Query.Unknown
                Query.Episodes(tmdb, season)
            }
            "sources" -> {
                val type = mediaType(param("type")) ?: return Query.Unknown
                val tmdb = positive(param("tmdb")) ?: return Query.Unknown
                val season = param("season")?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
                val episode = param("episode")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                when {
                    type == MediaType.MOVIE -> Query.Sources(type, tmdb, null, null)
                    // A show's sources are always for one episode.
                    season == null || episode == null -> Query.Unknown
                    else -> Query.Sources(type, tmdb, season, episode)
                }
            }
            "downloads" -> Query.Downloads
            else -> Query.Unknown
        }
    }

    private fun positive(s: String?) = s?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun mediaType(s: String?): MediaType? = when (s?.trim()?.lowercase()) {
        "movie" -> MediaType.MOVIE
        "tv" -> MediaType.TV
        else -> null
    }

    fun isImdbId(s: String): Boolean = Regex("tt\\d{5,10}").matches(s)

    /** One row; [values] follows [COLUMNS]. */
    data class Row(
        val key: String,
        val type: String,
        val tmdb: Long,
        val imdb: String?,
        val title: String,
        val subtitle: String?,
        val year: Int?,
        val rating: Double?,
        val poster: String?,
        val backdrop: String?,
        val progress: Double,
        val remainingMin: Int,
        val link: String,
    ) {
        fun values(): Array<Any?> = arrayOf(key, type, tmdb, imdb, title, subtitle, year, rating, poster, backdrop, progress, remainingMin, link)
    }

    fun titleRow(t: TitleSummary, imdb: String? = null) = Row(
        key = t.ref.toString(),
        type = t.ref.type.path,
        tmdb = t.ref.id.toLong(),
        imdb = imdb,
        title = t.title,
        subtitle = t.year?.toString(),
        year = t.year,
        rating = t.rating,
        poster = TmdbImages.poster(t.posterPath, "w342"),
        backdrop = TmdbImages.backdrop(t.backdropPath, "w780"),
        progress = -1.0,
        remainingMin = -1,
        link = LauncherLink.title(t.ref),
    )

    fun searchRows(results: List<TitleSummary>, limit: Int): List<Row> =
        results.distinctBy { it.ref }.take(limit).map { titleRow(it) }

    fun watchlistRows(items: List<WatchlistItem>, limit: Int): List<Row> =
        items.filterNot { it.removed }.sortedByDescending { it.changedAt }.mapNotNull { w ->
            val ref = w.ref ?: return@mapNotNull null
            Row(
                key = ref.toString(), type = ref.type.path, tmdb = ref.id.toLong(), imdb = null,
                title = w.title, subtitle = w.year?.toString(), year = w.year, rating = null,
                poster = TmdbImages.poster(w.posterPath, "w342"), backdrop = TmdbImages.backdrop(w.backdropPath, "w780"),
                progress = -1.0, remainingMin = -1, link = LauncherLink.title(ref),
            )
        }.take(limit)

    /**
     * The launcher key of a Continue watching card: "movie:<id>", or "tv:<id>:s<N>e<M>" for the
     * episode the card plays (the next one for Up next). Null for files with no TMDB id.
     */
    fun continueKey(item: ContinueItem): String? {
        val p = item.entry.playback
        val type = mediaType(p.tmdbType) ?: return null
        val id = p.tmdbId ?: return null
        val ep = when (item) {
            is ContinueItem.UpNext -> item.next
            is ContinueItem.Resume -> p.episode
        }
        return "${type.path}:$id" + if (type == MediaType.TV && ep != null) ":s${ep.season}e${ep.episode}" else ""
    }

    /** [items] already in the app's order (newest first); torrents without TMDB info are left out. */
    fun continueRows(items: List<ContinueItem>, limit: Int): List<Row> = items.mapNotNull { item ->
        val key = continueKey(item) ?: return@mapNotNull null
        val p = item.entry.playback
        val type = mediaType(p.tmdbType) ?: return@mapNotNull null
        val subtitle = when (item) {
            is ContinueItem.UpNext -> "S${item.next.season} · E${item.next.episode}"
            is ContinueItem.Resume -> p.episode?.let { ep ->
                val name = p.subtitle?.removePrefix(ep.label)?.removePrefix(" · ")?.takeIf { p.subtitle.startsWith(ep.label) && it.isNotBlank() }
                listOfNotNull("S${ep.season}", "E${ep.episode}", name).joinToString(" · ")
            } ?: p.year?.toString()
        }
        val resume = item as? ContinueItem.Resume
        val entry = item.entry
        Row(
            key = key, type = type.path, tmdb = p.tmdbId!!.toLong(), imdb = p.imdbId,
            title = p.title, subtitle = subtitle, year = p.year, rating = null,
            poster = TmdbImages.poster(p.posterPath, "w342"),
            backdrop = TmdbImages.backdrop(p.backdropPath ?: p.stillPath, "w780"),
            progress = if (resume != null && entry.durationMs > 0) entry.progress.toDouble() else -1.0,
            remainingMin = if (resume != null && entry.durationMs > 0) (entry.remainingMs / 60_000).toInt().coerceAtLeast(1) else -1,
            link = LauncherLink.resume(key),
        )
    }.take(limit)

    /**
     * What `onestream://resume/<key>` resumes: the card with exactly that key, else (a bare title
     * key like "tv:1399", or the launcher holding an older episode key) that title's card.
     */
    fun findContinue(items: List<ContinueItem>, key: String): ContinueItem? =
        items.firstOrNull { continueKey(it) == key }
            ?: titleOf(key)?.let { ref -> items.firstOrNull { it.entry.playback.titleKey == ref.toString() } }

    /** The film / show a launcher key points at ("tv:1399:s1e3" → tv 1399). */
    fun titleOf(key: String): TitleRef? {
        val parts = key.trim().split(":")
        if (parts.size !in 2..3) return null
        if (parts.size == 3 && !Regex("s\\d+e\\d+").matches(parts[2])) return null
        val type = mediaType(parts[0]) ?: return null
        val id = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return TitleRef(type, id)
    }
}
