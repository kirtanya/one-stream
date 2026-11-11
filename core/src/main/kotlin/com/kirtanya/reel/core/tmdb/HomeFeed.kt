package com.kirtanya.reel.core.tmdb

import java.time.LocalDate

/** Settings → Home screen → Content: whose titles Home suggests. Stored by [key]. */
enum class ContentPreference(val key: String, val label: String, val language: String?) {
    Both("both", "Both", null),
    Hollywood("hollywood", "Hollywood (English)", "en"),
    Bollywood("bollywood", "Bollywood (Hindi)", "hi");

    companion object {
        /** An unknown or missing key is [Both]: Home as it always was. */
        fun fromKey(key: String?): ContentPreference = entries.firstOrNull { it.key == key } ?: Both
    }
}

/** One TMDB request behind a Home row; [type] is the default media type for answers without one (null = mixed endpoint). */
data class HomeRequest(val path: String, val type: MediaType?)

/** A Home row: [title] (also its id in the saved layout) and the requests whose answers are interleaved into it. */
data class HomeFeedRow(val title: String, val requests: List<HomeRequest>)

/**
 * Which TMDB requests fill Home's rows for a [ContentPreference].
 *
 * [ContentPreference.Both] is today's rows untouched. For one language the trending / popular endpoints can't be used:
 * they cannot filter by language, and filtering their 20 results afterwards leaves a Hindi row with one or two
 * posters (most of the week's trending is English). So each row becomes a discover query with
 * `with_original_language`, which TMDB filters before paging, so rows stay full. "Trending" becomes "most popular among
 * titles released / aired in a recent window"; the row titles stay the same so a saved order and hidden rows still apply
 * (only "Popular in India" is replaced, since it would be wrong for English; see DECISIONS).
 */
object HomeFeed {
    /** Films released and shows aired this recently count as "trending". */
    const val RECENT_DAYS = 90L

    fun rows(pref: ContentPreference, today: LocalDate = LocalDate.now(), region: String = "IN"): List<HomeFeedRow> {
        val lang = pref.language ?: return listOf(
            HomeFeedRow("Trending this week", listOf(HomeRequest(TmdbPaths.trending(null), null))),
            // Both keeps the Hindi rows (0.16.7); a single-language choice replaces them with its own rows below.
            HomeFeedRow("New Hindi releases", listOf(HomeRequest(TmdbPaths.newReleasesByLanguage(today), MediaType.MOVIE))),
            HomeFeedRow("Popular Hindi films", listOf(HomeRequest(TmdbPaths.discoverByLanguage(MediaType.MOVIE), MediaType.MOVIE))),
            HomeFeedRow("Hindi shows", listOf(HomeRequest(TmdbPaths.discoverByLanguage(MediaType.TV), MediaType.TV))),
            HomeFeedRow("Popular movies", listOf(HomeRequest(TmdbPaths.popular(MediaType.MOVIE, region), MediaType.MOVIE))),
            HomeFeedRow("Trending shows", listOf(HomeRequest(TmdbPaths.trending(MediaType.TV), MediaType.TV))),
            HomeFeedRow("Popular in India", listOf(HomeRequest(TmdbPaths.discoverFromCountry(MediaType.MOVIE, region), MediaType.MOVIE))),
            HomeFeedRow("Top rated shows", listOf(HomeRequest(TmdbPaths.topRated(MediaType.TV, region), MediaType.TV))),
        )
        val since = today.minusDays(RECENT_DAYS)
        // English has far more votes than Hindi: the bar for "top rated" differs so each row keeps real, well-known titles.
        val filmVotes = if (pref == ContentPreference.Hollywood) 2000 else 200
        val showVotes = if (pref == ContentPreference.Hollywood) 500 else 20
        fun recent(type: MediaType) = HomeRequest(TmdbPaths.discoverByLanguage(type, lang, "popularity.desc", minVotes = 10, from = since, to = today), type)
        fun film(sort: String, votes: Int) = HomeRequest(TmdbPaths.discoverByLanguage(MediaType.MOVIE, lang, sort, votes), MediaType.MOVIE)
        return listOf(
            HomeFeedRow("Trending this week", listOf(recent(MediaType.MOVIE), recent(MediaType.TV))),
            HomeFeedRow("Popular movies", listOf(film("popularity.desc", 100))),
            HomeFeedRow("Trending shows", listOf(recent(MediaType.TV))),
            HomeFeedRow("Top rated films", listOf(film("vote_average.desc", filmVotes))),
            HomeFeedRow("Top rated shows", listOf(HomeRequest(TmdbPaths.discoverByLanguage(MediaType.TV, lang, "vote_average.desc", showVotes), MediaType.TV))),
        )
    }

    /** [lists] merged one from each in turn (films and shows alternate), repeats dropped. */
    fun interleave(lists: List<List<TitleSummary>>): List<TitleSummary> =
        (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.ref }
}
