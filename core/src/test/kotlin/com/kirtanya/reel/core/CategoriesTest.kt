package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.BrowseRows
import com.kirtanya.reel.core.browse.Categories
import com.kirtanya.reel.core.browse.Category
import com.kirtanya.reel.core.browse.GridNav
import com.kirtanya.reel.core.browse.GridNav.Dir
import com.kirtanya.reel.core.browse.Interleave
import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbPaths
import com.kirtanya.reel.core.tmdb.titlesInLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoriesTest {
    private fun merged() = Categories.mergeGenres(Categories.FALLBACK_MOVIE_GENRES, Categories.FALLBACK_TV_GENRES)

    @Test fun languagesAreTheSevenAsked() {
        assertEquals(listOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Korean", "Japanese"), Categories.LANGUAGES.map { it.label })
        assertEquals(listOf("hi", "en", "ta", "te", "ml", "ko", "ja"), Categories.LANGUAGES.map { it.language })
        assertTrue(Categories.LANGUAGES.all { it.types == listOf(MediaType.MOVIE, MediaType.TV) })
    }

    @Test fun filmAndShowGenresMergeIntoOneTile() {
        val action = merged().single { it.label == "Action" }
        assertEquals(28, action.movieGenre)
        assertEquals(10759, action.tvGenre)
        val scifi = merged().single { it.label == "Sci-Fi" }
        assertEquals(878, scifi.movieGenre)
        assertEquals(10765, scifi.tvGenre)
        assertEquals("Films and shows", action.caption)
    }

    @Test fun genresOnlyOneListHasKeepOneId() {
        val horror = merged().single { it.label == "Horror" }
        assertEquals(27, horror.movieGenre)
        assertNull(horror.tvGenre)
        assertEquals(listOf(MediaType.MOVIE), horror.types)
        assertEquals("Films", horror.caption)
        val kids = merged().single { it.label == "Kids" }
        assertNull(kids.movieGenre)
        assertEquals("Shows", kids.caption)
    }

    @Test fun curatedNamesComeFirstThenTheRestAlphabetically() {
        val names = merged().map { it.label }
        assertEquals(Categories.GENRE_ORDER, names.take(Categories.GENRE_ORDER.size))
        val rest = names.drop(Categories.GENRE_ORDER.size)
        assertEquals(rest.sorted(), rest)
    }

    @Test fun tvMovieNewsTalkAndSoapAreDropped() {
        val tile = Categories.mergeGenres(listOf(Genre(1, "TV Movie"), Genre(2, "Drama")), listOf(Genre(3, "News"), Genre(4, "Talk"), Genre(5, "Soap"), Genre(6, "Drama")))
        assertEquals(listOf(Category("Drama", movieGenre = 2, tvGenre = 6)), tile)
    }

    @Test fun mergeWorksWithOneListEmptyAndRepeatedNames() {
        assertEquals(listOf(Category("Drama", movieGenre = 18)), Categories.mergeGenres(listOf(Genre(18, "Drama"), Genre(99, "Drama")), emptyList()))
        assertTrue(Categories.mergeGenres(emptyList(), emptyList()).isEmpty())
    }

    @Test fun fallbackListHasEveryCuratedGenre() {
        assertTrue(Categories.FALLBACK_GENRES.map { it.label }.containsAll(Categories.GENRE_ORDER))
    }

    @Test fun keysAreUniqueAndTellGenresFromLanguages() {
        val all = Categories.FALLBACK_GENRES + Categories.LANGUAGES
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals("lang:hi", Categories.LANGUAGES[0].key)
    }

    @Test fun languagePathFiltersOnOriginalLanguage() {
        val p = TmdbPaths.titlesInLanguage(MediaType.TV, "ko", 3)
        assertTrue(p.startsWith("discover/tv?"))
        assertTrue("with_original_language=ko" in p && p.endsWith("page=3"))
    }

    @Test fun browseRowsAreOnlyOneKind() {
        val films = BrowseRows.forType(MediaType.MOVIE)
        val shows = BrowseRows.forType(MediaType.TV)
        assertTrue(films.all { "/tv" !in it.path && "tv/" !in it.path })
        assertTrue(shows.all { "movie" !in it.path })
        assertEquals(films.size, films.map { it.title }.toSet().size)
        assertEquals(shows.size, shows.map { it.title }.toSet().size)
        assertTrue(films.any { it.title == "Popular Hindi films" && "with_original_language=hi" in it.path })
        assertTrue(films.size >= 6 && shows.size >= 6)
    }

    private fun t(type: MediaType, id: Int) = TitleSummary(TitleRef(type, id), "t$id", null, null, null, null, "")

    @Test fun filmsAndShowsAlternateAndDuplicatesGoOnce() {
        val films = listOf(t(MediaType.MOVIE, 1), t(MediaType.MOVIE, 2), t(MediaType.MOVIE, 3))
        val shows = listOf(t(MediaType.TV, 1), t(MediaType.MOVIE, 2))
        assertEquals(listOf("movie:1", "tv:1", "movie:2", "movie:3"), Interleave.alternate(films, shows).map { it.ref.toString() })
        assertEquals(films, Interleave.alternate(films, emptyList()))
        assertTrue(Interleave.alternate(emptyList(), emptyList()).isEmpty())
    }

    // 10 cards, 4 across: rows 0-3, 4-7, 8-9 (short).
    @Test fun gridMovesInsideTheGrid() {
        assertEquals(5, GridNav.move(4, 4, 10, Dir.Right))
        assertEquals(4, GridNav.move(5, 4, 10, Dir.Left))
        assertEquals(1, GridNav.move(5, 4, 10, Dir.Up))
        assertEquals(9, GridNav.move(5, 4, 10, Dir.Down))
    }

    @Test fun gridDownKeepsTheColumnAndLandsOnTheLastCardOfAShortRow() {
        assertEquals(5, GridNav.move(1, 4, 10, Dir.Down))
        assertEquals(8, GridNav.move(4, 4, 10, Dir.Down))
        // Column 3 has nothing below row 1: the short row's last card.
        assertEquals(9, GridNav.move(7, 4, 10, Dir.Down))
        assertEquals(9, GridNav.move(6, 4, 10, Dir.Down))
        // Already on the last row.
        assertNull(GridNav.move(9, 4, 10, Dir.Down))
        assertNull(GridNav.move(8, 4, 10, Dir.Down))
    }

    @Test fun gridEdgesAreLeftToThePage() {
        assertNull(GridNav.move(4, 4, 10, Dir.Left))
        assertNull(GridNav.move(0, 4, 10, Dir.Left))
        assertNull(GridNav.move(3, 4, 10, Dir.Right))
        assertNull(GridNav.move(9, 4, 10, Dir.Right))
        assertNull(GridNav.move(2, 4, 10, Dir.Up))
        assertNull(GridNav.move(-1, 4, 10, Dir.Down))
        assertNull(GridNav.move(0, 4, 0, Dir.Down))
    }

    @Test fun focusRestoreStaysInsideWhatIsLoaded() {
        assertEquals(7, GridNav.restore(7, 20))
        assertEquals(4, GridNav.restore(30, 5))
        assertEquals(0, GridNav.restore(3, 0))
        assertEquals(0, GridNav.restore(-2, 5))
    }
}
