package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.ChartKind
import com.kirtanya.reel.core.browse.TopChart
import com.kirtanya.reel.core.browse.TopCharts
import com.kirtanya.reel.core.tmdb.ContentPreference
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TopChartsTest {
    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()
    private val popularMovies = TopChart(ChartKind.Popular, MediaType.MOVIE)
    private val topSeries = TopChart(ChartKind.TopRated, MediaType.TV)

    private fun title(id: Int, type: MediaType = MediaType.MOVIE) = TitleSummary(TitleRef(type, id), "Title $id", 2020, null, null, 7.0, "")
    private fun page(range: IntRange) = Result.success(range.map { title(it) })

    @Test fun fourChartsInTheOrderAsked() {
        assertEquals(
            listOf("Most popular movies", "Most popular series", "Top rated movies", "Top rated series"),
            TopCharts.ALL.map { it.label },
        )
        assertEquals(4, TopCharts.ALL.map { it.key }.toSet().size)
        assertEquals(100, TopCharts.SIZE)
    }

    @Test fun bothUsesTmdbsOwnPopularAndTopRatedLists() {
        assertEquals("movie/popular?language=en-US&page=3", TopCharts.path(popularMovies, ContentPreference.Both, 3))
        assertEquals("tv/top_rated?language=en-US&page=1", TopCharts.path(topSeries, ContentPreference.Both, 1))
        assertEquals("movie/top_rated?language=en-US&page=5", TopCharts.path(TopChart(ChartKind.TopRated, MediaType.MOVIE), ContentPreference.Both, 5))
        assertEquals("tv/popular?language=en-US&page=2", TopCharts.path(TopChart(ChartKind.Popular, MediaType.TV), ContentPreference.Both, 2))
    }

    @Test fun bollywoodSortsDiscoverByHindiWithAVoteFloor() {
        assertEquals(
            "discover/movie?language=en-US&include_adult=false&with_original_language=hi&sort_by=popularity.desc&vote_count.gte=50&page=2",
            TopCharts.path(popularMovies, ContentPreference.Bollywood, 2),
        )
        assertEquals(
            "discover/movie?language=en-US&include_adult=false&with_original_language=hi&sort_by=vote_average.desc&vote_count.gte=200&page=1",
            TopCharts.path(TopChart(ChartKind.TopRated, MediaType.MOVIE), ContentPreference.Bollywood, 1),
        )
        assertTrue("vote_count.gte=50&" in TopCharts.path(topSeries, ContentPreference.Bollywood, 1))
    }

    @Test fun hollywoodNeedsMoreVotesForARatingChart() {
        val films = TopCharts.path(TopChart(ChartKind.TopRated, MediaType.MOVIE), ContentPreference.Hollywood, 1)
        assertTrue("with_original_language=en" in films && "vote_count.gte=2000" in films, films)
        val shows = TopCharts.path(topSeries, ContentPreference.Hollywood, 1)
        assertTrue("vote_count.gte=500" in shows, shows)
    }

    @Test fun everyChartAsksFivePagesInOrder() {
        for (chart in TopCharts.ALL) for (pref in ContentPreference.entries) {
            val paths = TopCharts.paths(chart, pref)
            assertEquals(5, paths.size)
            assertEquals((1..5).toList(), paths.map { it.substringAfterLast("page=").toInt() })
            assertEquals(5, paths.toSet().size)
        }
    }

    @Test fun pagesOfTwentyMakeTopHundredInOrder() {
        val chart = TopCharts.assemble((0 until 5).map { page(it * 20 + 1..it * 20 + 20) })
        assertEquals(100, chart.size)
        assertEquals((1..100).toList(), chart.map { it.ref.id })
    }

    @Test fun moreThanAHundredAreCut() {
        val chart = TopCharts.assemble((0 until 6).map { page(it * 20 + 1..it * 20 + 20) })
        assertEquals(100, chart.size)
        assertEquals(100, chart.last().ref.id)
    }

    @Test fun aTitleThatMovedBetweenPagesKeepsItsBetterPlace() {
        // 20 is on page 1 and, after a re-sort while the pages loaded, on page 2 too.
        val chart = TopCharts.assemble(listOf(page(1..20), page(20..39)))
        assertEquals(39, chart.size)
        assertEquals(1, chart.count { it.ref.id == 20 })
        assertEquals(20, chart[19].ref.id)
        assertEquals(21, chart[20].ref.id)
    }

    @Test fun aFilmAndAShowWithTheSameIdAreDifferentTitles() {
        val mixed = Result.success(listOf(title(5, MediaType.MOVIE), title(5, MediaType.TV)))
        assertEquals(2, TopCharts.assemble(listOf(mixed)).size)
    }

    @Test fun aFailedLaterPageEndsTheChartThere() {
        val chart = TopCharts.assemble(listOf(page(1..20), page(21..40), Result.failure(java.io.IOException("x")), page(61..80), page(81..100)))
        assertEquals((1..40).toList(), chart.map { it.ref.id })
    }

    @Test fun anEmptyPageEndsTheChartThere() {
        val chart = TopCharts.assemble(listOf(page(1..20), Result.success(emptyList()), page(41..60)))
        assertEquals(20, chart.size)
    }

    @Test fun aFailedFirstPageIsTheScreensError() {
        val e = assertFailsWith<java.io.IOException> {
            TopCharts.assemble(listOf(Result.failure(java.io.IOException("Can't reach TMDB.")), page(21..40)))
        }
        assertEquals("Can't reach TMDB.", e.message)
    }

    @Test fun nothingBackIsAnEmptyChart() {
        assertTrue(TopCharts.assemble(listOf(Result.success(emptyList()))).isEmpty())
        assertTrue(TopCharts.assemble(emptyList()).isEmpty())
    }

    @Test fun ranksCountFromOneAndHiddenTitlesLeaveAGap() {
        val chart = (1..5).map { title(it) }
        val ranked = TopCharts.ranked(chart, hidden = setOf("movie:2", "movie:4"))
        assertEquals(listOf(1, 3, 5), ranked.map { it.rank })
        assertEquals(listOf(1, 3, 5), ranked.map { it.title.ref.id })
        assertEquals((1..5).toList(), TopCharts.ranked(chart, emptySet()).map { it.rank })
    }

    @Test fun captionNamesTheContentChoiceOnlyWhenItNarrowsTheChart() {
        assertEquals("Top 100 · TMDB popularity", popularMovies.caption(ContentPreference.Both))
        assertEquals("Top 100 · TMDB rating · Bollywood (Hindi)", TopChart(ChartKind.TopRated, MediaType.TV).caption(ContentPreference.Bollywood))
        assertEquals("Top 100 · by popularity", popularMovies.tileCaption)
    }

    // Fixtures are real TMDB shapes: the endpoints' answers go through the same parser the app uses.
    @Test fun popularPagesParseAndMergeWithoutRepeats() {
        val p1 = TmdbParser.parseList(fixture("chart_popular_p1.json"), MediaType.MOVIE)
        val p2 = TmdbParser.parseList(fixture("chart_popular_p2.json"), MediaType.MOVIE)
        assertEquals(3, p1.size)
        val chart = TopCharts.assemble(listOf(Result.success(p1), Result.success(p2)))
        assertEquals(listOf("Fight Club", "The Matrix", "Forrest Gump", "The Shawshank Redemption"), chart.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), TopCharts.ranked(chart, emptySet()).map { it.rank })
    }

    @Test fun discoverAnswersWithoutMediaTypeTakeTheChartsType() {
        val shows = TmdbParser.parseList(fixture("chart_top_rated_tv_discover.json"), MediaType.TV)
        assertEquals(listOf("Breaking Bad", "Panchayat"), shows.map { it.title })
        assertTrue(shows.all { it.ref.type == MediaType.TV })
        assertEquals(8.9, shows[0].rating)
    }
}
