package com.kirtanya.reel.core.browse

/**
 * The TV search keyboard's keys and typing rules, no Android types (ported from One Launcher's keyboard).
 *
 * Alphabetical square grid, not QWERTY: on a remote every letter costs its distance in presses, and an alphabetical
 * grid is short to cross and instant to scan. The right column is two tall keys, Delete over the top two rows and
 * Space over the bottom three, so → off any row reaches one in a press. Under the grid sits one action row: Clear and
 * Search. Focus movement is Compose's own (the keys are plain focusables), so none of that lives here.
 */
sealed interface SearchKey {
    data class Text(val char: Char) : SearchKey
    data object Delete : SearchKey
    data object Space : SearchKey
    /** 123 / abc: swaps the letters page and the symbols page. */
    data object Mode : SearchKey
    data object Clear : SearchKey
    data object Search : SearchKey
}

enum class KeyPage { LETTERS, SYMBOLS }

object SearchKeys {
    const val COLS = 7
    /** Rows of letter / symbol keys; the action row is the one after them. */
    const val GRID = 5
    const val ACTION_ROW = GRID
    /** Columns the action row's Clear key covers; Search takes the rest. */
    val CLEAR_COLS = 0..2
    val SEARCH_COLS = 3 until COLS
    /** The rows the right column's tall keys cover. */
    val DELETE_ROWS = 0..1
    val SPACE_ROWS = 2 until GRID

    /** Where focus starts: "o", mid-grid, so any letter is a few presses away. */
    const val START_ROW = 2
    const val START_COL = 2

    /** Longest query: TMDB titles are far shorter, and it keeps the query line honest. */
    const val MAX_LENGTH = 60

    private val letters = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz-.,")
    // Titles use & ' : ! ? and digits; the rest are the launcher's.
    private val symbols = listOf("123456", "7890&'", "@_/:?!", "+=()*%", "#\"<>;")

    /** The key at [row], [col] (0 until [COLS]); the 123 / abc key sits at the end of the bottom key row. */
    fun key(page: KeyPage, row: Int, col: Int): SearchKey {
        if (row == ACTION_ROW) return if (col in CLEAR_COLS) SearchKey.Clear else SearchKey.Search
        if (col == COLS - 1) return if (row in DELETE_ROWS) SearchKey.Delete else SearchKey.Space
        val line = (if (page == KeyPage.LETTERS) letters else symbols)[row]
        return if (col < line.length) SearchKey.Text(line[col]) else SearchKey.Mode
    }

    /** The other page. */
    fun toggle(page: KeyPage) = if (page == KeyPage.LETTERS) KeyPage.SYMBOLS else KeyPage.LETTERS

    /** The query after pressing [key]. Mode and Search don't change the text. */
    fun press(text: String, key: SearchKey): String = when (key) {
        is SearchKey.Text -> if (text.length >= MAX_LENGTH) text else text + key.char
        SearchKey.Space -> if (text.isEmpty() || text.endsWith(' ') || text.length >= MAX_LENGTH) text else "$text "
        SearchKey.Delete -> if (text.isEmpty()) text else text.dropLast(if (text.length > 1 && text[text.length - 1].isLowSurrogate()) 2 else 1)
        SearchKey.Clear -> ""
        SearchKey.Mode, SearchKey.Search -> text
    }
}

/**
 * Suggestions for what's typed so far (ported from One Launcher). On a remote a suggestion saves a dozen presses, so the
 * strip leads with what was searched here before, then the titles the search already found: nothing is fetched per key.
 *
 * Matching is case-insensitive on the whole typed text, as a prefix of the candidate or of any word in it ("bad" finds
 * "Breaking Bad"). Better matches lead whatever their source: the exact title, then titles that start with it, then
 * titles with a word that does.
 */
object SearchSuggest {
    const val MAX = 3

    /** 0 = the exact title, 1 = starts with it, 2 = a word starts with it, null = no match. */
    fun match(typed: String, candidate: String): Int? {
        val q = typed.trim().lowercase()
        if (q.isEmpty()) return null
        val c = candidate.trim().lowercase()
        return when {
            c == q -> 0
            c.startsWith(q) -> 1
            c.split(' ', '-', ':', '.').any { it.startsWith(q) } -> 2
            else -> null
        }
    }

    /**
     * Best match first; within a match, [sources] in the order given, then each source's own order. Duplicates (ignoring
     * case) keep their best place. Nothing typed: the first source (recent searches) as it is.
     */
    fun rank(typed: String, vararg sources: List<String>, max: Int = MAX): List<String> {
        if (typed.isBlank()) return sources.firstOrNull().orEmpty().take(max)
        val found = ArrayList<Pair<Int, String>>()
        for (source in sources) for (c in source) match(typed, c)?.let { found += it to c }
        val seen = HashSet<String>()
        return found.sortedBy { it.first } // stable: source and position order hold within a match
            .map { it.second }
            .filter { seen.add(it.trim().lowercase()) }
            .take(max)
    }

    /**
     * The [n] characters most likely typed next, read from where each matching candidate continues (earlier candidates
     * weigh more): typed "a" with Avatar, Avengers, Animal → v, n. ' ' = Space.
     */
    fun nextKeys(typed: String, sources: List<List<String>>, n: Int = 3): List<Char> {
        val q = typed.trimStart().lowercase()
        if (q.isBlank()) return emptyList()
        val weight = HashMap<Char, Double>()
        var seen = 0
        for (source in sources) for (c in source) {
            if (seen == HINT_POOL) break
            val t = c.lowercase()
            val at = if (t.startsWith(q)) q.length
            else WORD.findAll(t).firstOrNull { t.startsWith(q, it.range.first) }?.let { it.range.first + q.length }
            val next = at?.let { t.getOrNull(it) } ?: continue
            weight[next] = (weight[next] ?: 0.0) + 1.0 / (1 + seen++)
        }
        return weight.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    private const val HINT_POOL = 40
    private val WORD = Regex("""(?<=[ \-:.])\S""")
}
