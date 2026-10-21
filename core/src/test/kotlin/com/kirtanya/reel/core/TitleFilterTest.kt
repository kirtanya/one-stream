package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.Era
import com.kirtanya.reel.core.browse.MinRating
import com.kirtanya.reel.core.browse.TitleFilter
import com.kirtanya.reel.core.browse.TitleSort
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleFilterTest {
    private fun t(id: Int, title: String, year: Int?, rating: Double?) =
        TitleSummary(TitleRef(MediaType.MOVIE, id), title, year, null, null, rating, "")

    private val items = listOf(t(1, "The Zebra", 2021, 7.5), t(2, "Apple", 2005, 8.4), t(3, "Mango", null, null), t(4, "Banana", 1990, 6.0))

    @Test fun defaultKeepsTmdbOrder() = assertEquals(listOf(1, 2, 3, 4), TitleFilter().apply(items).map { it.ref.id })

    @Test fun minRatingDropsUnrated() = assertEquals(listOf(1, 2), TitleFilter(rating = MinRating.Seven).apply(items).map { it.ref.id })

    @Test fun eraNeedsAYear() {
        assertEquals(listOf(2), TitleFilter(era = Era.Noughties).apply(items).map { it.ref.id })
        assertEquals(listOf(4), TitleFilter(era = Era.Older).apply(items).map { it.ref.id })
    }

    @Test fun topRatedAndNewestPutMissingLast() {
        assertEquals(listOf(2, 1, 4, 3), TitleFilter(sort = TitleSort.TopRated).apply(items).map { it.ref.id })
        assertEquals(listOf(1, 2, 4, 3), TitleFilter(sort = TitleSort.Newest).apply(items).map { it.ref.id })
    }

    @Test fun azIgnoresThe() = assertEquals(listOf(2, 4, 3, 1), TitleFilter(sort = TitleSort.Az).apply(items).map { it.ref.id })

    @Test fun discoverMapsDecadeEnds() {
        val d = TitleFilter(sort = TitleSort.Newest, rating = MinRating.Eight, era = Era.Older).toDiscover(MediaType.TV, 18)
        assertEquals(setOf(18), d.genres)
        assertEquals(null, d.yearFrom)
        assertEquals(1999, d.yearTo)
        assertEquals(8.0, d.minRating)
        assertEquals(com.kirtanya.reel.core.tmdb.DiscoverFilter.Sort.NEWEST, d.sort)
        val now = TitleFilter(era = Era.Now).toDiscover(MediaType.MOVIE, 1)
        assertEquals(2020, now.yearFrom)
        assertEquals(null, now.yearTo)
    }
}
