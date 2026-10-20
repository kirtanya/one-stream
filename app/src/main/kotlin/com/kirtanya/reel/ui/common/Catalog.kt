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
}

/** Null in the app (screens use TMDB); renders provide fixed data. */
val LocalCatalog = staticCompositionLocalOf<Catalog?> { null }

@Composable
fun rememberCatalog(): Catalog {
    val override = LocalCatalog.current
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    return override ?: remember(tmdb) { TmdbCatalog(tmdb) }
}

private class TmdbCatalog(private val tmdb: TmdbRepository) : Catalog {
    override suspend fun home() = tmdb.home()
    override suspend fun onService(providerId: Int) = tmdb.onService(providerId)
    override suspend fun search(query: String) = tmdb.search(query)
    override suspend fun details(ref: TitleRef) = tmdb.details(ref)
    override suspend fun season(tvId: Int, season: Int) = tmdb.season(tvId, season)
    override suspend fun logo(ref: TitleRef) = tmdb.logo(ref)
    override suspend fun genres(type: MediaType) = tmdb.genres(type)
    override suspend fun byGenre(type: MediaType, genreId: Int, page: Int) = tmdb.byGenre(type, genreId, page)
    override suspend fun person(id: Int) = tmdb.person(id)
}
