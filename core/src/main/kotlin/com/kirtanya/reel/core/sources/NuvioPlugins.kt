package com.kirtanya.reel.core.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One scraper listed in a plugin repository's manifest (Nuvio format). */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    /** Relative to the manifest URL. */
    val filename: String,
    /** "movie" and/or "tv". */
    val supportedTypes: List<String>,
    val enabledByDefault: Boolean,
    val logo: String?,
    val formats: List<String>?,
)

data class PluginRepoManifest(
    val name: String,
    val version: String,
    val description: String?,
    val author: String?,
    val scrapers: List<PluginInfo>,
)

/** Nuvio "local scraper" plugin format: a repo manifest + one JS file per scraper. */
object NuvioPlugins {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val WEB_LINK = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

    fun parseManifest(body: String): PluginRepoManifest {
        val o = json.parseToJsonElement(body) as? JsonObject ?: error("Not a plugin manifest")
        val scrapers = (o["scrapers"] as? JsonArray).orEmpty().mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            PluginInfo(
                id = so.str("id") ?: return@mapNotNull null,
                name = so.str("name") ?: so.str("id")!!,
                version = so.str("version") ?: "?",
                description = so.str("description"),
                filename = so.str("filename") ?: return@mapNotNull null,
                supportedTypes = (so["supportedTypes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::normalizeType) }
                    ?: listOf("movie", "tv"),
                enabledByDefault = (so["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                logo = so.str("logo"),
                formats = (so["formats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }
        return PluginRepoManifest(
            name = o.str("name") ?: "Plugins",
            version = o.str("version") ?: "?",
            description = o.str("description"),
            author = o.str("author"),
            scrapers = scrapers,
        )
    }

    /** Where a scraper's code lives: [filename] resolved against the manifest URL. */
    fun codeUrl(manifestUrl: String, filename: String): String {
        if (filename.contains("://")) return filename
        return manifestUrl.substringBeforeLast('/') + "/" + filename.removePrefix("./").removePrefix("/")
    }

    /** "series"/"show" → "tv". */
    fun normalizeType(type: String) = when (type.lowercase()) {
        "series", "show", "shows", "other" -> "tv"
        else -> type.lowercase()
    }

    /** A plugin's `getStreams` result (JSON array) → sources. */
    fun parseResults(body: String, provider: String): List<Source> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val name = r.str("name")
            val title = r.str("title") ?: name ?: return@mapNotNull null
            val qualityText = r.str("quality")
            val quality = QualityParser.parse(qualityText, title, name)
            val size = (r["size"] as? JsonPrimitive)?.let { it.longOrNull ?: QualityParser.parseSize(it.content) }
            val seeders = (r["seeders"] as? JsonPrimitive)?.intOrNull ?: QualityParser.parseSeeders(title)
            val providerName = r.str("provider")?.let { "$provider · $it" } ?: provider
            val headers = (r["headers"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
            val subtitles = (r["subtitles"] as? JsonArray).orEmpty().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                SubtitleTrack(so.str("url") ?: return@mapNotNull null, so.str("language") ?: so.str("lang") ?: "und", so.str("name"))
            }
            val url = r.str("url")
            val externalUrl = r.str("externalUrl")
            val infoHash = r.str("infoHash")?.lowercase()
            val fileIdx = (r["fileIdx"] as? JsonPrimitive)?.intOrNull
            when {
                url != null && url.startsWith("magnet:", ignoreCase = true) ->
                    Source.fromMagnet(url, title, providerName, quality, fileIdx = fileIdx, filename = r.str("filename"), seeders = seeders, sizeBytes = size)
                infoHash != null -> Source(
                    kind = SourceKind.TORRENT, title = title, provider = providerName, quality = quality,
                    infoHash = infoHash, fileIdx = fileIdx, filename = r.str("filename"), seeders = seeders, sizeBytes = size,
                    subtitles = subtitles,
                )
                // A page to open in another app (no stream of ours). Only web links: a third-party plugin must not
                // be able to fire arbitrary intent schemes.
                url == null && externalUrl != null && externalUrl.matches(WEB_LINK) -> Source(
                    kind = SourceKind.EXTERNAL, title = title, provider = providerName, quality = quality,
                    externalUrl = externalUrl, sizeBytes = size,
                )
                url != null -> Source(
                    kind = SourceKind.DIRECT, title = title, provider = providerName, quality = quality,
                    url = url, headers = headers, sizeBytes = size, subtitles = subtitles,
                )
                else -> null
            }
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
