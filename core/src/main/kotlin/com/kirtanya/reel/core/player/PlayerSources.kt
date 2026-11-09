package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.sources.ByteSize
import com.kirtanya.reel.core.sources.CacheState
import com.kirtanya.reel.core.sources.DebridCache
import com.kirtanya.reel.core.sources.SeedHealth
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceFacts
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch

/** One line of the player's Change source page: [title] "1080p · 4.2 GB", [detail] "Torrentio · 142 seeders · Hindi, English". */
data class PlayerSourceRow(val source: Source, val title: String, val detail: String, val current: Boolean) {
    val key: String get() = source.key
}

/**
 * What the player's Change source page shows right now: [rows] best first, [searching] ("Searching… 3 of 7") while
 * providers are still answering, [empty] when the search ended with nothing but the playing source.
 */
data class PlayerSourcePage(val rows: List<PlayerSourceRow>, val searching: String?, val empty: String?)

/**
 * The player's Change source page (a panel page like Settings / Subtitles). It lists the same ranked results the
 * Sources page does (SourceRanker already ordered them, so no second ranking here), cut down to what a viewer
 * on the sofa needs per row: quality, size, provider, seed health or "Direct", a language.
 */
object PlayerSources {
    /** The rest are on the full Sources page ("All sources…"). */
    const val MAX_ROWS = 15
    const val NONE_FOUND = "No other sources found"
    const val ALL_SOURCES_ROW = "All sources…"

    fun page(search: SourceSearch?, playing: Source): PlayerSourcePage {
        val ranked = search?.sources.orEmpty()
            // Services open another app and trailers aren't the film: neither can replace the playing source here.
            .filter { it.kind != SourceKind.EXTERNAL && !it.isExtra }
        val playingKey = playing.key
        // The playing source may be missing from a fresh search (an addon dropped it): it still heads the list, marked.
        val ordered = if (ranked.any { it.key == playingKey }) ranked else listOf(playing) + ranked
        val rows = ordered.take(MAX_ROWS).map { row(it, current = it.key == playingKey) }
        val searching = when {
            search == null -> "Searching…"
            !search.done -> "Searching… ${search.total - search.pending.size} of ${search.total}"
            else -> null
        }
        val others = rows.any { !it.current }
        return PlayerSourcePage(rows, searching, empty = if (searching == null && !others) NONE_FOUND else null)
    }

    /** [omitLanguage]: a language the row shows another way (the TV's "Hindi" tag), so the detail doesn't say it twice. */
    fun row(s: Source, current: Boolean, omitLanguage: String? = null): PlayerSourceRow {
        // Quality, size and the two tags that matter most (HDR / Dolby Vision / Atmos…).
        val title = (listOfNotNull(s.quality.label, s.sizeBytes?.let(ByteSize::format)) + SourceFacts.tags(s).filterNot { s.quality.label.contains(it) }.take(2)).joinToString(" · ")
        val peers = when (s.kind) {
            SourceKind.DIRECT -> if (DebridCache.of(s) == CacheState.UNCACHED) DebridCache.NOT_CACHED_TAG else "Direct"
            // The real count when the addon gives it (easier to compare than Strong / OK), else the health word.
            else -> s.seeders?.takeIf { it > 0 }?.let { if (it == 1) "1 seeder" else "$it seeders" } ?: SeedHealth.of(s)?.label
        }
        val languages = SourceFacts.languages(s).filter { it != omitLanguage }.take(3).joinToString(", ").ifEmpty { null }
        val detail = listOfNotNull(SourceFacts.providerLabel(s.provider), peers, languages).joinToString(" · ")
        return PlayerSourceRow(s, title, detail, current)
    }
}
