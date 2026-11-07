package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.TitleSummary
import kotlin.math.ln
import kotlin.math.min

/**
 * Search results in the order people expect: TMDB's search is literal and sorted by its own relevance, so a typo
 * ("aben") finds almost nothing and big titles can sit below obscure ones. Here every hit is ranked by popularity,
 * a boost for how well the name matches, and the viewer's taste (genres they watch and save); a misspelt word is
 * corrected against well-known titles so the search can be run again with the real word.
 */
object SearchRank {
    /** A search hit with what ranking needs. [genres] are TMDB genre ids. */
    data class Hit(val summary: TitleSummary, val popularity: Double, val genres: List<Int> = emptyList())

    /**
     * Distinct titles, best first. [taste]: genre id → weight 0..1 (1 = the viewer's favourite genre); an empty map
     * ranks by popularity and match alone.
     */
    fun rank(query: String, hits: List<Hit>, taste: Map<Int, Double> = emptyMap()): List<TitleSummary> {
        val q = normalize(query)
        return hits.groupBy { it.summary.ref }.values
            .map { same -> same.maxBy { it.popularity } }
            .sortedByDescending { score(q, it, taste) }
            .map { it.summary }
    }

    internal fun score(q: String, hit: Hit, taste: Map<Int, Double>): Double {
        val pop = ln(1.0 + hit.popularity.coerceAtLeast(0.0))
        val like = hit.genres.maxOfOrNull { taste[it] ?: 0.0 } ?: 0.0
        return pop * (1.0 + 0.5 * like) + matchBonus(q, normalize(hit.summary.title))
    }

    /** The whole name typed: well ahead; the name starts with it: ahead; a word starts with it: a little ahead. */
    internal fun matchBonus(q: String, title: String): Double = when {
        q.isEmpty() -> 0.0
        title == q -> 4.0
        title.startsWith(q) -> 2.0
        title.split(' ').any { it.startsWith(q) } -> 1.0
        else -> 0.0
    }

    /**
     * [query] with each misspelt word (4+ letters, not the start of any known word) swapped for the closest word in
     * [known] (titles, most popular first); null when nothing changes. "aben" → "avengers", "intersteller" → "interstellar".
     */
    fun correct(query: String, known: List<String>): String? {
        val words = known.asSequence().flatMap { normalize(it).split(' ').asSequence() }.filter { it.length >= 3 }.distinct().toList()
        val typed = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (typed.isEmpty()) return null
        val fixed = typed.map { w -> if (w.length < 4 || words.any { it.startsWith(w) }) w else closest(w, words) ?: w }
        return fixed.joinToString(" ").takeIf { it != typed.joinToString(" ") }
    }

    /** The word whose start is nearest [w] (1 edit for 4–6 letters, 2 for longer); earlier words win ties. */
    private fun closest(w: String, words: List<String>): String? {
        val allowed = if (w.length >= 7) 2 else 1
        var best: String? = null
        var bestD = allowed + 1
        for (word in words) {
            if (word.length < w.length - 1) continue
            val d = (w.length - 1..w.length + 1).filter { it in 1..word.length }.minOf { distance(w, word.take(it)) }
            if (d < bestD) { best = word; bestD = d }
        }
        return best
    }

    /** Edit distance counting a swap of two neighbouring letters as one edit. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Lower case, letters and digits only, single spaces ("Spider-Man: No Way Home" → "spider man no way home"). */
    fun normalize(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex(" +"), " ")

    /** Genre weights from the genres of titles the viewer watched or saved: the most common genre is 1. */
    fun taste(genresPerTitle: List<List<Int>>): Map<Int, Double> {
        val counts = genresPerTitle.flatMap { it.distinct() }.groupingBy { it }.eachCount()
        val top = counts.values.maxOrNull() ?: return emptyMap()
        return counts.mapValues { it.value.toDouble() / top }
    }
}
