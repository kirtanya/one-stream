package com.kirtanya.reel.core

import com.kirtanya.reel.core.ratings.MetaBand
import com.kirtanya.reel.core.ratings.RatingRules
import com.kirtanya.reel.core.ratings.RatingsCache
import com.kirtanya.reel.core.ratings.RatingsParser
import com.kirtanya.reel.core.ratings.TitleRatings
import com.kirtanya.reel.core.ratings.Tomato
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.ReviewParser
import com.kirtanya.reel.core.tmdb.ReviewText
import com.kirtanya.reel.core.tmdb.SeasonSummary
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleEyebrow
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbPaths
import com.kirtanya.reel.core.tmdb.WatchProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingsTest {
    // ─── OMDb ──────────────────────────────────────────────────────────────────────────────

    @Test fun omdbAllThree() {
        val body = """{"Title":"Inception","imdbRating":"8.8","Metascore":"74","Ratings":[
            {"Source":"Internet Movie Database","Value":"8.8/10"},{"Source":"Rotten Tomatoes","Value":"86%"},
            {"Source":"Metacritic","Value":"74/100"}],"Response":"True"}"""
        assertEquals(TitleRatings(8.8, 86, 74), RatingsParser.omdb(body))
    }

    @Test fun omdbSeriesHasOnlyImdb() {
        val body = """{"imdbRating":"8.3","Metascore":"N/A","Ratings":[{"Source":"Internet Movie Database","Value":"8.3/10"}],"Response":"True"}"""
        assertEquals(TitleRatings(8.3, null, null), RatingsParser.omdb(body))
    }

    @Test fun omdbFallsBackToRatingsListAndRejectsJunk() {
        val body = """{"imdbRating":"N/A","Metascore":"N/A","Ratings":[{"Source":"Internet Movie Database","Value":"7.1/10"},
            {"Source":"Metacritic","Value":"55/100"},{"Source":"Rotten Tomatoes","Value":"140%"}],"Response":"True"}"""
        assertEquals(TitleRatings(7.1, null, 55), RatingsParser.omdb(body))
    }

    @Test fun omdbUnknownOrBroken() {
        assertNull(RatingsParser.omdb("""{"Response":"False","Error":"Incorrect IMDb ID."}"""))
        assertNull(RatingsParser.omdb("""{"Response":"False","Error":"Request limit reached!"}"""))
        assertNull(RatingsParser.omdb("<html>gateway</html>"))
        assertNull(RatingsParser.omdb(""))
    }

    // ─── Cinemeta + TMDB external ids ──────────────────────────────────────────────────────

    @Test fun cinemetaRating() {
        assertEquals(8.8, RatingsParser.cinemetaImdb("""{"meta":{"imdb_id":"tt1375666","imdbRating":"8.8"}}"""))
        assertEquals(7.0, RatingsParser.cinemetaImdb("""{"meta":{"imdbRating":7}}"""))
        assertNull(RatingsParser.cinemetaImdb("""{"meta":{"imdbRating":""}}"""))
        assertNull(RatingsParser.cinemetaImdb("""{"meta":{"imdbRating":"0.0"}}"""))
        assertNull(RatingsParser.cinemetaImdb("""{"meta":null}"""))
        assertNull(RatingsParser.cinemetaImdb("not json"))
    }

    @Test fun externalIds() {
        assertEquals("tt1375666", RatingsParser.imdbId("""{"id":27205,"imdb_id":"tt1375666","wikidata_id":"Q25188"}"""))
        assertNull(RatingsParser.imdbId("""{"id":1,"imdb_id":null}"""))
        assertNull(RatingsParser.imdbId("""{"id":1,"imdb_id":""}"""))
        assertNull(RatingsParser.imdbId("""{"id":1,"imdb_id":"nm0000123"}"""))
        assertEquals("tv/1399/external_ids", TmdbPaths.externalIds(TitleRef(MediaType.TV, 1399)))
        assertEquals("movie/550/reviews?language=en-US&page=1", TmdbPaths.reviews(TitleRef(MediaType.MOVIE, 550)))
    }

    // ─── Thresholds ────────────────────────────────────────────────────────────────────────

    @Test fun freshFromSixty() {
        assertEquals(Tomato.FRESH, RatingRules.tomato(60))
        assertEquals(Tomato.FRESH, RatingRules.tomato(100))
        assertEquals(Tomato.ROTTEN, RatingRules.tomato(59))
        assertEquals(Tomato.ROTTEN, RatingRules.tomato(0))
    }

    @Test fun metascoreBands() {
        assertEquals(MetaBand.FAVORABLE, RatingRules.metaBand(61))
        assertEquals(MetaBand.FAVORABLE, RatingRules.metaBand(100))
        assertEquals(MetaBand.MIXED, RatingRules.metaBand(60))
        assertEquals(MetaBand.MIXED, RatingRules.metaBand(40))
        assertEquals(MetaBand.UNFAVORABLE, RatingRules.metaBand(39))
        assertEquals(MetaBand.UNFAVORABLE, RatingRules.metaBand(0))
    }

    @Test fun imdbLabelAlwaysOneDecimalWithADot() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("8.0", RatingRules.imdbLabel(8.0))
            assertEquals("7.4", RatingRules.imdbLabel(7.36))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ─── Cache ─────────────────────────────────────────────────────────────────────────────

    @Test fun freshness() {
        val day = RatingsCache.DAY_MS
        val hit = RatingsCache.PosterEntry("tt1", 8.1, at = 0)
        val miss = RatingsCache.PosterEntry("tt1", null, at = 0)
        assertTrue(RatingsCache.isFresh(hit, 6 * day))
        assertFalse(RatingsCache.isFresh(hit, 7 * day))
        assertTrue(RatingsCache.isFresh(miss, day))
        assertFalse(RatingsCache.isFresh(miss, 2 * day))
        // Clock went backwards: refetch rather than trust it.
        assertFalse(RatingsCache.isFresh(RatingsCache.PosterEntry("tt1", 8.1, at = 10 * day), 9 * day))
        assertTrue(RatingsCache.isFresh(RatingsCache.FullEntry(TitleRatings(8.0, 90, 80), at = 0), 6 * day))
        assertFalse(RatingsCache.isFresh(RatingsCache.FullEntry(TitleRatings(), at = 0), day))
        assertFalse(RatingsCache.isFresh(RatingsCache.FullEntry(null, at = 0), day))
        // OMDb failed, Cinemeta's IMDb stood in: good for a day, not a week.
        assertTrue(RatingsCache.isFresh(RatingsCache.FullEntry(TitleRatings(8.0), at = 0, complete = false), day - 1))
        assertFalse(RatingsCache.isFresh(RatingsCache.FullEntry(TitleRatings(8.0), at = 0, complete = false), day))
    }

    @Test fun cacheRoundTripsAndTrimsOldest() {
        val c = RatingsCache()
        c.putPoster("movie:1", RatingsCache.PosterEntry("tt0000001", 7.5, at = 5))
        c.putFull("tt0000001", RatingsCache.FullEntry(TitleRatings(7.5, 81, 66), at = 6))
        val back = RatingsCache.decode(c.encode())
        assertEquals(7.5, back.poster("movie:1")?.imdb)
        assertEquals(TitleRatings(7.5, 81, 66), back.full("tt0000001")?.ratings)
        repeat(RatingsCache.MAX_POSTERS + 5) { back.putPoster("tv:$it", RatingsCache.PosterEntry(null, null, at = 1)) }
        assertNull(back.poster("movie:1"), "oldest dropped first")
        assertEquals(1L, back.poster("tv:${RatingsCache.MAX_POSTERS + 4}")?.at)
    }

    @Test fun brokenCacheFileStartsEmpty() {
        assertNull(RatingsCache.decode("{not json").poster("movie:1"))
        assertNull(RatingsCache.decode(null).poster("movie:1"))
    }

    // ─── Reviews ───────────────────────────────────────────────────────────────────────────

    @Test fun reviewsParse() {
        val body = """{"id":27205,"page":1,"results":[
            {"author":"John Chard","author_details":{"name":"","username":"John Chard","rating":9.0},
             "content":"Your mind is the scene of the crime.\r\n\r\n**Nolan** builds a _maze_.","created_at":"2017-02-04T13:55:46.153Z"},
            {"author":"","author_details":{"username":"msbreviews","rating":null},"content":"Rare <em>and</em> coherent.","created_at":null},
            {"author":"Nobody","author_details":{},"content":"   "}],
            "total_pages":1,"total_results":12}"""
        val r = ReviewParser.parse(body)
        assertEquals(12, r.total)
        assertEquals(2, r.items.size, "empty reviews dropped")
        assertEquals("John Chard", r.items[0].author)
        assertEquals(9.0, r.items[0].rating)
        assertEquals("★ 9", r.items[0].ratingLabel)
        assertEquals("Your mind is the scene of the crime.\n\nNolan builds a maze.", r.items[0].content)
        assertEquals("msbreviews", r.items[1].author)
        assertNull(r.items[1].rating)
        assertNull(r.items[1].ratingLabel)
        assertEquals("Rare and coherent.", r.items[1].content)
        assertEquals("M", r.items[1].initial)
        assertEquals(0, ReviewParser.parse("""{"results":[]}""").total)
        assertEquals(0, ReviewParser.parse("oops").items.size)
    }

    @Test fun reviewText() {
        assertEquals("Line one\nLine two", ReviewText.plain("Line one<br />Line two"))
        assertEquals("Tom & Jerry \"rule\" — it's <fine>", ReviewText.plain("Tom &amp; Jerry &quot;rule&quot; &#8212; it&#39;s &lt;fine&gt;"))
        assertEquals("See the trailer here.", ReviewText.plain("See the [trailer](https://youtu.be/x) here.![poster](http://x/y.jpg)"))
        assertEquals("Verdict\nA bold film.\nQuote", ReviewText.plain("## Verdict\nA **bold** film.\n> Quote"))
        assertEquals("snake_case_names stay, 2*3*4 too", ReviewText.plain("snake_case_names stay, 2*3*4 too"))
        assertEquals("one\n\ntwo", ReviewText.plain("one\n\n\n\n\ntwo"))
        assertEquals("Para one\n\nPara two", ReviewText.plain("<p>Para one</p><p>Para two</p>"))
        assertEquals("code and struck", ReviewText.plain("`code` and ~~struck~~"))
        assertEquals("a\n\nb", ReviewText.plain("a\n\n---\n\nb").replace(Regex("\n{3,}"), "\n\n"))
        assertEquals("?", com.kirtanya.reel.core.tmdb.Review("—", null, "x", null).initial)
        assertEquals("★ 7.5", com.kirtanya.reel.core.tmdb.Review("a", 7.5, "x", null).ratingLabel)
    }

    // ─── Eyebrow ───────────────────────────────────────────────────────────────────────────

    private fun details(type: MediaType, runtime: Int?, seasons: List<SeasonSummary> = emptyList()) = TitleDetails(
        summary = TitleSummary(TitleRef(type, 1), "X", 2010, null, null, null, ""),
        tagline = null, runtimeMin = runtime, genres = listOf("Science Fiction", "Action", "Drama"), certification = "UA",
        cast = emptyList(), seasons = seasons, imdbId = null, providers = WatchProviders.NONE,
    )

    @Test fun eyebrow() {
        assertEquals(listOf("FILM", "2010", "2h 28m", "UA", "SCIENCE FICTION, ACTION"), TitleEyebrow.parts(details(MediaType.MOVIE, 148)))
        assertEquals(listOf("FILM", "2010", "45m", "UA", "SCIENCE FICTION"), TitleEyebrow.parts(details(MediaType.MOVIE, 45), genres = 1))
        val seasons = listOf(SeasonSummary(0, "Specials", 2, null, null), SeasonSummary(1, "S1", 8, null, 2016), SeasonSummary(2, "S2", 8, null, 2018))
        assertEquals(listOf("SERIES", "2010", "2 seasons", "UA", "SCIENCE FICTION, ACTION"), TitleEyebrow.parts(details(MediaType.TV, 58, seasons)))
        val ended = details(MediaType.TV, 58, seasons).copy(status = "Ended", lastAiredEpisode = com.kirtanya.reel.core.tmdb.AiringEpisode(2, 8, "End", "2018-05-01"))
        assertEquals("2010–2018", TitleEyebrow.years(ended))
        assertEquals("2010–present", TitleEyebrow.years(ended.copy(status = "Returning Series")))
        assertEquals("2010", TitleEyebrow.years(ended.copy(status = "Canceled", lastAiredEpisode = null)))
        assertEquals("2010", TitleEyebrow.years(ended.copy(status = null)))
    }

    @Test fun omdbSeasonMatchesByEpisodeNumber() {
        val body = """{"Title":"Breaking Bad","Season":"5","Episodes":[
            {"Title":"Live Free or Die","Episode":"1","imdbRating":"9.2"},
            {"Title":"Madrigal","Episode":"2","imdbRating":"N/A"},
            {"Title":"Dead Freight","Episode":"5","imdbRating":"9.7"},
            {"Title":"Broken","Episode":"x","imdbRating":"8.0"}],"Response":"True"}"""
        assertEquals(mapOf(1 to 9.2, 5 to 9.7), RatingsParser.omdbSeason(body))
        assertEquals(emptyMap(), RatingsParser.omdbSeason("""{"Response":"False","Error":"Series or season not found!"}"""))
        assertEquals(emptyMap(), RatingsParser.omdbSeason("nope"))
    }

    @Test fun seasonCache() {
        val c = RatingsCache()
        c.putSeason("tt0903747", 5, RatingsCache.SeasonEntry(mapOf(1 to 9.2), at = 0))
        val back = RatingsCache.decode(c.encode())
        assertEquals(mapOf(1 to 9.2), back.season("tt0903747", 5)?.episodes)
        assertNull(back.season("tt0903747", 4))
        val day = RatingsCache.DAY_MS
        assertTrue(RatingsCache.isFresh(back.season("tt0903747", 5)!!, 6 * day))
        assertFalse(RatingsCache.isFresh(RatingsCache.SeasonEntry(emptyMap(), at = 0), day))
        assertFalse(RatingsCache.isFresh(RatingsCache.SeasonEntry(mapOf(1 to 9.0), at = 0, ok = false), day))
    }

    @Test fun episodeLines() {
        val e = com.kirtanya.reel.core.tmdb.Episode(1, 9, "Greatness Achieved", "", null, "2016-03-12", 52)
        assertEquals("E9 · Greatness Achieved", com.kirtanya.reel.core.tmdb.EpisodeFacts.title(e))
        assertEquals("52m · 12 Mar 2016", com.kirtanya.reel.core.tmdb.EpisodeFacts.facts(e))
        assertEquals("1h 5m", com.kirtanya.reel.core.tmdb.EpisodeFacts.facts(e.copy(runtimeMin = 65, airDate = null)))
        assertEquals("2016", com.kirtanya.reel.core.tmdb.EpisodeFacts.facts(e.copy(runtimeMin = null, airDate = "2016-xx")))
        assertEquals("", com.kirtanya.reel.core.tmdb.EpisodeFacts.facts(e.copy(runtimeMin = 0, airDate = null)))
    }
}
