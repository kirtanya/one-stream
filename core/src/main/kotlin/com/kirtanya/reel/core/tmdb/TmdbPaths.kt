package com.kirtanya.reel.core.tmdb

import java.net.URLEncoder

/** TMDB v3 request paths (relative to https://api.themoviedb.org/3/). */
object TmdbPaths {
    fun trending(type: MediaType?, window: String = "week") = "trending/${type?.path ?: "all"}/$window?language=en-US"
    fun popular(type: MediaType, region: String = "IN") = "${type.path}/popular?language=en-US&region=$region"
    fun topRated(type: MediaType, region: String = "IN") = "${type.path}/top_rated?language=en-US&region=$region"

    /** Popular titles made in a country, e.g. Indian films. */
    fun discoverFromCountry(type: MediaType, country: String = "IN") =
        "discover/${type.path}?language=en-US&sort_by=popularity.desc&with_origin_country=$country&include_adult=false"

    /** Popular titles by original language (not country), so Hindi shows up whatever it was co-produced with. The vote floor keeps unrated junk out. */
    fun discoverByLanguage(type: MediaType, language: String = "hi", minVotes: Int = 30, page: Int = 1) =
        "discover/${type.path}?language=en-US&sort_by=popularity.desc&with_original_language=$language&vote_count.gte=$minVotes&include_adult=false&page=$page"

    /**
     * Films in [language] released in the last [days] days, up to [today] (no announced titles).
     * A low vote floor: new films have few votes, but zero-vote entries are mostly junk.
     */
    fun newReleasesByLanguage(
        today: java.time.LocalDate,
        language: String = "hi",
        days: Long = NEW_RELEASE_DAYS,
        minVotes: Int = 5,
    ) = "discover/movie?language=en-US&sort_by=popularity.desc&with_original_language=$language&vote_count.gte=$minVotes&include_adult=false" +
        "&primary_release_date.gte=${today.minusDays(days)}&primary_release_date.lte=$today"

    /** Popular titles streaming on one service in [region] (TMDB provider id, e.g. 8 = Netflix). */
    fun onService(type: MediaType, providerId: Int, region: String = "IN") =
        "discover/${type.path}?language=en-US&sort_by=popularity.desc&watch_region=$region&with_watch_providers=$providerId&include_adult=false"

    fun search(query: String, page: Int = 1) = "search/multi?language=en-US&include_adult=false&page=$page&query=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}"
    /** Well-known titles, most popular first (search's spelling fixes are checked against their names). */
    fun wellKnown(type: MediaType, page: Int) =
        "discover/${type.path}?language=en-US&include_adult=false&sort_by=popularity.desc&vote_count.gte=300&page=$page"

    /** Films or shows only, optionally in one year (artwork matching for raw files: core/artmatch). */
    fun searchTitle(type: MediaType, query: String, year: Int? = null): String {
        val yearParam = year?.let { if (type == MediaType.MOVIE) "&primary_release_year=$it" else "&first_air_date_year=$it" }.orEmpty()
        return "search/${type.path}?language=en-US&include_adult=false&query=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}$yearParam"
    }

    fun details(ref: TitleRef): String {
        val extras = when (ref.type) {
            MediaType.MOVIE -> "credits,external_ids,watch%2Fproviders,release_dates,$EXTRAS"
            MediaType.TV -> "credits,external_ids,watch%2Fproviders,content_ratings,$EXTRAS"
        }
        return "${ref.type.path}/${ref.id}?language=en-US&append_to_response=$extras&include_image_language=en,null&include_video_language=en,null"
    }

    fun season(tvId: Int, season: Int) = "tv/$tvId/season/$season?language=en-US"

    /** Logos and backdrops only (e.g. a logo for a Home hero without fetching full details). */
    fun images(ref: TitleRef) = "${ref.type.path}/${ref.id}/images?include_image_language=en,null"

    fun genres(type: MediaType) = "genre/${type.path}/list?language=en-US"

    /** Popular titles in one genre. */
    fun byGenre(type: MediaType, genreId: Int, page: Int = 1) =
        "discover/${type.path}?language=en-US&sort_by=popularity.desc&with_genres=$genreId&include_adult=false&vote_count.gte=50&page=$page"

    /** Looks a title up by its IMDb id (tt…): `movie_results` / `tv_results`. */
    fun find(imdbId: String) = "find/${URLEncoder.encode(imdbId.trim(), Charsets.UTF_8)}?external_source=imdb_id&language=en-US"

    fun collection(id: Int) = "collection/$id?language=en-US"

    /** Discover with the user's filters; [page] from 1. Rating sorts need enough votes to mean something. */
    fun discover(f: DiscoverFilter, page: Int = 1, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val movie = f.type == MediaType.MOVIE
        val dateField = if (movie) "primary_release_date" else "first_air_date"
        val sort = when (f.sort) {
            DiscoverFilter.Sort.POPULAR -> "popularity.desc"
            DiscoverFilter.Sort.RATING -> "vote_average.desc"
            DiscoverFilter.Sort.NEWEST -> "$dateField.desc"
            DiscoverFilter.Sort.TITLE -> if (movie) "original_title.asc" else "original_name.asc"
        }
        val minVotes = if (f.sort == DiscoverFilter.Sort.RATING || f.minRating != null) 200 else 20
        return buildString {
            append("discover/${f.type.path}?language=en-US&include_adult=false&page=$page&sort_by=$sort&vote_count.gte=$minVotes")
            if (f.genres.isNotEmpty()) append("&with_genres=" + f.genres.sorted().joinToString(","))
            f.yearFrom?.let { append("&$dateField.gte=$it-01-01") }
            f.yearTo?.let { append("&$dateField.lte=$it-12-31") }
            // Newest first shouldn't list announced titles that aren't out yet.
            if (f.sort == DiscoverFilter.Sort.NEWEST && f.yearTo == null) append("&$dateField.lte=$today")
            f.minRating?.let { append("&vote_average.gte=$it") }
            f.language?.let { append("&with_original_language=$it") }
        }
    }

    /** A title's IMDb id and other outside ids (posters' IMDb pill). */
    fun externalIds(ref: TitleRef) = "${ref.type.path}/${ref.id}/external_ids"

    /** The first page of user reviews (English; TMDB has few in other languages). */
    fun reviews(ref: TitleRef) = "${ref.type.path}/${ref.id}/reviews?language=en-US&page=1"

    fun person(id: Int) = "person/$id?language=en-US&append_to_response=combined_credits"

    const val NEW_RELEASE_DAYS = 60L

    private const val EXTRAS = "images,videos,recommendations,similar"
}

/** Popular titles whose original language is [code] ("hi", "ko"): the Languages of the TV Categories page and the Hindi rows of Movies / Shows. */
fun TmdbPaths.titlesInLanguage(type: MediaType, code: String, page: Int = 1) =
    "discover/${type.path}?language=en-US&sort_by=popularity.desc&with_original_language=$code&include_adult=false&vote_count.gte=20&page=$page"
