package com.kirtanya.reel.core.store

import com.kirtanya.reel.core.sources.StremioAddon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One addon listed in a store catalog. */
data class StoreAddon(
    val manifestUrl: String,
    val id: String,
    val name: String,
    val description: String,
    val logo: String?,
    val version: String,
    val hasStreams: Boolean,
    val hasSubtitles: Boolean,
    /** Has a /configure page (keys, languages, …). */
    val configurable: Boolean,
    /** Can't be used until set up on its /configure page. */
    val needsSetup: Boolean,
    /** Which catalog listed it. */
    val catalog: String,
    /** Content types from the manifest: movie, series, channel, tv, anime… */
    val types: List<String> = emptyList(),
    /** Also offers catalogs (browse lists); One Stream doesn't show those, but it's what the addon is. */
    val hasCatalogs: Boolean = false,
) {
    /** Reel can use streams and subtitles; catalog-only addons do nothing here (yet). */
    val usable: Boolean get() = hasStreams || hasSubtitles

    /** The addon's settings page: base URL + /configure. */
    val configureUrl: String get() = StremioAddon.baseUrl(manifestUrl) + "/configure"
}

/** What an addon provides (the store's "kind" chips). */
enum class StoreKind(val label: String) { STREAMS("Streams"), SUBTITLES("Subtitles"), CATALOGS("Catalogs") }

/** Where an addon stands on this device. */
enum class StoreStatus(val label: String) { ADDED("Added"), NOT_ADDED("Not added"), NEEDS_SETUP("Needs setup") }

/**
 * The store's search text and picked chips. Within a group any picked value matches;
 * across groups all must match. Empty group = no filter.
 */
data class StoreQuery(
    val text: String = "",
    val kinds: Set<StoreKind> = emptySet(),
    val status: Set<StoreStatus> = emptySet(),
    val types: Set<String> = emptySet(),
    val catalogs: Set<String> = emptySet(),
) {
    val hasChips: Boolean get() = kinds.isNotEmpty() || status.isNotEmpty() || types.isNotEmpty() || catalogs.isNotEmpty()
}

data class StoreChip<T>(val value: T, val label: String, val count: Int)

/** Chips worth showing: groups with a single value are left out (nothing to choose). */
data class StoreFacets(
    val kinds: List<StoreChip<StoreKind>>,
    val status: List<StoreChip<StoreStatus>>,
    val types: List<StoreChip<String>>,
    val catalogs: List<StoreChip<String>>,
)

/**
 * Addon catalogs: Stremio's official collection (a JSON array of `{transportUrl, manifest}`)
 * and any Stremio `addon_catalog` response (`{"addons":[…]}`) share one shape.
 */
object AddonCatalog {
    /** Stremio's official, curated addon collection. */
    const val OFFICIAL = "https://api.strem.io/addonscollection.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, catalog: String): List<StoreAddon> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["addons"] as? JsonArray).orEmpty()
            else -> emptyList()
        }
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.str("transportUrl")?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
            val m = o["manifest"] as? JsonObject ?: return@mapNotNull null
            val resources = (m["resources"] as? JsonArray).orEmpty().mapNotNull { r ->
                when (r) {
                    is JsonPrimitive -> r.content
                    is JsonObject -> r.str("name")
                    else -> null
                }
            }.toSet()
            val hints = m["behaviorHints"] as? JsonObject
            StoreAddon(
                manifestUrl = url,
                id = m.str("id") ?: url,
                name = m.str("name")?.trim() ?: return@mapNotNull null,
                description = m.str("description")?.trim().orEmpty(),
                logo = m.str("logo")?.takeIf { it.startsWith("http") },
                version = m.str("version") ?: "?",
                hasStreams = "stream" in resources,
                hasSubtitles = "subtitles" in resources,
                configurable = (hints?.get("configurable") as? JsonPrimitive)?.booleanOrNull == true,
                needsSetup = (hints?.get("configurationRequired") as? JsonPrimitive)?.booleanOrNull == true,
                catalog = catalog,
                types = (m["types"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                hasCatalogs = "catalog" in resources || ((m["catalogs"] as? JsonArray)?.isNotEmpty() == true),
            )
        }.distinctBy { it.manifestUrl }
    }

    /** The store's own label for a manifest content type. */
    fun typeLabel(type: String): String = when (type.lowercase()) {
        "movie" -> "Movies"
        "series" -> "Series"
        "channel" -> "Channels"
        "tv" -> "Live TV"
        "anime" -> "Anime"
        "events" -> "Events"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    fun kinds(a: StoreAddon): Set<StoreKind> = buildSet {
        if (a.hasStreams) add(StoreKind.STREAMS)
        if (a.hasSubtitles) add(StoreKind.SUBTITLES)
        if (a.hasCatalogs) add(StoreKind.CATALOGS)
    }

    /** Added wins over Needs setup: an addon you already set up and added is simply added. */
    fun status(a: StoreAddon, installedIds: Set<String>): StoreStatus = when {
        a.id in installedIds -> StoreStatus.ADDED
        a.needsSetup -> StoreStatus.NEEDS_SETUP
        else -> StoreStatus.NOT_ADDED
    }

    /**
     * What the store lists: usable addons (streams or subtitles; catalog-only addons do nothing in
     * One Stream), one per addon id even when several catalogs list it.
     */
    fun listed(all: List<StoreAddon>): List<StoreAddon> = all.filter { it.usable }.distinctBy { it.id }

    /** Chips with counts over everything listed, so a count never depends on the other picks. */
    fun facets(all: List<StoreAddon>, installedIds: Set<String>, catalogName: (String) -> String = { it }): StoreFacets {
        val listed = listed(all)
        fun <T> chips(values: List<T>, label: (T) -> String, count: (T) -> Int) =
            values.map { StoreChip(it, label(it), count(it)) }.filter { it.count > 0 }
        val kinds = chips(StoreKind.entries, { it.label }) { k -> listed.count { k in kinds(it) } }
        val status = chips(StoreStatus.entries, { it.label }) { st -> listed.count { status(it, installedIds) == st } }
        val types = listed.flatMap { a -> a.types.map { it.lowercase() }.distinct() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { StoreChip(it.key, typeLabel(it.key), it.value) }
        val catalogs = listed.groupingBy { it.catalog }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { StoreChip(it.key, catalogName(it.key), it.value) }
        return StoreFacets(
            kinds = kinds.takeIf { it.size > 1 }.orEmpty(),
            status = status.takeIf { it.size > 1 }.orEmpty(),
            types = types.takeIf { it.size > 1 }.orEmpty(),
            catalogs = catalogs.takeIf { it.size > 1 }.orEmpty(),
        )
    }

    /** Listed addons matching [q]: name matches before description matches, ready-to-add before needs-setup. */
    fun search(all: List<StoreAddon>, q: StoreQuery, installedIds: Set<String> = emptySet()): List<StoreAddon> {
        val text = q.text.trim().lowercase()
        return listed(all)
            .asSequence()
            .filter { q.kinds.isEmpty() || kinds(it).any { k -> k in q.kinds } }
            .filter { q.status.isEmpty() || status(it, installedIds) in q.status }
            .filter { q.types.isEmpty() || it.types.any { t -> t.lowercase() in q.types } }
            .filter { q.catalogs.isEmpty() || it.catalog in q.catalogs }
            .filter { text.isEmpty() || it.name.lowercase().contains(text) || it.description.lowercase().contains(text) }
            .sortedWith(compareBy<StoreAddon>({ text.isNotEmpty() && !it.name.lowercase().contains(text) }, { it.needsSetup }, { it.name.lowercase() }))
            .toList()
    }

    /** Drops picked chips that are no longer offered (the catalog reloaded without them). */
    fun prune(q: StoreQuery, f: StoreFacets): StoreQuery = q.copy(
        kinds = q.kinds.filterTo(HashSet()) { k -> f.kinds.any { it.value == k } },
        status = q.status.filterTo(HashSet()) { s -> f.status.any { it.value == s } },
        types = q.types.filterTo(HashSet()) { t -> f.types.any { it.value == t } },
        catalogs = q.catalogs.filterTo(HashSet()) { c -> f.catalogs.any { it.value == c } },
    )

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
