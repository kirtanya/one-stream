package com.kirtanya.reel.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.core.ratings.RatingsCache
import com.kirtanya.reel.core.ratings.RatingsParser
import com.kirtanya.reel.core.ratings.TitleRatings
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.engine.EngineStats
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Outside ratings (docs/DECISIONS.md 2026-10-24 "Ratings + reviews"):
 * - posters: TMDB external_ids → IMDb id → Cinemeta's imdbRating (no key, no daily limit), fetched only for
 *   posters on screen, a few at a time, cached on disk for a week;
 * - title pages: OMDb (IMDb + Rotten Tomatoes + Metascore), 1,000 calls a day on the free key, so only
 *   there and cached a week per IMDb id; Cinemeta stands in when OMDb fails or there's no key.
 * Every failure is quiet (null): ratings are decoration, the screens never wait for them.
 */
class RatingsRepository(
    context: Context,
    http: OkHttpClient,
    private val tmdb: TmdbRepository,
    isTv: Boolean,
) {
    // Short timeouts: a pill that shows up 15 s late is worse than none.
    private val http = http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val omdbKey = BuildConfig.OMDB_KEY
    private val file = AtomicFile(File(context.filesDir, "ratings-cache.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = Channel<Unit>(Channel.CONFLATED)
    // The TV (a weak 32-bit box) fetches two at a time, the phone four.
    private val gate = Semaphore(if (isTv) 2 else 4)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Double?>>()

    @Volatile private var loaded: RatingsCache? = null

    init {
        scope.launch {
            for (u in saves) {
                delay(SAVE_DELAY_MS) // batch a screenful of answers into one write
                write()
            }
        }
    }

    private fun cache(): RatingsCache = loaded ?: synchronized(this) {
        loaded ?: RatingsCache.decode(runCatching { if (file.baseFile.exists()) file.readFully().decodeToString() else null }.getOrNull()).also { loaded = it }
    }

    /** What the cache already knows (stale or not), without any network: posters draw it straight away. */
    fun cachedImdb(ref: TitleRef): Double? = loaded?.poster(ref.toString())?.imdb

    /** Loads the disk cache off the main thread, so the first rows on screen find their ratings at once. */
    suspend fun warm() = withContext(Dispatchers.IO) { cache(); Unit }

    /**
     * The IMDb rating for a poster: from the cache when fresh, else fetched (several posters asking for the same
     * title share one fetch). Cancelling the caller (poster scrolled away) gives up its place in the queue.
     */
    suspend fun posterImdb(ref: TitleRef): Double? {
        val key = ref.toString()
        val c = withContext(Dispatchers.IO) { cache() }
        val now = System.currentTimeMillis()
        val entry = c.poster(key)
        if (entry != null && RatingsCache.isFresh(entry, now)) return entry.imdb
        val mine = CompletableDeferred<Double?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await() ?: entry?.imdb
        try {
            val fresh = gate.withPermit { fetchPoster(ref, entry) }
            mine.complete(fresh)
            return fresh ?: entry?.imdb
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private suspend fun fetchPoster(ref: TitleRef, old: RatingsCache.PosterEntry?): Double? {
        val started = EngineStats.now()
        val imdbId = old?.imdbId ?: if (old?.noImdbId == true) null else runCatching { tmdb.imdbId(ref) }.getOrElse {
            if (it is CancellationException) throw it
            return null // TMDB unreachable: try again next time, cache nothing
        }
        if (imdbId == null) {
            save(ref.toString(), RatingsCache.PosterEntry(null, null, System.currentTimeMillis(), noImdbId = true))
            return null
        }
        val rating = cinemeta(imdbId, ref.type)
        // A failed Cinemeta call keeps the old rating (stale beats nothing) but stamps it, so it isn't retried every scroll.
        save(ref.toString(), RatingsCache.PosterEntry(imdbId, rating ?: old?.imdb, System.currentTimeMillis()))
        EngineStats.log("ratings", mapOf("src" to "poster", "ok" to (rating != null), "ms" to EngineStats.now() - started))
        return rating
    }

    /** The title page's ratings: OMDb when there's a key, else (or when it fails) IMDb from Cinemeta. */
    suspend fun titleRatings(ref: TitleRef, imdbId: String?): TitleRatings? {
        if (imdbId == null) return null
        val c = withContext(Dispatchers.IO) { cache() }
        val now = System.currentTimeMillis()
        c.full(imdbId)?.takeIf { RatingsCache.isFresh(it, now) }?.let { return it.ratings }
        val started = EngineStats.now()
        val omdb = if (omdbKey.isNotBlank()) omdb(imdbId) else null
        val ratings = omdb ?: cinemeta(imdbId, ref.type)?.let { TitleRatings(imdb = it) }
        // Only a real OMDb answer earns the week; a Cinemeta stand-in is retried after a day.
        c.putFull(imdbId, RatingsCache.FullEntry(ratings, System.currentTimeMillis(), complete = omdb != null))
        ratings?.imdb?.let { c.putPoster(ref.toString(), RatingsCache.PosterEntry(imdbId, it, System.currentTimeMillis())) }
        saves.trySend(Unit)
        EngineStats.log("ratings", mapOf("src" to if (omdb != null) "omdb" else "cinemeta", "ok" to (ratings != null), "ms" to EngineStats.now() - started))
        return ratings
    }

    /**
     * A season's episode ratings (episode number → IMDb), one OMDb call per season, only for the season on
     * screen, kept a week. Empty without an OMDb key or when OMDb doesn't know the season.
     */
    suspend fun episodeRatings(showImdbId: String?, season: Int): Map<Int, Double> {
        if (showImdbId == null || omdbKey.isBlank() || season < 1) return emptyMap()
        val c = withContext(Dispatchers.IO) { cache() }
        c.season(showImdbId, season)?.takeIf { RatingsCache.isFresh(it, System.currentTimeMillis()) }?.let { return it.episodes }
        val started = EngineStats.now()
        val body = body("https://www.omdbapi.com/?apikey=$omdbKey&i=$showImdbId&Season=$season")
        val episodes = body?.let(RatingsParser::omdbSeason).orEmpty()
        c.putSeason(showImdbId, season, RatingsCache.SeasonEntry(episodes, System.currentTimeMillis(), ok = body != null))
        saves.trySend(Unit)
        EngineStats.log("ratings", mapOf("src" to "season", "ok" to episodes.isNotEmpty(), "ms" to EngineStats.now() - started))
        return episodes
    }

    private fun save(key: String, entry: RatingsCache.PosterEntry) {
        cache().putPoster(key, entry)
        saves.trySend(Unit)
    }

    private suspend fun omdb(imdbId: String): TitleRatings? =
        // The key is in the URL: never log it (the URL isn't logged anywhere, and errors are swallowed whole).
        body("https://www.omdbapi.com/?apikey=$omdbKey&i=$imdbId")?.let(RatingsParser::omdb)

    private suspend fun cinemeta(imdbId: String, type: MediaType): Double? {
        val kind = if (type == MediaType.TV) "series" else "movie"
        return body("https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json")?.let(RatingsParser::cinemetaImdb)
    }

    private suspend fun body(url: String): String? = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }
    }

    private fun write() {
        val text = loaded?.encode() ?: return
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(text.toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w("Ratings", "cache save failed: ${it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 3_000L
        const val TOTAL_TIMEOUT_MS = 12_000L
    }
}
