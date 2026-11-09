package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbPaths
import com.kirtanya.reel.core.tmdb.titlesInLanguage

/** One row of the Movies / Shows pages: its heading and the TMDB path that fills it. */
data class RowSpec(val title: String, val path: String)

/** The rows of the TV's Movies and Shows pages (Netflix-style: only films, or only shows). */
object BrowseRows {
    fun forType(type: MediaType, region: String = "IN"): List<RowSpec> {
        val films = type == MediaType.MOVIE
        val noun = if (films) "films" else "shows"
        fun genre(title: String, id: Int) = RowSpec(title, TmdbPaths.byGenre(type, id))
        return listOf(
            RowSpec("Trending $noun this week", TmdbPaths.trending(type)),
            RowSpec("Popular $noun", TmdbPaths.popular(type, region)),
            RowSpec("Top rated $noun", TmdbPaths.topRated(type, region)),
            RowSpec("Popular Hindi $noun", TmdbPaths.titlesInLanguage(type, "hi")),
        ) + if (films) listOf(
            genre("Action films", 28), genre("Comedy films", 35), genre("Thriller films", 53), genre("Romance films", 10749), genre("Sci-Fi films", 878),
        ) else listOf(
            genre("Drama shows", 18), genre("Comedy shows", 35), genre("Crime shows", 80), genre("Action & Adventure shows", 10759), genre("Sci-Fi & Fantasy shows", 10765),
        )
    }
}

/** Films and shows in one list. */
object Interleave {
    /** [a] and [b] alternating (a first), a title that is in both kept once; the longer list's tail follows. */
    fun alternate(a: List<TitleSummary>, b: List<TitleSummary>): List<TitleSummary> {
        val out = ArrayList<TitleSummary>(a.size + b.size)
        val seen = HashSet<TitleRef>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { if (seen.add(it.ref)) out.add(it) }
            b.getOrNull(i)?.let { if (seen.add(it.ref)) out.add(it) }
        }
        return out
    }
}
