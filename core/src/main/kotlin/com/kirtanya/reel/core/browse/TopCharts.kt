package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.ContentPreference
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary

/** What a chart ranks by. */
enum class ChartKind { Popular, TopRated }

/**
 * One of the four "Top charts" on the TV's Categories page: the 100 most popular or best rated films or shows.
 *
 * TMDB (our only catalogue) has no public view counts and IMDb has no free API (scraping it is against its terms), so
 * "most watched" is TMDB's own popularity score, which it builds from page views, watchlist / favourite adds and
 * ratings. "Top rated" is TMDB's vote average with a minimum vote count, so a title with five votes can't top it.
 */
data class TopChart(val kind: ChartKind, val type: MediaType) {
    val label: String
        get() = when (kind) {
            ChartKind.Popular -> if (type == MediaType.MOVIE) "Most popular movies" else "Most popular series"
            ChartKind.TopRated -> if (type == MediaType.MOVIE) "Top rated movies" else "Top rated series"
        }

    /** What a Categories tile says under the name. */
    val tileCaption: String get() = if (kind == ChartKind.Popular) "Top 100 · by popularity" else "Top 100 · by rating"

    /** The chart page's line under its title; names the content choice when it narrows the chart. */
    fun caption(pref: ContentPreference): String =
        "Top 100 · " + (if (kind == ChartKind.Popular) "TMDB popularity" else "TMDB rating") + (if (pref == ContentPreference.Both) "" else " · ${pref.label}")

    /** Saved-state key of its page. */
    val key: String get() = "chart:${kind.name.lowercase()}:${type.path}"
}

/** A chart entry with its place (1 = best). */
data class RankedTitle(val rank: Int, val title: TitleSummary)

/**
 * The Top charts' requests and how their pages become one ranked list.
 *
 * [PAGES] pages of 20 are asked together (a chart is one load, so every rank is final from the first frame). TMDB's
 * `/popular` and `/top_rated` can't filter by language, so a Hollywood or Bollywood choice (Settings → Home screen →
 * Content, the same one Home uses) swaps them for `/discover` with `with_original_language`, sorted the same way; the
 * vote floors keep unrated junk out of the rating charts, and are higher for English, which has far more votes.
 */
object TopCharts {
    const val PAGE_SIZE = 20
    const val PAGES = 5
    const val SIZE = PAGE_SIZE * PAGES

    val ALL: List<TopChart> = listOf(
        TopChart(ChartKind.Popular, MediaType.MOVIE),
        TopChart(ChartKind.Popular, MediaType.TV),
        TopChart(ChartKind.TopRated, MediaType.MOVIE),
        TopChart(ChartKind.TopRated, MediaType.TV),
    )

    /** TMDB request for [page] (from 1) of [chart] under [pref]. No region: these are world charts, not "popular in India". */
    fun path(chart: TopChart, pref: ContentPreference, page: Int): String {
        val language = pref.language
        val type = chart.type.path
        if (language == null) {
            val list = if (chart.kind == ChartKind.Popular) "popular" else "top_rated"
            return "$type/$list?language=en-US&page=$page"
        }
        val sort = if (chart.kind == ChartKind.Popular) "popularity.desc" else "vote_average.desc"
        return "discover/$type?language=en-US&include_adult=false&with_original_language=$language&sort_by=$sort" +
            "&vote_count.gte=${minVotes(chart, language)}&page=$page"
    }

    /** Votes a title needs to enter a language chart. */
    fun minVotes(chart: TopChart, language: String): Int {
        if (chart.kind == ChartKind.Popular) return 50
        val film = chart.type == MediaType.MOVIE
        return if (language == "en") (if (film) 2000 else 500) else (if (film) 200 else 50)
    }

    /** The requests for every page of [chart]: [PAGES] of them, in rank order. */
    fun paths(chart: TopChart, pref: ContentPreference): List<String> = (1..PAGES).map { path(chart, pref, it) }

    /**
     * The chart from the answers to [paths], in page order. Only the unbroken run of pages from the first counts: a page
     * that failed or came back empty ends the chart there, so no rank is ever handed to the wrong title. The first page
     * failing throws its error (the screen offers Try again). Repeats (a title that moved between pages while they loaded)
     * keep their better place; at most [SIZE] titles.
     */
    fun assemble(pages: List<Result<List<TitleSummary>>>): List<TitleSummary> {
        val run = ArrayList<List<TitleSummary>>()
        for ((i, page) in pages.withIndex()) {
            val failure = page.exceptionOrNull()
            if (failure != null && i == 0) throw failure
            val titles = page.getOrNull().orEmpty()
            if (titles.isEmpty()) break
            run.add(titles)
        }
        return run.flatten().distinctBy { it.ref }.take(SIZE)
    }

    /**
     * [chart] with ranks, leaving out [hidden] titles (their [TitleSummary.ref] strings; the user's "hide from
     * suggestions"). A hidden title keeps its number, so what is shown still says where each title really stands.
     */
    fun ranked(chart: List<TitleSummary>, hidden: Set<String>): List<RankedTitle> =
        chart.mapIndexed { i, t -> RankedTitle(i + 1, t) }.filter { it.title.ref.toString() !in hidden }
}
