package com.kirtanya.reel.data

import android.util.LruCache
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.core.browse.SearchRank
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.PersonDetails
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.TmdbPaths
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A titled row of posters on Home. */
data class HomeRow(val title: String, val items: List<TitleSummary>)

class TmdbException(message: String) : Exception(message)

/**
 * TMDB access with a small in-memory cache (15 min) and retries, since some networks
 * drop requests. Region is India for "where to watch".
 */
class TmdbRepository(private val http: OkHttpClient, private val region: String = "IN") {
    private val token = BuildConfig.TMDB_TOKEN
    private val cache = LruCache<String, Pair<Long, String>>(200)

    val hasKey: Boolean get() = token.isNotBlank()

    suspend fun home(): List<HomeRow> = coroutineScope {
        val trending = async { list(TmdbPaths.trending(null)) }
        val movies = async { list(TmdbPaths.popular(MediaType.MOVIE, region), MediaType.MOVIE) }
        val shows = async { list(TmdbPaths.trending(MediaType.TV), MediaType.TV) }
        val indian = async { list(TmdbPaths.discoverFromCountry(MediaType.MOVIE, region), MediaType.MOVIE) }
        val topShows = async { list(TmdbPaths.topRated(MediaType.TV, region), MediaType.TV) }
        listOf(
            HomeRow("Trending this week", trending.await()),
            HomeRow("Popular movies", movies.await()),
            HomeRow("Trending shows", shows.await()),
            HomeRow("Popular in India", indian.await()),
            HomeRow("Top rated shows", topShows.await()),
        ).filter { it.items.isNotEmpty() }
    }

    /** What's streaming on a service in India: films and shows, interleaved by popularity. */
    suspend fun onService(providerId: Int): List<TitleSummary> = coroutineScope {
        val movies = async { list(TmdbPaths.onService(MediaType.MOVIE, providerId, region), MediaType.MOVIE) }
        val shows = async { list(TmdbPaths.onService(MediaType.TV, providerId, region), MediaType.TV) }
        val m = movies.await()
        val t = shows.await()
        (0 until maxOf(m.size, t.size)).flatMap { i -> listOfNotNull(t.getOrNull(i), m.getOrNull(i)) }
    }

    /**
     * Everything TMDB finds for [query] (first three pages, people's known-for titles included), ranked by
     * [SearchRank]: popular and well-matched first, nudged towards [taste] (genre weights, fetched alongside). When few titles come
     * back and a word looks misspelt ("aben"), the corrected search ("avengers") is merged in.
     */
    suspend fun search(query: String, taste: suspend () -> Map<Int, Double> = { emptyMap() }): List<TitleSummary> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val liking = async { runCatching { taste() }.getOrDefault(emptyMap()) }
        suspend fun pages(q: String, count: Int): List<SearchRank.Hit> {
            val rest = (2..count).map { p -> async { runCatching { TmdbParser.parseSearchHits(get(TmdbPaths.search(q, p))) }.getOrDefault(emptyList()) } }
            return TmdbParser.parseSearchHits(get(TmdbPaths.search(q, 1))) + rest.flatMap { it.await() }
        }
        var hits = pages(query, 3)
        if (hits.distinctBy { it.summary.ref }.size < FEW_RESULTS && query.split(' ').any { it.length >= 4 }) {
            val fixed = runCatching { SearchRank.correct(query, wellKnownTitles()) }.getOrNull()
            if (fixed != null) hits = hits + runCatching { pages(fixed, 2) }.getOrDefault(emptyList())
        }
        SearchRank.rank(query, hits, liking.await())
    }

    @Volatile private var tasteMemo: Pair<List<TitleRef>, Map<Int, Double>>? = null

    /** Genre weights from [refs] (recently watched and saved titles; details are cached), for ranking search. */
    suspend fun taste(refs: List<TitleRef>): Map<Int, Double> = coroutineScope {
        tasteMemo?.let { (for_, taste) -> if (for_ == refs) return@coroutineScope taste }
        val genres = refs.map { ref -> async { runCatching { details(ref).genreList.map { it.id } }.getOrNull() } }.mapNotNull { it.await() }
        SearchRank.taste(genres).also { if (genres.isNotEmpty()) tasteMemo = refs to it }
    }

    @Volatile private var wellKnown: Pair<Long, List<String>>? = null

    /** Names of ~400 well-known films and shows, most popular first (an hour's cache): the spelling-fix dictionary. */
    private suspend fun wellKnownTitles(): List<String> = coroutineScope {
        wellKnown?.let { (at, names) -> if (System.currentTimeMillis() - at < WELL_KNOWN_MS) return@coroutineScope names }
        val pages = MediaType.entries.flatMap { type -> (1..10).map { p -> async { runCatching { TmdbParser.parseSearchHits(get(TmdbPaths.wellKnown(type, p)), type) }.getOrDefault(emptyList()) } } }
        val names = pages.flatMap { it.await() }.sortedByDescending { it.popularity }.map { it.summary.title }
        if (names.isNotEmpty()) wellKnown = System.currentTimeMillis() to names
        names
    }

    /** Films or shows named [query] with popularity and original titles (artwork matching for raw files). */
    suspend fun matchCandidates(type: MediaType, query: String, year: Int?): List<com.kirtanya.reel.core.artmatch.MatchCandidate> =
        com.kirtanya.reel.core.artmatch.TitleMatcher.parseSearch(get(TmdbPaths.searchTitle(type, query, year)), type)

    suspend fun details(ref: TitleRef): TitleDetails =
        TmdbParser.parseDetails(get(TmdbPaths.details(ref)), ref.type, region)

    suspend fun season(tvId: Int, season: Int): List<Episode> =
        TmdbParser.parseSeason(get(TmdbPaths.season(tvId, season)))

    /** The title's logo artwork path (for a Home hero), or null. Details already carry it as [TitleDetails.logoPath]. */
    suspend fun logo(ref: TitleRef): String? = runCatching { TmdbParser.parseLogo(get(TmdbPaths.images(ref))) }.getOrNull()

    /** The title with this IMDb id (tt…), preferring [type]; null when TMDB doesn't know it. */
    suspend fun findByImdb(imdbId: String, type: MediaType): TitleSummary? =
        TmdbParser.parseFind(get(TmdbPaths.find(imdbId)), type)

    /** All genres for films or shows (stable; cached like everything else). */
    suspend fun genres(type: MediaType): List<Genre> = TmdbParser.parseGenres(get(TmdbPaths.genres(type)))

    /** Popular titles in one genre; [page] starts at 1 (20 per page). */
    suspend fun byGenre(type: MediaType, genreId: Int, page: Int = 1): List<TitleSummary> =
        list(TmdbPaths.byGenre(type, genreId, page), type)

    /** A film series: its films in release order. */
    suspend fun collection(id: Int): com.kirtanya.reel.core.tmdb.CollectionDetails =
        TmdbParser.parseCollection(get(TmdbPaths.collection(id)))

    /** Discover with filters (genre grid / Browse); 20 per page, [page] from 1. */
    suspend fun discover(filter: com.kirtanya.reel.core.tmdb.DiscoverFilter, page: Int = 1): List<TitleSummary> =
        list(TmdbPaths.discover(filter, page), filter.type)

    /**
     * Episodes of [shows] airing from [from] to [to] (a week back to a few weeks ahead, say). Ended
     * shows are skipped; a show that fails to load is left out rather than failing the whole calendar.
     */
    suspend fun calendar(shows: List<TitleRef>, from: java.time.LocalDate, to: java.time.LocalDate): List<com.kirtanya.reel.core.tmdb.CalendarEntry> = coroutineScope {
        val details = shows.filter { it.type == MediaType.TV }.distinct()
            .map { ref -> async { runCatching { details(ref) }.getOrNull() } }
            .mapNotNull { it.await() }
            .filter(com.kirtanya.reel.core.tmdb.EpisodeCalendar::isFollowable)
        val seasons = details.map { d ->
            async { d.summary.ref.id to (com.kirtanya.reel.core.tmdb.EpisodeCalendar.seasonToFetch(d)?.let { runCatching { season(d.summary.ref.id, it) }.getOrNull() }.orEmpty()) }
        }.associate { it.await() }
        com.kirtanya.reel.core.tmdb.EpisodeCalendar.entries(details, seasons, from, to)
    }

    /** The first page of user reviews (kept with the rest of TMDB's answers for 15 min). */
    suspend fun reviews(ref: TitleRef): com.kirtanya.reel.core.tmdb.Reviews =
        com.kirtanya.reel.core.tmdb.ReviewParser.parse(get(TmdbPaths.reviews(ref)))

    /** The title's IMDb id (tt…), or null when TMDB has none. */
    suspend fun imdbId(ref: TitleRef): String? =
        com.kirtanya.reel.core.ratings.RatingsParser.imdbId(get(TmdbPaths.externalIds(ref)))

    /** A cast member's page (photo, bio, films and shows). */
    suspend fun person(id: Int): PersonDetails = TmdbParser.parsePerson(get(TmdbPaths.person(id)))

    private suspend fun list(path: String, defaultType: MediaType? = null) =
        TmdbParser.parseList(get(path), defaultType)

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        if (!hasKey) throw TmdbException("No TMDB key in this build")
        cache.get(path)?.let { (at, body) -> if (System.currentTimeMillis() - at < CACHE_MS) return@withContext body }
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(BASE + path)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        cache.put(path, System.currentTimeMillis() to body)
                        return@withContext body
                    }
                    if (response.code in 400..499 && response.code != 429) throw TmdbException("TMDB said ${response.code}")
                    last = TmdbException("TMDB said ${response.code}")
                }
            } catch (e: IOException) {
                last = e
            }
            delay(400L * (attempt + 1))
        }
        throw TmdbException("Can't reach TMDB. Check your connection.").apply { last?.let(::initCause) }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3/"
        private const val CACHE_MS = 15 * 60 * 1000L
        /** Fewer distinct titles than this and a misspelt word: also search the corrected spelling. */
        private const val FEW_RESULTS = 10
        private const val WELL_KNOWN_MS = 60 * 60 * 1000L

        fun createHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
