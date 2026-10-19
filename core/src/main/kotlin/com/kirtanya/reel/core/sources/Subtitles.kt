package com.kirtanya.reel.core.sources

import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Subtitle addons (Stremio `subtitles` resource), e.g. the official OpenSubtitles v3 addon. */
object Subtitles {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `/subtitles/{type}/{id}[/{extra}].json`, extra = filename / videoSize when we know them. */
    fun url(manifestUrl: String, req: SourceRequest, id: String, filename: String? = null, videoSize: Long? = null): String {
        val extra = listOfNotNull(
            videoSize?.let { "videoSize=$it" },
            filename?.let { "filename=" + URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") },
        ).joinToString("&")
        val base = "${StremioAddon.baseUrl(manifestUrl)}/subtitles/${StremioAddon.typeOf(req.type)}/$id"
        return if (extra.isEmpty()) "$base.json" else "$base/$extra.json"
    }

    fun parse(body: String, provider: String): List<SubtitleTrack> {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["subtitles"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val url = (s["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val lang = (s["lang"] as? JsonPrimitive)?.content ?: "und"
            val fileName = (s["subtitleFileName"] as? JsonPrimitive)?.content
            SubtitleTrack(url = url, language = normalizeLanguage(lang), name = provider, format = formatOf(fileName ?: url))
        }
    }

    /**
     * Asks every subtitle addon at once (each gets [timeoutMs]) and merges the answers,
     * keeping at most [perLanguage] tracks per language so menus stay short.
     */
    suspend fun search(
        req: SourceRequest,
        addons: List<Pair<String, StremioManifest>>,
        http: HttpFetcher,
        filename: String? = null,
        videoSize: Long? = null,
        timeoutMs: Long = 6_000,
        perLanguage: Int = 3,
    ): List<SubtitleTrack> = coroutineScope {
        addons.filter { it.second.resources.containsKey("subtitles") }.map { (url, manifest) ->
            async {
                val id = manifest.streamIdFor(req, resource = "subtitles") ?: return@async emptyList()
                withTimeoutOrNull(timeoutMs) {
                    runCatching {
                        val r = http.fetch(FetchRequest(url(url, req, id, filename, videoSize)))
                        if (r.status in 200..299) parse(r.text, manifest.name) else emptyList()
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.url }.groupBy { it.language }.flatMap { it.value.take(perLanguage) }
    }

    /** File extension → subtitle format, or null if it doesn't look like one. */
    fun formatOf(name: String): String? =
        name.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp") }

    /** "eng" / "en" / "English" → "en"; unknown codes are kept as they are. */
    fun normalizeLanguage(code: String): String {
        val c = code.trim().lowercase()
        LANGS[c]?.let { return it }
        if (c.length == 2) return c
        if (c.length == 3) {
            Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language == c }.getOrDefault(false) }?.let { return it }
        }
        return Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(code.trim(), true) }?.language ?: c
    }

    /** Bibliographic ISO 639-2 codes Locale doesn't map, plus common names. */
    private val LANGS = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
        "pob" to "pt", "pb" to "pt", "spn" to "es",
    )
}
