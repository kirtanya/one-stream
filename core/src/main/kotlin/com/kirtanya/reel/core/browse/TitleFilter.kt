package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.DiscoverFilter
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleSummary

/** How a grid of titles is ordered. Popular keeps TMDB's own order (it's already by popularity). */
enum class TitleSort(val label: String) { Popular("Popular"), TopRated("Top rated"), Newest("Newest"), Az("A–Z") }

/** Minimum TMDB rating. */
enum class MinRating(val label: String, val min: Double?) { Any("Any rating", null), Seven("★ 7+", 7.0), Eight("★ 8+", 8.0) }

/** Release decade. */
enum class Era(val label: String, val years: IntRange?) {
    Any("Any year", null),
    Now("2020s", 2020..2099),
    Tens("2010s", 2010..2019),
    Noughties("2000s", 2000..2009),
    Older("Before 2000", 0..1999),
}

/** The chips above a grid (genre, service, See all). Pure, so it's tested on the Mac. */
data class TitleFilter(
    val sort: TitleSort = TitleSort.Popular,
    val rating: MinRating = MinRating.Any,
    val era: Era = Era.Any,
) {
    val isDefault get() = this == TitleFilter()

    fun apply(items: List<TitleSummary>): List<TitleSummary> {
        val kept = items.filter { t ->
            (rating.min == null || (t.rating ?: 0.0) >= rating.min) &&
                (era.years == null || (t.year != null && t.year in era.years))
        }
        return when (sort) {
            TitleSort.Popular -> kept
            // Unrated / undated titles go last rather than first.
            TitleSort.TopRated -> kept.sortedByDescending { it.rating ?: -1.0 }
            TitleSort.Newest -> kept.sortedByDescending { it.year ?: Int.MIN_VALUE }
            TitleSort.Az -> kept.sortedBy { it.title.lowercase().removePrefix("the ") }
        }
    }

    /** The same choices asked of TMDB (genre grids page through discover, not just what's loaded). */
    fun toDiscover(type: MediaType, genreId: Int) = DiscoverFilter(
        type = type,
        genres = setOf(genreId),
        yearFrom = era.years?.first?.takeIf { it > 0 },
        yearTo = era.years?.last?.takeIf { it < 2099 },
        minRating = rating.min,
        sort = when (sort) {
            TitleSort.Popular -> DiscoverFilter.Sort.POPULAR
            TitleSort.TopRated -> DiscoverFilter.Sort.RATING
            TitleSort.Newest -> DiscoverFilter.Sort.NEWEST
            TitleSort.Az -> DiscoverFilter.Sort.TITLE
        },
    )
}
