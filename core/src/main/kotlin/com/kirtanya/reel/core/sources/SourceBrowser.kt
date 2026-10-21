package com.kirtanya.reel.core.sources

/** Order of the list under the Recommended card. */
enum class SourceSort(val label: String) { BEST("Best"), SMALLEST("Smallest"), SEEDS("Most seeds") }

/**
 * The chips the user picked. Within one group any picked value matches (4K or 1080p);
 * across groups all must match (4K and Torrentio). Empty group = no filter.
 */
data class SourceFilters(
    val qualities: Set<QualityBucket> = emptySet(),
    val providers: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val types: Set<SourceType> = emptySet(),
    val sort: SourceSort = SourceSort.BEST,
) {
    val active: Boolean get() = qualities.isNotEmpty() || providers.isNotEmpty() || languages.isNotEmpty() || types.isNotEmpty()

    fun cleared() = SourceFilters(sort = sort)
}

/** One chip: what it filters on, its text, and how many rows it would show on its own. */
data class FilterChip<T>(val value: T, val label: String, val count: Int)

/** Which chips to show. Groups with a single value are left out (nothing to choose), except quality. */
data class SourceFacets(
    val qualities: List<FilterChip<QualityBucket>>,
    val providers: List<FilterChip<String>>,
    val languages: List<FilterChip<String>>,
    val types: List<FilterChip<SourceType>>,
) {
    val isEmpty: Boolean get() = qualities.isEmpty() && providers.isEmpty() && languages.isEmpty() && types.isEmpty()
}

/** One row of the Sources page, with everything it shows already worked out. */
data class SourceView(
    val source: Source,
    val bucket: QualityBucket,
    val health: SeedHealth?,
    val type: SourceType,
    val provider: String,
    val languages: List<String>,
    val tags: List<String>,
)

/** Progress line for a search that fills in as providers answer. */
data class SearchStatus(val headline: String, val detail: String?, val progress: Float?)

/**
 * The Sources page's logic: the Recommended pick, filter chips with counts, filtering and sorting.
 * The input list is already ranked best-first (SourceRanker), so "Best" keeps its order.
 */
object SourceBrowser {
    fun view(s: Source) = SourceView(
        source = s,
        bucket = QualityBucket.of(s.quality),
        health = SeedHealth.of(s),
        type = SourceType.of(s),
        provider = SourceFacts.providerLabel(s.provider),
        languages = SourceFacts.languages(s),
        tags = SourceFacts.tags(s),
    )

    /** The ranker's winner that can start in the app (services open another app, so never recommended). */
    fun recommended(ranked: List<Source>): Source? = ranked.firstOrNull { it.kind != SourceKind.EXTERNAL }

    /**
     * Chips over [rows] (the list under the Recommended card). Quality chips appear for every
     * quality present; provider, language and type only when there's more than one value to choose.
     */
    fun facets(rows: List<SourceView>): SourceFacets {
        val qualities = QualityBucket.entries.mapNotNull { b ->
            rows.count { it.bucket == b }.takeIf { it > 0 }?.let { FilterChip(b, b.label, it) }
        }
        val providers = rows.groupingBy { it.provider }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { FilterChip(it.key, it.key, it.value) }
        val languages = rows.flatMap { it.languages }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { FilterChip(it.key, it.key, it.value) }
        val types = SourceType.entries.mapNotNull { t ->
            rows.count { it.type == t }.takeIf { it > 0 }?.let { FilterChip(t, t.label, it) }
        }
        return SourceFacets(
            qualities = qualities,
            providers = providers.takeIf { it.size > 1 }.orEmpty(),
            // Languages are only known for some releases: one named language still splits the list.
            languages = languages.takeIf { it.size > 1 || (it.size == 1 && it[0].count < rows.size) }.orEmpty(),
            types = types.takeIf { it.size > 1 }.orEmpty(),
        )
    }

    fun matches(v: SourceView, f: SourceFilters): Boolean =
        (f.qualities.isEmpty() || v.bucket in f.qualities) &&
            (f.providers.isEmpty() || v.provider in f.providers) &&
            (f.languages.isEmpty() || v.languages.any { it in f.languages }) &&
            (f.types.isEmpty() || v.type in f.types)

    /** Rows that pass [f], in its sort order. Ties keep the ranker's order. */
    fun apply(rows: List<SourceView>, f: SourceFilters): List<SourceView> {
        val shown = rows.filter { matches(it, f) }
        return when (f.sort) {
            SourceSort.BEST -> shown
            // Unknown sizes last: they can't be the smallest you can count on.
            SourceSort.SMALLEST -> shown.sortedBy { it.source.sizeBytes ?: Long.MAX_VALUE }
            // Direct links don't need peers: after torrents with seeds, before dead torrents.
            SourceSort.SEEDS -> shown.sortedByDescending { seedRank(it.source) }
        }
    }

    private fun seedRank(s: Source): Long = when (s.kind) {
        SourceKind.TORRENT -> s.seeders?.toLong()?.takeIf { it > 0 }?.plus(1) ?: if (s.seeders == null) 0 else -1
        SourceKind.DIRECT -> 1
        SourceKind.EXTERNAL -> -2
    }

    /** Drops picked chips that no longer exist (a provider whose rows were all replaced). */
    fun prune(f: SourceFilters, facets: SourceFacets): SourceFilters = f.copy(
        qualities = f.qualities.filterTo(HashSet()) { q -> facets.qualities.any { it.value == q } },
        providers = f.providers.filterTo(HashSet()) { p -> facets.providers.any { it.value == p } },
        languages = f.languages.filterTo(HashSet()) { l -> facets.languages.any { it.value == l } },
        types = f.types.filterTo(HashSet()) { t -> facets.types.any { it.value == t } },
    )

    fun <T> toggle(set: Set<T>, value: T): Set<T> = if (value in set) set - value else set + value

    /**
     * "Searching · 3 of 7 sources · 12 found" plus who we're still waiting for, by name.
     * [progress] is null once the search is done (the bar disappears).
     */
    fun status(search: SourceSearch?): SearchStatus {
        if (search == null) return SearchStatus("Getting your sources ready…", null, 0f)
        if (search.total == 0) return SearchStatus("None of your sources handle this yet.", null, null)
        val answered = search.total - search.pending.size
        val found = search.sources.size
        val failedText = search.failed.keys.takeIf { it.isNotEmpty() }?.let { names ->
            (if (names.size == 1) "Didn't answer: " else "${names.size} didn't answer: ") + names.joinToString(", ")
        }
        if (!search.done) {
            val waiting = search.pending.take(2).joinToString(", ") + if (search.pending.size > 2) " +${search.pending.size - 2}" else ""
            return SearchStatus(
                "Searching · $answered of ${search.total} · ${plural(found, "result")}",
                "Still searching: $waiting",
                answered.toFloat() / search.total,
            )
        }
        val from = "${plural(found, "result")} from ${plural(search.total, "source")}"
        return SearchStatus(from, failedText, null)
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}
