package com.kirtanya.reel.core.library

/**
 * Downloads and the stream cache as ONE list (2026-11-21): everything played or downloaded sits together.
 * "Kept" = a download (stays until you delete it); not kept = something you played, which clears itself when
 * space is needed. Keep turns a played title into a kept one (the rest downloads); Don't keep does the reverse.
 */
enum class LibraryFilter(val label: String) { ALL("All"), DOWNLOADING("Downloading"), KEPT("Kept"), HOME("Home disk") }

/** What the list needs to know about one entry. */
data class LibraryFacts(val kept: Boolean, val finished: Boolean, val streaming: Boolean = false)

object LibraryList {
    fun matches(f: LibraryFacts, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.DOWNLOADING -> f.kept && !f.finished
        LibraryFilter.KEPT -> f.kept
        // Titles on the home disk come with the disk; until then this chip shows the at-home requests only.
        LibraryFilter.HOME -> false
    }

    /**
     * The order on screen: what is downloading first, then what is playing right now, then everything else in the
     * engine's own order (stable, so rows don't jump as numbers change).
     */
    fun <T> order(items: List<T>, facts: (T) -> LibraryFacts): List<T> =
        items.withIndex().sortedWith(compareBy({ rank(facts(it.value)) }, { it.index })).map { it.value }

    private fun rank(f: LibraryFacts) = when {
        f.kept && !f.finished -> 0
        f.streaming -> 1
        else -> 2
    }

    /** The status line of a title that isn't kept: "Played recently · 1.2 GB · not kept". */
    fun notKeptLine(sizeText: String?): String =
        listOfNotNull("Played recently", sizeText, "not kept").joinToString(" · ")

    /** Shown once above the list when anything in it isn't kept. */
    const val EXPLAINER = "Kept titles stay until you delete them. The rest is what you played recently; it clears itself when space is needed."

    const val KEEP_TOAST = "Keeping it. The rest downloads too"

    /** The "Don't keep?" dialog body. */
    fun unkeepConfirm(finished: Boolean): String =
        (if (finished) "" else "Downloading stops. ") +
            "What's there stays on this device and still plays, until space is needed."
}
