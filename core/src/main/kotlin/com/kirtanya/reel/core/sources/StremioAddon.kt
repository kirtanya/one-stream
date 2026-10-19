package com.kirtanya.reel.core.sources

import com.kirtanya.reel.core.tmdb.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The parts of a Stremio addon manifest Reel uses. */
data class StremioManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val types: List<String>,
    /** resource name → (types it applies to, id prefixes) */
    val resources: Map<String, Pair<List<String>?, List<String>?>>,
    val idPrefixes: List<String>?,
) {
    val hasStreams: Boolean get() = resources.containsKey("stream")
    val hasSubtitles: Boolean get() = resources.containsKey("subtitles")

    /** Can this addon answer [resource] ("stream", "subtitles") for [req] with an id in one of our formats? */
    fun streamIdFor(req: SourceRequest, resource: String = "stream"): String? {
        val (resTypes, resPrefixes) = resources[resource] ?: return null
        val stremioType = StremioAddon.typeOf(req.type)
        if (!(resTypes ?: types).contains(stremioType)) return null
        val prefixes = resPrefixes ?: idPrefixes
        val imdb = req.imdbId?.let { if (req.type == MediaType.TV) "$it:${req.season}:${req.episode}" else it }
        val tmdb = "tmdb:${req.tmdbId}" + if (req.type == MediaType.TV) ":${req.season}:${req.episode}" else ""
        return when {
            prefixes == null -> imdb ?: tmdb
            imdb != null && prefixes.any { imdb.startsWith(it) } -> imdb
            prefixes.any { tmdb.startsWith(it) } -> tmdb
            else -> null
        }
    }
}

/** Stremio addon protocol: manifest parsing, request URLs, stream parsing. */
object StremioAddon {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun typeOf(type: MediaType) = if (type == MediaType.TV) "series" else "movie"

    /** "https://x/abc/manifest.json" (or "stremio://x/…") → "https://x/abc". */
    fun baseUrl(manifestUrl: String): String =
        manifestUrl.trim()
            .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .substringBefore("?")
            .removeSuffix("/")
            .removeSuffix("/manifest.json")

    fun manifestUrl(input: String): String = baseUrl(input) + "/manifest.json"

    fun streamUrl(manifestUrl: String, req: SourceRequest, id: String): String =
        "${baseUrl(manifestUrl)}/stream/${typeOf(req.type)}/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("%3A", ":")}.json"

    fun parseManifest(body: String): StremioManifest {
        val o = json.parseToJsonElement(body) as? JsonObject ?: error("Not an addon manifest")
        val types = o.strings("types")
        val resources = LinkedHashMap<String, Pair<List<String>?, List<String>?>>()
        (o["resources"] as? JsonArray)?.forEach { r ->
            when (r) {
                is JsonPrimitive -> resources[r.content] = null to null
                is JsonObject -> r.str("name")?.let { resources[it] = r.stringsOrNull("types") to r.stringsOrNull("idPrefixes") }
                else -> Unit
            }
        }
        return StremioManifest(
            id = o.str("id") ?: error("Manifest has no id"),
            name = o.str("name") ?: o.str("id")!!,
            version = o.str("version") ?: "?",
            description = o.str("description"),
            logo = o.str("logo"),
            types = types,
            resources = resources,
            idPrefixes = o.stringsOrNull("idPrefixes"),
        )
    }

    /** `{"streams":[…]}` → sources. Unknown or unplayable entries are skipped. */
    fun parseStreams(body: String, provider: String): List<Source> {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["streams"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val name = s.str("name").orEmpty()
            val description = s.str("description") ?: s.str("title").orEmpty()
            val hints = s["behaviorHints"] as? JsonObject
            val filename = hints?.str("filename")
            val text = listOf(name, description, filename).filter { !it.isNullOrBlank() }.joinToString("\n")
            val quality = QualityParser.parse(name, description, filename)
            val size = (hints?.get("videoSize") as? JsonPrimitive)?.longOrNull ?: QualityParser.parseSize(description, name)
            val seeders = QualityParser.parseSeeders(description, name)
            val subtitles = (s["subtitles"] as? JsonArray).orEmpty().mapNotNull { sub ->
                val so = sub as? JsonObject ?: return@mapNotNull null
                SubtitleTrack(so.str("url") ?: return@mapNotNull null, so.str("lang") ?: "und", so.str("id"))
            }
            val title = description.ifBlank { name }
            val base = Source(
                kind = SourceKind.DIRECT, title = title, provider = provider, quality = quality,
                sizeBytes = size, seeders = seeders, subtitles = subtitles, filename = filename,
                bingeGroup = hints?.str("bingeGroup"),
            )
            val infoHash = s.str("infoHash")?.lowercase()
            when {
                infoHash != null -> base.copy(
                    kind = SourceKind.TORRENT,
                    infoHash = infoHash,
                    fileIdx = (s["fileIdx"] as? JsonPrimitive)?.intOrNull,
                    trackers = s.strings("sources").filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
                )
                s.str("url")?.startsWith("magnet:") == true ->
                    Source.fromMagnet(s.str("url")!!, title, provider, quality, filename = filename, seeders = seeders, sizeBytes = size)
                s.str("url") != null -> base.copy(
                    url = s.str("url"),
                    headers = (hints?.get("proxyHeaders") as? JsonObject)?.let { (it["request"] as? JsonObject)?.toStringMap() }.orEmpty(),
                )
                s.str("externalUrl") != null -> base.copy(
                    kind = SourceKind.EXTERNAL,
                    externalUrl = s.str("externalUrl"),
                    appUrl = s.str("androidUrl"),
                    tvAppUrl = s.str("androidTvUrl"),
                    title = listOf(name, description).filter { it.isNotBlank() }.joinToString(" · "),
                )
                else -> null
            }?.takeIf { text.isNotBlank() || it.kind != SourceKind.EXTERNAL }
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.stringsOrNull(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    private fun JsonObject.strings(key: String) = stringsOrNull(key).orEmpty()
    private fun JsonObject.toStringMap() = entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
}
