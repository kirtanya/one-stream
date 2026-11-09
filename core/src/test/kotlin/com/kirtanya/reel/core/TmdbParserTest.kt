package com.kirtanya.reel.core

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.TmdbPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbParserTest {
    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()

    @Test fun trendingKeepsMoviesAndShowsDropsPeople() {
        val list = TmdbParser.parseList(fixture("trending.json"))
        assertEquals(listOf(MediaType.TV, MediaType.MOVIE), list.map { it.ref.type })
        assertTrue(list.all { it.title.isNotBlank() })
    }

    @Test fun listWithoutMediaTypeUsesDefault() {
        val body = """{"results":[{"id":7,"title":"A Film","release_date":"2024-05-01","vote_average":0}]}"""
        val item = TmdbParser.parseList(body, MediaType.MOVIE).single()
        assertEquals(TitleRef(MediaType.MOVIE, 7), item.ref)
        assertEquals(2024, item.year)
        assertNull(item.rating) // 0 means "no votes", not a 0/10 rating
    }

    @Test fun movieDetails() {
        val d = TmdbParser.parseDetails(fixture("movie.json"), MediaType.MOVIE)
        assertEquals("Fight Club", d.summary.title)
        assertEquals(1999, d.summary.year)
        assertEquals(139, d.runtimeMin)
        assertEquals("tt0137523", d.imdbId)
        assertEquals("A", d.certification) // India first
        assertEquals(listOf("Netflix"), d.providers.stream.map { it.name })
        assertEquals(listOf("Stream", "Buy"), d.providers.groups.map { it.first })
        assertEquals(2, d.cast.size)
        assertTrue(d.seasons.isEmpty())
    }

    @Test fun showDetails() {
        val d = TmdbParser.parseDetails(fixture("tv.json"), MediaType.TV)
        assertEquals("Game of Thrones", d.summary.title)
        assertEquals(TitleRef(MediaType.TV, 1399), d.summary.ref)
        assertEquals(80, d.runtimeMin) // falls back to the last episode's runtime
        assertEquals(listOf(1, 2, 0), d.seasons.map { it.number }) // specials last
        assertEquals("A", d.certification)
        assertEquals("JioHotstar", d.providers.stream.first().name)
        assertEquals("tt0944947", d.imdbId)
        assertEquals(8 to 6, d.lastAired)
    }

    @Test fun originalTitleComesFromTheOriginalFields() {
        val movie = TmdbParser.parseDetails("""{"id":1,"title":"Mughal-E-Azam: The Great Mughal","original_title":"Mughal-E-Azam","original_language":"hi"}""", MediaType.MOVIE)
        assertEquals("Mughal-E-Azam", movie.originalTitle)
        assertEquals("hi", movie.originalLanguage)
        val show = TmdbParser.parseDetails("""{"id":2,"name":"Panchayat","original_name":"पंचायत"}""", MediaType.TV)
        assertEquals("पंचायत", show.originalTitle)
        assertNull(TmdbParser.parseDetails("""{"id":3,"title":"X","original_title":""}""", MediaType.MOVIE).originalTitle)
    }

    @Test fun missingRegionMeansNoProviders() {
        assertTrue(TmdbParser.parseDetails(fixture("tv.json"), MediaType.TV, region = "ZZ").providers.isEmpty)
    }

    @Test fun season() {
        val eps = TmdbParser.parseSeason(fixture("season.json"))
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals("Winter Is Coming", eps.first().name)
        assertEquals(1, eps.first().season)
        assertEquals(62, eps.first().runtimeMin)
    }

    @Test fun garbageIsEmptyNotACrash() {
        assertTrue(TmdbParser.parseList("""{"status_message":"nope"}""").isEmpty())
        assertTrue(TmdbParser.parseSeason("{}").isEmpty())
    }

    @Test fun paths() {
        assertEquals("search/multi?language=en-US&include_adult=false&page=1&query=the+matrix", TmdbPaths.search(" the matrix "))
        assertTrue(TmdbPaths.details(TitleRef(MediaType.TV, 1)).contains("content_ratings"))
        assertEquals("tv/1399/season/2?language=en-US", TmdbPaths.season(1399, 2))
        assertTrue(TmdbPaths.onService(MediaType.TV, 8).contains("with_watch_providers=8&") && TmdbPaths.onService(MediaType.TV, 8).startsWith("discover/tv?"))
    }

    @Test fun images() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", TmdbImages.poster("/a.jpg"))
        assertNull(TmdbImages.backdrop(null))
    }
}
