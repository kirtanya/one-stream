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

    /** Popular titles streaming on one service in [region] (TMDB provider id, e.g. 8 = Netflix). */
    fun onService(type: MediaType, providerId: Int, region: String = "IN") =
        "discover/${type.path}?language=en-US&sort_by=popularity.desc&watch_region=$region&with_watch_providers=$providerId&include_adult=false"

    fun search(query: String) = "search/multi?language=en-US&include_adult=false&query=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}"

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

    fun person(id: Int) = "person/$id?language=en-US&append_to_response=combined_credits"

    private const val EXTRAS = "images,videos,recommendations,similar"
}
