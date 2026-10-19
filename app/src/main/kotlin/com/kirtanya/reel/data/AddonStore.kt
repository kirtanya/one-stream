package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.store.AddonCatalog
import com.kirtanya.reel.core.store.StoreAddon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class StoreCatalog(val url: String, val name: String, val builtIn: Boolean = false)

data class StoreState(val catalogs: List<StoreCatalog>, val addons: List<StoreAddon> = emptyList(), val failed: List<String> = emptyList())

/**
 * The addon store: Stremio's official collection by default, plus any catalogs the user adds
 * (a JSON list, or an addon whose manifest lists `addonCatalogs`). Listings are cached for 6 h.
 */
class AddonStore(context: Context, private val http: HttpFetcher) {
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(StoreCatalog.serializer())
    private var loadedAt = 0L

    private val _state = MutableStateFlow(StoreState(loadCatalogs()))
    val state: StateFlow<StoreState> = _state.asStateFlow()

    /** Fetches every catalog (in parallel); one failing doesn't hide the others. */
    suspend fun refresh(force: Boolean = false) {
        if (!force && _state.value.addons.isNotEmpty() && System.currentTimeMillis() - loadedAt < 6 * 60 * 60_000L) return
        val catalogs = _state.value.catalogs
        val results = coroutineScope {
            catalogs.map { c -> async { c to runCatching { fetchCatalog(c) } } }.awaitAll()
        }
        results.forEach { (_, r) -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
        _state.value = _state.value.copy(
            addons = results.flatMap { it.second.getOrDefault(emptyList()) },
            failed = results.filter { it.second.isFailure }.map { it.first.name },
        )
        loadedAt = System.currentTimeMillis()
    }

    /** Adds a catalog by link: a JSON listing, or an addon manifest that offers addon catalogs. */
    suspend fun addCatalog(link: String): String {
        val url = link.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        if (!url.startsWith("http", ignoreCase = true)) throw SourceSetupException("That isn't a link")
        if (_state.value.catalogs.any { it.url == url }) throw SourceSetupException("That catalog is already here")
        val body = get(url)
        val name = when {
            AddonCatalog.parse(body, "").isNotEmpty() -> url.substringAfter("://").substringBefore('/')
            catalogUrlsIn(url, body).isNotEmpty() -> (runCatching { (json.parseToJsonElement(body) as JsonObject)["name"] as? JsonPrimitive }.getOrNull()?.content)
                ?: url.substringAfter("://").substringBefore('/')
            else -> throw SourceSetupException("No addons listed at that link")
        }
        save(_state.value.catalogs + StoreCatalog(url, name))
        refresh(force = true)
        return "Added catalog $name"
    }

    fun removeCatalog(url: String) {
        save(_state.value.catalogs.filterNot { it.url == url && !it.builtIn })
        _state.value = _state.value.copy(addons = _state.value.addons.filterNot { it.catalog == url })
    }

    private suspend fun fetchCatalog(c: StoreCatalog): List<StoreAddon> {
        val body = get(c.url)
        AddonCatalog.parse(body, c.url).takeIf { it.isNotEmpty() }?.let { return it }
        // An addon manifest with addonCatalogs: fetch each listed catalog.
        return catalogUrlsIn(c.url, body).flatMap { u -> AddonCatalog.parse(get(u), c.url) }
    }

    /** `{"addonCatalogs":[{"type":"all","id":"x"}]}` in a manifest → `<base>/addon_catalog/all/x.json`. */
    private fun catalogUrlsIn(manifestUrl: String, body: String): List<String> {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val base = manifestUrl.substringBefore('?').removeSuffix("/manifest.json")
        return (o["addonCatalogs"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val type = (c["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val id = (c["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            "$base/addon_catalog/$type/$id.json"
        }.take(5)
    }

    private suspend fun get(url: String): String {
        val r = try {
            http.fetch(FetchRequest(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceSetupException("Couldn't reach that link")
        }
        if (r.status !in 200..299) throw SourceSetupException("That link answered ${r.status}")
        return r.text
    }

    private fun save(catalogs: List<StoreCatalog>) {
        prefs.edit().putString("catalogs", json.encodeToString(list, catalogs.filterNot { it.builtIn })).apply()
        _state.value = _state.value.copy(catalogs = catalogs)
    }

    private fun loadCatalogs(): List<StoreCatalog> =
        listOf(StoreCatalog(AddonCatalog.OFFICIAL, "Stremio official", builtIn = true)) +
            (prefs.getString("catalogs", null)?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty())
}
