package com.kirtanya.reel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.PersonDetails
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.data.HomeRow
import com.kirtanya.reel.data.TmdbRepository

/**
 * Everything the screens read from TMDB. The app reads [TmdbRepository]; screen renders (tests)
 * provide fixed data through [LocalCatalog], so every state (loading, failed, empty, long names)
 * can be drawn without a network.
 */
interface Catalog {
    suspend fun home(): List<HomeRow>
    suspend fun onService(providerId: Int): List<TitleSummary>
    suspend fun search(query: String): List<TitleSummary>
    suspend fun details(ref: TitleRef): TitleDetails
    suspend fun season(tvId: Int, season: Int): List<Episode>
    /** A title's logo artwork path, or null. */
    suspend fun logo(ref: TitleRef): String?
    suspend fun genres(type: MediaType): List<Genre>
    suspend fun byGenre(type: MediaType, genreId: Int, page: Int = 1): List<TitleSummary>
    suspend fun person(id: Int): PersonDetails
    /** A title by IMDb id (launcher links); null when TMDB doesn't know it. */
    suspend fun findByImdb(imdbId: String, type: MediaType): TitleSummary?
    /** A film series' parts, in release order. Defaults keep render fakes short. */
    suspend fun collection(id: Int): com.kirtanya.reel.core.tmdb.CollectionDetails = throw UnsupportedOperationException()
    /** Discover with filters (genre grids with sort / rating / decade chips); 20 a page. */
    suspend fun discover(filter: com.kirtanya.reel.core.tmdb.DiscoverFilter, page: Int = 1): List<TitleSummary> = byGenre(filter.type, filter.genres.first(), page)
    /** TV Movies / Shows pages: the rows for films or for shows. Empty by default so render fakes stay short. */
    suspend fun browseRows(type: MediaType): List<HomeRow> = emptyList()
    /** Popular titles of [type] whose original language is [code] (TV Categories → Languages). */
    suspend fun byLanguage(type: MediaType, code: String, page: Int = 1): List<TitleSummary> = emptyList()
    /** Episodes of [shows] airing (or aired) between [from] and [to]. */
    suspend fun calendar(shows: List<TitleRef>, from: java.time.LocalDate, to: java.time.LocalDate): List<com.kirtanya.reel.core.tmdb.CalendarEntry> = emptyList()
    /** TMDB user reviews (first page + total). */
    suspend fun reviews(ref: TitleRef): com.kirtanya.reel.core.tmdb.Reviews = com.kirtanya.reel.core.tmdb.Reviews.NONE
    /** A title page's IMDb / Rotten Tomatoes / Metascore; null when unknown or unreachable. */
    suspend fun titleRatings(d: TitleDetails): com.kirtanya.reel.core.ratings.TitleRatings? = null
    /** A show's season: episode number → that episode's IMDb rating (OMDb); empty when unknown. */
    suspend fun episodeImdb(d: TitleDetails, season: Int): Map<Int, Double> = emptyMap()
    /** A poster's IMDb rating as already known (no network), so the pill can be there on the first frame. */
    fun cachedImdb(ref: TitleRef): Double? = null
    /** A poster's IMDb rating, fetched when not cached (a few at a time). */
    suspend fun posterImdb(ref: TitleRef): Double? = null
}

/** Null in the app (screens use TMDB); renders provide fixed data. */
val LocalCatalog = staticCompositionLocalOf<Catalog?> { null }

@Composable
fun rememberCatalog(): Catalog {
    val override = LocalCatalog.current
    val app = ReelApp.get(LocalContext.current)
    return override ?: remember(app) { TmdbCatalog(app.tmdb, lazy { app.ratings }, app) }
}

private class TmdbCatalog(private val tmdb: TmdbRepository, ratings: Lazy<com.kirtanya.reel.data.RatingsRepository>, private val app: ReelApp) : Catalog {
    private val ratings by ratings
    override suspend fun reviews(ref: TitleRef) = tmdb.reviews(ref)
    override suspend fun titleRatings(d: TitleDetails) = ratings.titleRatings(d.summary.ref, d.imdbId)
    override fun cachedImdb(ref: TitleRef) = ratings.cachedImdb(ref)
    override suspend fun episodeImdb(d: TitleDetails, season: Int) = ratings.episodeRatings(d.imdbId, season)
    override suspend fun posterImdb(ref: TitleRef) = ratings.posterImdb(ref)
    override suspend fun home() = tmdb.home()
    override suspend fun onService(providerId: Int) = tmdb.onService(providerId)
    override suspend fun search(query: String) = tmdb.search(query) { tmdb.taste(tasteRefs()) }

    /** The viewer's taste: the 15 titles most recently watched, then saved to the watchlist. */
    private fun tasteRefs(): List<com.kirtanya.reel.core.tmdb.TitleRef> {
        val parse = com.kirtanya.reel.core.library.Watchlist::parseRef
        val watched = app.history.entries.value.sortedByDescending { it.updatedAt }
            .mapNotNull { parse(it.playback.key.split(":").take(2).joinToString(":")) }
        val saved = app.watchlist.all.value.filterNot { it.removed }.sortedByDescending { it.changedAt }.mapNotNull { it.ref }
        return (watched + saved).distinct().take(15)
    }
    override suspend fun details(ref: TitleRef) = tmdb.details(ref)
    override suspend fun season(tvId: Int, season: Int) = tmdb.season(tvId, season)
    override suspend fun logo(ref: TitleRef) = tmdb.logo(ref)
    override suspend fun genres(type: MediaType) = tmdb.genres(type)
    override suspend fun byGenre(type: MediaType, genreId: Int, page: Int) = tmdb.byGenre(type, genreId, page)
    override suspend fun person(id: Int) = tmdb.person(id)
    override suspend fun findByImdb(imdbId: String, type: MediaType) = tmdb.findByImdb(imdbId, type)
    override suspend fun collection(id: Int) = tmdb.collection(id)
    override suspend fun discover(filter: com.kirtanya.reel.core.tmdb.DiscoverFilter, page: Int) = tmdb.discover(filter, page)
    override suspend fun browseRows(type: MediaType) = tmdb.browseRows(type)
    override suspend fun byLanguage(type: MediaType, code: String, page: Int) = tmdb.titlesInLanguage(type, code, page)
    override suspend fun calendar(shows: List<TitleRef>, from: java.time.LocalDate, to: java.time.LocalDate) = tmdb.calendar(shows, from, to)
}
