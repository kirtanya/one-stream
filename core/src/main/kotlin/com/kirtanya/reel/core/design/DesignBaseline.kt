package com.kirtanya.reel.core.design

/**
 * Baselines for the design rules: violations that existed when a rule was introduced, one per line
 * (`where | what | measured`). Only violations beyond the baseline fail the suite. A line's key is
 * everything but the last field, so a measured value that drifts (3.9:1 → 4.1:1) is still the same
 * entry; lines are counted (multiset), so a third identical chip fails when two are baselined.
 *
 * Baselines only ever shrink without the user's OK (CLAUDE.md "Design rules").
 */
object DesignBaseline {
    const val SEP = " | "

    /** One line from its fields; field text can't break the format. */
    fun line(vararg fields: String) = fields.joinToString(SEP) { it.replace("|", "/").replace('\n', ' ').replace('\r', ' ').trim() }

    fun key(line: String): String {
        val parts = line.split(SEP)
        return if (parts.size <= 1) line.trim() else parts.dropLast(1).joinToString(SEP).trim()
    }

    /** First field: the screen (or file) a line belongs to. */
    fun where(line: String) = line.split(SEP).first().trim()

    /** Baseline file text → its lines (blank lines and # comments skipped). */
    fun parse(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    class Diff(val new: List<String>, val known: List<String>, val stale: List<String>)

    fun diff(found: List<String>, baseline: List<String>): Diff {
        val left = HashMap<String, Int>()
        for (b in baseline) left.merge(key(b), 1, Int::plus)
        val new = ArrayList<String>()
        val known = ArrayList<String>()
        for (f in found) {
            val k = key(f)
            val n = left[k] ?: 0
            if (n > 0) { left[k] = n - 1; known += f } else new += f
        }
        val total = HashMap<String, Int>()
        for (b in baseline) total.merge(key(b), 1, Int::plus)
        val stale = ArrayList<String>()
        val seen = HashMap<String, Int>()
        for (b in baseline) {
            val k = key(b)
            // The last `left[k]` copies of a key were not found again.
            if (seen.merge(k, 1, Int::plus)!! > total.getValue(k) - (left[k] ?: 0)) stale += b
        }
        return Diff(new, known, stale)
    }
}
