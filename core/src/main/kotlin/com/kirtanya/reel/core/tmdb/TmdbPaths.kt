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

    fun search(query: String) = "search/multi?language=en-US&include_adult=false&query=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}"

    fun details(ref: TitleRef): String {
        val extras = when (ref.type) {
            MediaType.MOVIE -> "credits,external_ids,watch%2Fproviders,release_dates"
            MediaType.TV -> "credits,external_ids,watch%2Fproviders,content_ratings"
        }
        return "${ref.type.path}/${ref.id}?language=en-US&append_to_response=$extras"
    }

    fun season(tvId: Int, season: Int) = "tv/$tvId/season/$season?language=en-US"
}
