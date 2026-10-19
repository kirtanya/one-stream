package com.kirtanya.reel.core.tmdb

enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }

/** Identifies a film or show on TMDB. */
data class TitleRef(val type: MediaType, val id: Int) {
    /** For deep links and storage, e.g. "movie:550". */
    override fun toString() = "${type.path}:$id"
}

/** What a poster card needs. */
data class TitleSummary(
    val ref: TitleRef,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val overview: String,
)

data class CastMember(val name: String, val character: String?, val profilePath: String?)

data class Provider(val id: Int, val name: String, val logoPath: String?)

/** Where a title can be watched in one country (data from JustWatch via TMDB). */
data class WatchProviders(
    val link: String?,
    val stream: List<Provider> = emptyList(),
    val free: List<Provider> = emptyList(),
    val ads: List<Provider> = emptyList(),
    val rent: List<Provider> = emptyList(),
    val buy: List<Provider> = emptyList(),
) {
    val isEmpty get() = stream.isEmpty() && free.isEmpty() && ads.isEmpty() && rent.isEmpty() && buy.isEmpty()

    /** Groups in display order, skipping empty ones. */
    val groups: List<Pair<String, List<Provider>>>
        get() = listOf("Stream" to stream, "Free" to free + ads, "Rent" to rent, "Buy" to buy).filter { it.second.isNotEmpty() }

    companion object {
        val NONE = WatchProviders(link = null)
    }
}

data class SeasonSummary(val number: Int, val name: String, val episodeCount: Int, val posterPath: String?, val year: Int?)

data class TitleDetails(
    val summary: TitleSummary,
    val tagline: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val certification: String?,
    val cast: List<CastMember>,
    /** Regular seasons only (specials last, if any). */
    val seasons: List<SeasonSummary>,
    val imdbId: String?,
    val providers: WatchProviders,
)

data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val airDate: String?,
    val runtimeMin: Int?,
)

/** TMDB image CDN URLs. Sizes: posters w185/w342/w500, backdrops w780/w1280, logos w92/w154. */
object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String?, size: String = "w342") = path?.let { BASE + size + it }
    fun backdrop(path: String?, size: String = "w1280") = path?.let { BASE + size + it }
    fun logo(path: String?, size: String = "w92") = path?.let { BASE + size + it }
    fun profile(path: String?, size: String = "w185") = path?.let { BASE + size + it }
    fun still(path: String?, size: String = "w300") = path?.let { BASE + size + it }
}
