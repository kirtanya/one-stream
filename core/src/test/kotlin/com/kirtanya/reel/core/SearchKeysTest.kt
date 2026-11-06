package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.KeyPage
import com.kirtanya.reel.core.browse.SearchKey
import com.kirtanya.reel.core.browse.SearchKeys
import com.kirtanya.reel.core.browse.SearchSuggest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchKeysTest {
    private fun t(c: Char) = SearchKey.Text(c)

    @Test fun lettersPageHoldsEveryLetterOnce() {
        val all = (0 until SearchKeys.GRID).flatMap { r -> (0 until SearchKeys.COLS).map { c -> SearchKeys.key(KeyPage.LETTERS, r, c) } }
        val letters = all.filterIsInstance<SearchKey.Text>().map { it.char }.filter { it in 'a'..'z' }
        assertEquals(('a'..'z').toList(), letters)
    }

    @Test fun tallKeysAndModeKey() {
        assertEquals(SearchKey.Delete, SearchKeys.key(KeyPage.LETTERS, 0, 6))
        assertEquals(SearchKey.Delete, SearchKeys.key(KeyPage.LETTERS, 1, 6))
        assertEquals(SearchKey.Space, SearchKeys.key(KeyPage.LETTERS, 2, 6))
        assertEquals(SearchKey.Space, SearchKeys.key(KeyPage.LETTERS, 4, 6))
        assertEquals(SearchKey.Mode, SearchKeys.key(KeyPage.LETTERS, 4, 5))
        assertEquals(SearchKey.Mode, SearchKeys.key(KeyPage.SYMBOLS, 4, 5))
    }

    @Test fun symbolsPageHasDigitsAndTitlePunctuation() {
        assertEquals(t('1'), SearchKeys.key(KeyPage.SYMBOLS, 0, 0))
        assertEquals(t('0'), SearchKeys.key(KeyPage.SYMBOLS, 1, 3))
        val chars = (0 until SearchKeys.GRID).flatMap { r -> (0 until SearchKeys.COLS - 1).map { c -> SearchKeys.key(KeyPage.SYMBOLS, r, c) } }
            .filterIsInstance<SearchKey.Text>().map { it.char }
        assertTrue(chars.containsAll("1234567890&':!?".toList()))
    }

    @Test fun actionRowSplitsClearAndSearch() {
        assertEquals(SearchKey.Clear, SearchKeys.key(KeyPage.LETTERS, SearchKeys.ACTION_ROW, 0))
        assertEquals(SearchKey.Clear, SearchKeys.key(KeyPage.LETTERS, SearchKeys.ACTION_ROW, 2))
        assertEquals(SearchKey.Search, SearchKeys.key(KeyPage.LETTERS, SearchKeys.ACTION_ROW, 3))
        assertEquals(SearchKey.Search, SearchKeys.key(KeyPage.SYMBOLS, SearchKeys.ACTION_ROW, 6))
    }

    @Test fun startKeyIsO() = assertEquals(t('o'), SearchKeys.key(KeyPage.LETTERS, SearchKeys.START_ROW, SearchKeys.START_COL))

    @Test fun togglePages() {
        assertEquals(KeyPage.SYMBOLS, SearchKeys.toggle(KeyPage.LETTERS))
        assertEquals(KeyPage.LETTERS, SearchKeys.toggle(KeyPage.SYMBOLS))
    }

    @Test fun typingAppendsAndDeletes() {
        var s = ""
        for (k in "dune".map(::t)) s = SearchKeys.press(s, k)
        assertEquals("dune", s)
        assertEquals("dun", SearchKeys.press(s, SearchKey.Delete))
        assertEquals("", SearchKeys.press("", SearchKey.Delete))
        assertEquals("", SearchKeys.press(s, SearchKey.Clear))
    }

    @Test fun spaceNeverLeadsOrDoubles() {
        assertEquals("", SearchKeys.press("", SearchKey.Space))
        assertEquals("a ", SearchKeys.press("a", SearchKey.Space))
        assertEquals("a ", SearchKeys.press("a ", SearchKey.Space))
    }

    @Test fun modeAndSearchLeaveTheText() {
        assertEquals("ab", SearchKeys.press("ab", SearchKey.Mode))
        assertEquals("ab", SearchKeys.press("ab", SearchKey.Search))
    }

    @Test fun queryStopsAtTheLimit() {
        val full = "a".repeat(SearchKeys.MAX_LENGTH)
        assertEquals(full, SearchKeys.press(full, t('b')))
        assertEquals(full, SearchKeys.press(full, SearchKey.Space))
        assertEquals(full.dropLast(1), SearchKeys.press(full, SearchKey.Delete))
    }

    @Test fun deleteTakesWholeEmoji() = assertEquals("a", SearchKeys.press("a🎬", SearchKey.Delete))

    // ─── suggestions ──────────────────────────────────────────────────────────────────────────

    @Test fun matchLevels() {
        assertEquals(0, SearchSuggest.match("joker", "Joker"))
        assertEquals(1, SearchSuggest.match("joker", "Joker: Folie à Deux"))
        assertEquals(2, SearchSuggest.match("bad", "Breaking Bad"))
        assertEquals(null, SearchSuggest.match("zzz", "Breaking Bad"))
        assertEquals(null, SearchSuggest.match("  ", "Breaking Bad"))
    }

    @Test fun rankPutsBetterMatchesFirstAndDropsDuplicates() {
        val recent = listOf("batman: the killing joke", "dune")
        val titles = listOf("Joker: Folie à Deux", "Joker", "joker")
        assertEquals(listOf("Joker", "Joker: Folie à Deux"), SearchSuggest.rank("joker", recent, titles))
    }

    @Test fun rankKeepsSourceOrderWithinAMatch() =
        assertEquals(listOf("joker 2", "Joker: Folie à Deux"), SearchSuggest.rank("jok", listOf("joker 2"), listOf("Joker: Folie à Deux")))

    @Test fun rankIsCappedAtThree() = assertEquals(3, SearchSuggest.rank("a", listOf("a1", "a2", "a3", "a4", "a5")).size)

    @Test fun nothingTypedShowsTheFirstSourceAsIs() =
        assertEquals(listOf("dune", "heat"), SearchSuggest.rank("", listOf("dune", "heat", "alien", "up"), listOf("x"), max = 2))

    @Test fun nextKeysFollowTheCandidates() {
        val sources = listOf(listOf("Avatar", "Avengers", "Animal"))
        assertEquals(listOf('v', 'n'), SearchSuggest.nextKeys("a", sources, 2))
        assertEquals(emptyList(), SearchSuggest.nextKeys("", sources))
    }
}
