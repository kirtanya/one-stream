package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch

/** One source on the TV title page / source panel. [title] "1080p · 4.2 GB · HDR", [detail] "Torrentio · 142 seeders · Hindi". */
data class TitleSourceRow(val source: Source, val title: String, val detail: String, val resume: String?, val tooBig: Boolean) {
    val key: String get() = source.key
}

/** Why the search ended with nothing to pick: a headline and a line of help. */
data class NoSources(val title: String, val body: String)

/**
 * What the TV shows for a film or episode right now: [rows] best first (the first [TitleSources.TOP] unless expanded),
 * [more] sources still hidden behind "All sources…", [searching] while providers are answering, [empty] once the
 * search ended with nothing usable.
 */
data class TitleSourcePage(val rows: List<TitleSourceRow>, val more: Int, val searching: String?, val empty: NoSources?)

/**
 * The TV title page's sources (docs/TV_CLEAN_REDESIGN.md §4, §5): the player's Change source rows
 * ([PlayerSources.row], same text) for the title being browsed, in the order the Sources page ranks them.
 * The caller passes [TitleSources.page]'s `ordered` list already marked up with the user's stars and the
 * debrid "not ready" demotion, so this stays pure.
 */
object TitleSources {
    /** Rows shown before "All sources…". */
    const val TOP = 5

    /** A page can't scroll forever on a remote: the rest is on the phone's Sources page. */
    const val MAX = 40

    fun page(
        search: SourceSearch?,
        ordered: List<Source>,
        expanded: Boolean,
        /** False for a file this device can't hold (a 4K REMUX on the TV). */
        fits: (Source) -> Boolean = { true },
        /** "Resume 42:10", put on the first source that plays in the app: any pick resumes there. */
        resume: String? = null,
    ): TitleSourcePage {
        // Trailers aren't the film. Services open another app: they stay, but after everything that plays here.
        val usable = ordered.filterNot { it.isExtra }
        val (apps, own) = usable.partition { it.kind == SourceKind.EXTERNAL }
        val all = (own + apps).take(MAX)
        val shown = if (expanded) all else all.take(TOP)
        val firstOwn = shown.indexOfFirst { it.kind != SourceKind.EXTERNAL }
        val rows = shown.mapIndexed { i, s -> row(s, fits(s), resume.takeIf { i == firstOwn }) }
        val searching = when {
            search == null -> "Searching…"
            !search.done -> "Searching… ${search.total - search.pending.size} of ${search.total}"
            else -> null
        }
        val empty = if (searching == null && rows.isEmpty()) noSources(search!!) else null
        return TitleSourcePage(rows, more = all.size - shown.size, searching = searching, empty = empty)
    }

    fun row(s: Source, fits: Boolean, resume: String?): TitleSourceRow {
        if (s.kind == SourceKind.EXTERNAL) {
            val provider = com.kirtanya.reel.core.sources.SourceFacts.providerLabel(s.provider)
            return TitleSourceRow(s, "Streaming service", listOf(provider, "Opens in its app").joinToString(" · "), null, tooBig = false)
        }
        val r = PlayerSources.row(s, current = false)
        return TitleSourceRow(s, r.title, r.detail, resume, tooBig = !fits)
    }

    /** "Resume 42:10", "Resume 1:05:09": the way the title page words a saved position. */
    fun resumeLabel(positionMs: Long): String {
        val total = positionMs / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val sec = total % 60
        return "Resume " + if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** The same words the Sources page uses when a finished search found nothing it can offer. */
    fun noSources(search: SourceSearch): NoSources {
        val allFailed = search.total > 0 && search.failed.size == search.total
        val onlyExtras = search.sources.isNotEmpty() && search.sources.all { it.isExtra }
        return when {
            search.total == 0 -> NoSources("No sources for this yet", "Add addons or plugins in Settings to find places to play this.")
            allFailed -> NoSources("Your sources didn't answer", "Check your connection and search again.")
            onlyExtras -> NoSources("Only trailers and extras found", "Your sources only had trailers or extras for this.")
            search.total == 1 -> NoSources("Nothing found", "Your only source didn't have this. Try again later, or add more sources in Settings.")
            else -> NoSources("Nothing found", "None of your ${search.total} sources had this. Try again later, or add more sources in Settings.")
        }
    }
}
