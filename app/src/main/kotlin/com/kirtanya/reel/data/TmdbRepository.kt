package com.kirtanya.reel.data

import android.util.LruCache
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
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
    private val cache = LruCache<String, Pair<Long, String>>(120)

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

    suspend fun search(query: String): List<TitleSummary> =
        if (query.isBlank()) emptyList() else list(TmdbPaths.search(query))

    suspend fun details(ref: TitleRef): TitleDetails =
        TmdbParser.parseDetails(get(TmdbPaths.details(ref)), ref.type, region)

    suspend fun season(tvId: Int, season: Int): List<Episode> =
        TmdbParser.parseSeason(get(TmdbPaths.season(tvId, season)))

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

        fun createHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
