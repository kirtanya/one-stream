package com.kirtanya.reel.core.report

/**
 * Keeps home-disk credentials out of problem reports (docs/HOME_DISK.md §6): a line holding a [passwords]
 * value is dropped whole (like the TMDB token in [LogWindow]), and [names] (the share's user name and
 * folder) are masked wherever they appear: in logs, stacks, report.json and notes.
 *
 * Passwords count from 4 characters, names from 3: shorter values would match ordinary text everywhere.
 */
class Redactor(passwords: Collection<String>, names: Collection<String>) {
    private val passwords = passwords.map(String::trim).filter { it.length >= 4 }.distinct()
    private val names = names.map(String::trim).filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    val isEmpty: Boolean get() = passwords.isEmpty() && names.isEmpty()

    /** True when [line] holds a password (drop it). */
    fun holdsPassword(line: String): Boolean = passwords.any { line.contains(it) }

    /** [text] with every password replaced and every name masked (case-insensitive). */
    fun scrub(text: String): String {
        var out = text
        for (p in passwords) out = out.replace(p, MASK)
        for (n in names) out = out.replace(n, MASK, ignoreCase = true)
        return out
    }

    /** Log lines: password lines dropped, names masked. */
    fun lines(lines: List<String>): List<String> = lines.filterNot(::holdsPassword).map(::scrub)

    companion object {
        const val MASK = "‹hidden›"
        val NONE = Redactor(emptyList(), emptyList())
    }
}
