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

data class CastMember(val name: String, val character: String?, val profilePath: String?, val id: Int? = null)

data class Genre(val id: Int, val name: String)

/** A YouTube video from TMDB (trailers first). Open [youtubeUrl] with an ACTION_VIEW intent. */
data class Video(val key: String, val name: String, val type: String, val official: Boolean) {
    val youtubeUrl get() = "https://www.youtube.com/watch?v=$key"
    val thumbnailUrl get() = "https://img.youtube.com/vi/$key/hqdefault.jpg"
    val isTrailer get() = type == "Trailer" || type == "Teaser"
}

/** A person's page: photo, bio and what they're known for (most popular first). */
data class PersonDetails(
    val id: Int,
    val name: String,
    val profilePath: String?,
    val biography: String,
    val knownFor: String?,
    val credits: List<TitleSummary>,
)

data class Provider(val id: Int, val name: String, val logoPath: String?) {
    companion object {
        /** Streaming services shown on Home ("On your services"), India first. Logos from TMDB. */
        val FEATURED = listOf(
            Provider(8, "Netflix", "/rK1KljqmbvO9HQa1PBFLILWah72.png"),
            Provider(119, "Prime Video", "/gMZdpavHmxFNnLpMHwVxfqeux2g.png"),
            Provider(2336, "JioHotstar", "/ledoS6EgdjTNq8F1e6wubUQer18.png"),
            Provider(350, "Apple TV", "/9icYBfYFcwgCbky5VdGUIKJ4C5i.png"),
            Provider(237, "Sony LIV", "/coM4QWbmIOa0xJ5cGR9BRmoV25B.png"),
            Provider(232, "Zee5", "/uQvhdtB8skccsGHmvKi3y5bqBsX.png"),
            Provider(283, "Crunchyroll", "/uFL3c4Cq8M6WoLymlC5Y8bmGytV.png"),
            Provider(11, "MUBI", "/k7iSlvgWzZuO4zU5PcBjhABMuia.png"),
            Provider(309, "Sun NXT", "/1T9VcLknesOZZen9ZgLfcDdm5Ys.png"),
        )
    }
}

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
    /** Shows: the latest episode that has aired (TMDB `last_episode_to_air`), so Up next never points past it. */
    val lastAired: Pair<Int, Int>? = null,
    /** The title's logo artwork (transparent PNG, English or textless) to show instead of plain text. */
    val logoPath: String? = null,
    /** YouTube videos, trailers first. */
    val videos: List<Video> = emptyList(),
    /** "More like this": TMDB recommendations, else similar titles. */
    val recommendations: List<TitleSummary> = emptyList(),
    /** [genres] with ids, for "browse this genre". */
    val genreList: List<Genre> = emptyList(),
    /** Extra textless backdrops (for a slideshow behind the title). */
    val backdrops: List<String> = emptyList(),
) {
    val trailer: Video? get() = videos.firstOrNull { it.isTrailer }
}

data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val airDate: String?,
    val runtimeMin: Int?,
)

/** TMDB image CDN URLs. Sizes: posters w185/w342/w500, backdrops w780/w1280, provider logos w92/w154, title logos w300/w500. */
object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String?, size: String = "w342") = path?.let { BASE + size + it }
    fun backdrop(path: String?, size: String = "w1280") = path?.let { BASE + size + it }
    fun logo(path: String?, size: String = "w92") = path?.let { BASE + size + it }
    fun profile(path: String?, size: String = "w185") = path?.let { BASE + size + it }
    fun still(path: String?, size: String = "w300") = path?.let { BASE + size + it }
    /** A title's logo artwork ([TitleDetails.logoPath]). */
    fun titleLogo(path: String?, size: String = "w500") = path?.let { BASE + size + it }
}
