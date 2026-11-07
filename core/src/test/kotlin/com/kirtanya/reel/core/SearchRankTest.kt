package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.SearchRank
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchRankTest {
    private fun hit(id: Int, title: String, pop: Double, genres: List<Int> = emptyList()) =
        SearchRank.Hit(TitleSummary(TitleRef(MediaType.MOVIE, id), title, 2020, null, null, null, ""), pop, genres)

    private val known = listOf("Avengers: Endgame", "The Avengers", "Interstellar", "Avatar", "Breaking Bad", "Dune")

    @Test fun misspeltWordIsCorrected() {
        assertEquals("avengers", SearchRank.correct("aben", known))
        assertEquals("interstellar", SearchRank.correct("intersteller", known))
        assertEquals("breaking bad", SearchRank.correct("braeking bad", known))
    }

    @Test fun rightSpellingOrShortWordsAreLeftAlone() {
        assertNull(SearchRank.correct("aven", known))
        assertNull(SearchRank.correct("Dune", known))
        assertNull(SearchRank.correct("abe", known))
        assertNull(SearchRank.correct("zzzzzz", known))
    }

    @Test fun popularFirstNotTmdbOrder() {
        val ranked = SearchRank.rank("x", listOf(hit(1, "Obscure", 2.0), hit(2, "Huge", 400.0), hit(3, "Mid", 30.0)))
        assertEquals(listOf("Huge", "Mid", "Obscure"), ranked.map { it.title })
    }

    @Test fun exactNameBeatsSlightlyMorePopular() {
        val ranked = SearchRank.rank("dune", listOf(hit(1, "Dune: Part Two", 120.0), hit(2, "Dune", 100.0), hit(3, "Avatar", 130.0)))
        assertEquals(listOf("Dune", "Dune: Part Two", "Avatar"), ranked.map { it.title })
    }

    @Test fun tasteNudgesTies() {
        val taste = SearchRank.taste(listOf(listOf(878), listOf(878, 28), listOf(18)))
        assertEquals(1.0, taste[878])
        val ranked = SearchRank.rank("x", listOf(hit(1, "Drama", 50.0, listOf(18)), hit(2, "SciFi", 50.0, listOf(878))), taste)
        assertEquals(listOf("SciFi", "Drama"), ranked.map { it.title })
    }

    @Test fun duplicatesCollapse() {
        assertEquals(1, SearchRank.rank("a", listOf(hit(1, "A", 5.0), hit(1, "A", 9.0))).size)
    }

    @Test fun parserKeepsPopularityGenresAndPeoplesTitles() {
        val body = """{"results":[
            {"media_type":"movie","id":1,"title":"Top Gun","popularity":80.5,"genre_ids":[28,18]},
            {"media_type":"person","id":9,"name":"Tom Cruise","known_for":[{"media_type":"movie","id":2,"title":"Mission: Impossible","popularity":60}]},
            {"media_type":"tv","id":3,"name":"Show","popularity":5}
        ]}"""
        val hits = TmdbParser.parseSearchHits(body)
        assertEquals(listOf("Top Gun", "Mission: Impossible", "Show"), hits.map { it.summary.title })
        assertEquals(listOf(28, 18), hits[0].genres)
        assertEquals(80.5, hits[0].popularity)
    }
}
