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
) {
    /** Reel can use streams and subtitles; catalog-only addons do nothing here (yet). */
    val usable: Boolean get() = hasStreams || hasSubtitles

    /** The addon's settings page: base URL + /configure. */
    val configureUrl: String get() = StremioAddon.baseUrl(manifestUrl) + "/configure"
}

enum class StoreFilter(val label: String) { ALL("All"), STREAMS("Streams"), SUBTITLES("Subtitles") }

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
            )
        }.distinctBy { it.manifestUrl }
    }

    /** Usable addons matching [query] (name, then description) and [filter]; name matches first. */
    fun search(all: List<StoreAddon>, query: String, filter: StoreFilter): List<StoreAddon> {
        val q = query.trim().lowercase()
        return all.asSequence()
            .filter { it.usable }
            .filter {
                when (filter) {
                    StoreFilter.ALL -> true
                    StoreFilter.STREAMS -> it.hasStreams
                    StoreFilter.SUBTITLES -> it.hasSubtitles
                }
            }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
            .sortedWith(compareBy<StoreAddon>({ q.isNotEmpty() && !it.name.lowercase().contains(q) }, { it.needsSetup }, { it.name.lowercase() }))
            .distinctBy { it.id }
            .toList()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
