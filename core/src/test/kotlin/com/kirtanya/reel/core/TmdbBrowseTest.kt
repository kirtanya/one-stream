package com.kirtanya.reel.core

import com.kirtanya.reel.core.tmdb.DiscoverFilter
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.EpisodeCalendar
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.TmdbPaths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbBrowseTest {
    @Test fun collectionOnDetailsAndItsPage(): Unit {
        val d = TmdbParser.parseDetails("""{"id":438631,"title":"Dune","belongs_to_collection":{"id":726871,"name":"Dune Collection","poster_path":"/p.jpg"}}""", MediaType.MOVIE)
        assertEquals(726871, d.collection?.id)
        val c = TmdbParser.parseCollection("""{"id":726871,"name":"Dune Collection","parts":[
            {"id":3,"title":"Dune: Part Three","release_date":""},
            {"id":2,"title":"Dune: Part Two","release_date":"2024-02-27"},
            {"id":1,"title":"Dune","release_date":"2021-09-15"}]}""")
        assertEquals(listOf(1, 2, 3), c.parts.map { it.ref.id })
    }

    @Test fun discoverPath(): Unit {
        val p = TmdbPaths.discover(
            DiscoverFilter(MediaType.TV, genres = setOf(80, 18), yearFrom = 2010, yearTo = 2020, minRating = 7.5, language = "ko", sort = DiscoverFilter.Sort.RATING),
            page = 2,
        )
        assertTrue("discover/tv?" in p && "page=2" in p && "sort_by=vote_average.desc" in p && "vote_count.gte=200" in p)
        assertTrue("with_genres=18,80" in p && "first_air_date.gte=2010-01-01" in p && "first_air_date.lte=2020-12-31" in p)
        assertTrue("vote_average.gte=7.5" in p && "with_original_language=ko" in p)
        val newest = TmdbPaths.discover(DiscoverFilter(MediaType.MOVIE, sort = DiscoverFilter.Sort.NEWEST), today = LocalDate.of(2026, 10, 22))
        assertTrue("primary_release_date.lte=2026-10-22" in newest)
    }

    @Test fun calendarFromLastNextAndSeason(): Unit {
        val d = TmdbParser.parseDetails("""{"id":1,"name":"Show","status":"Returning Series",
            "last_episode_to_air":{"season_number":2,"episode_number":3,"name":"Three","air_date":"2026-10-18"},
            "next_episode_to_air":{"season_number":2,"episode_number":4,"name":"Four","air_date":"2026-10-25"}}""", MediaType.TV)
        assertEquals(2, EpisodeCalendar.seasonToFetch(d))
        val season = listOf(
            Episode(2, 4, "Four", "", null, "2026-10-25", null),
            Episode(2, 5, "Five", "", null, "2026-11-01", null),
            Episode(2, 6, "Six", "", null, "2026-11-30", null),
        )
        val e = EpisodeCalendar.entries(listOf(d), mapOf(1 to season), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 5))
        assertEquals(listOf(3, 4, 5), e.map { it.episode.episode })
        assertFalse(EpisodeCalendar.isFollowable(d.copy(status = "Ended")))
    }
}
