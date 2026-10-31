package com.kirtanya.reel.core.sources

import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
    /** The only subtitle language One Stream shows (the user reads English subtitles only). */
    const val ONLY_LANGUAGE = "en"
    /** With one language, more of its tracks: another cut or release often needs a different file. */
    const val PER_LANGUAGE = 6

    /** Keeps [ONLY_LANGUAGE] tracks; the rest are never listed. */
    fun onlyWanted(tracks: List<SubtitleTrack>): List<SubtitleTrack> = tracks.filter { normalizeLanguage(it.language) == ONLY_LANGUAGE }

    /**
     * Subtitle tracks inside the video file, by language code (null / "und" = unlabelled): which to list.
     * [ONLY_LANGUAGE] ones; unlabelled ones only when there's none (an unlabelled track is usually English).
     */
    fun embeddedToList(languages: List<String?>): List<Int> {
        val wanted = languages.indices.filter { i -> languages[i]?.let(::normalizeLanguage) == ONLY_LANGUAGE }
        if (wanted.isNotEmpty()) return wanted
        return languages.indices.filter { i -> languages[i].let { it == null || normalizeLanguage(it) == "und" } }
    }

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
        perLanguage: Int = PER_LANGUAGE,
    ): List<SubtitleTrack> = searchDetailed(req, addons, http, filename, videoSize, timeoutMs, perLanguage).tracks

    /**
     * [search], keeping what each addon answered (request URL without secrets, HTTP status, tracks per
     * language, error): the player logs it per playback and the SourcesProbe `subsTest` prints it.
     * Only [ONLY_LANGUAGE] tracks are kept.
     */
    suspend fun searchDetailed(
        req: SourceRequest,
        addons: List<Pair<String, StremioManifest>>,
        http: HttpFetcher,
        filename: String? = null,
        videoSize: Long? = null,
        timeoutMs: Long = 6_000,
        perLanguage: Int = PER_LANGUAGE,
    ): SubtitleSearch = coroutineScope {
        val answers = addons.filter { it.second.hasSubtitles }.map { (url, manifest) ->
            async { ask(req, url, manifest, http, filename, videoSize, timeoutMs) }
        }.awaitAll()
        // Addons (OpenSubtitles v3) answer every language at once and can't be asked for one: keep English here.
        val tracks = onlyWanted(answers.flatMap { it.tracks }.distinctBy { it.url }).groupBy { it.language }.flatMap { it.value.take(perLanguage) }
        SubtitleSearch(answers, tracks)
    }

    private suspend fun ask(
        req: SourceRequest, manifestUrl: String, manifest: StremioManifest, http: HttpFetcher,
        filename: String?, videoSize: Long?, timeoutMs: Long,
    ): AddonAnswer {
        val id = manifest.streamIdFor(req, resource = "subtitles")
            ?: return AddonAnswer(manifest.name, null, null, emptyList(), error = if (req.imdbId == null) "needs an IMDb id this title doesn't have" else "doesn't cover this ${StremioAddon.typeOf(req.type)}")
        val requestUrl = url(manifestUrl, req, id, filename, videoSize)
        val safe = safeUrl(requestUrl)
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                val r = http.fetch(FetchRequest(requestUrl))
                if (r.status in 200..299) AddonAnswer(manifest.name, safe, r.status, parse(r.text, manifest.name))
                else AddonAnswer(manifest.name, safe, r.status, emptyList(), error = "answered ${r.status}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddonAnswer(manifest.name, safe, null, emptyList(), error = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return result ?: AddonAnswer(manifest.name, safe, null, emptyList(), error = "no answer in ${timeoutMs} ms")
    }

    /**
     * A request URL fit for logs: an addon's config lives in the path before the resource (debrid keys,
     * tokens), and in the query; both are replaced. `https://h/abc123/subtitles/movie/tt1.json` →
     * `https://h/…/subtitles/movie/tt1.json`; a manifest link likewise (`https://h/…/manifest.json`).
     */
    fun safeUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val scheme = noQuery.substringBefore("://", "")
        val rest = noQuery.substringAfter("://")
        val host = rest.substringBefore('/')
        val path = rest.removePrefix(host)
        val at = path.indexOf("/subtitles/")
        val tail = when {
            at > 0 -> "/…" + path.substring(at)
            at == 0 -> path
            path.isEmpty() || path == "/" || path == "/manifest.json" -> path
            path.endsWith("/manifest.json") -> "/…/manifest.json"
            else -> "/…"
        }
        return (if (scheme.isEmpty()) "" else "$scheme://") + host + tail + if (url.contains('?')) "?…" else ""
    }

    /**
     * Every track the player gets: the source's own (languages normalised) then the addons', one per URL.
     * All are attached, even when none is shown, so the Subtitles menu always lists them.
     */
    fun merge(fromSource: List<SubtitleTrack>, fromAddons: List<SubtitleTrack>): List<SubtitleTrack> =
        (fromSource.map { it.copy(language = normalizeLanguage(it.language), format = it.format ?: formatOf(it.url)) } + fromAddons)
            .distinctBy { it.url }

    /**
     * The track that shows by itself: the first in [preferred] language, or none (Off, or that language
     * isn't there: the tracks stay listed in the menu). The player's track selector applies the same
     * rule (preferred text language, undetermined and "default" flags ignored); this is for logs and tests.
     */
    fun autoSelect(tracks: List<SubtitleTrack>, preferred: String?): SubtitleTrack? {
        val want = preferred?.let(::normalizeLanguage) ?: return null
        return tracks.firstOrNull { normalizeLanguage(it.language) == want }
    }

    /** How long the start may wait for subtitle addons: briefly when a language should show, else not at all. */
    fun startWaitMs(languageWanted: Boolean): Long = if (languageWanted) 400L else 0L

    /** Log lines for one search (SourcesProbe `subsTest`, player logs): one per addon, then the total. */
    fun describe(search: SubtitleSearch): List<String> =
        search.answers.map { a ->
            listOfNotNull(
                "addon \"${a.addon}\"",
                a.requestUrl?.let { "url=$it" },
                a.status?.let { "status=$it" },
                "tracks=${a.tracks.size}",
                a.perLanguage.takeIf { it.isNotEmpty() }?.let { m -> "langs=" + m.entries.joinToString(",") { "${it.key}:${it.value}" } },
                a.error?.let { "error=$it" },
            ).joinToString(" ")
        } + ("total addons=${search.answers.size} tracks=${search.tracks.size} langs=" +
            search.tracks.groupingBy { it.language }.eachCount().entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" })

    /** File extension → subtitle format, or null if it doesn't look like one. */
    fun formatOf(name: String): String? =
        name.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp") }

    /**
     * "eng" / "en" / "EN" / "English" / "en-US" / "English (UK)" → "en"; blank → "und"; unknown codes
     * are kept (lower case). Region and script are dropped: the setting and the menu work per language.
     */
    /**
     * Does [tracks] contain [want] (a language the user reads; null / "off" = none)? Late subtitle
     * tracks in that language are attached at once instead of waiting for a pause.
     */
    fun containsLanguage(tracks: List<SubtitleTrack>, want: String?): Boolean {
        if (want.isNullOrBlank() || want == "off") return false
        val w = normalizeLanguage(want)
        return tracks.any { normalizeLanguage(it.language) == w }
    }

    fun normalizeLanguage(code: String): String {
        val name = code.substringBefore('(').trim()
        if (name.isEmpty()) return "und"
        val c = name.lowercase().let { if (it.length > 3 && (it[2] == '-' || it[2] == '_' || it.getOrNull(3) == '-' || it.getOrNull(3) == '_')) it.substringBefore('-').substringBefore('_') else it }
        LANGS[c]?.let { return it }
        if (c.length == 2) return c
        if (c.length == 3) {
            Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language == c }.getOrDefault(false) }?.let { return it }
        }
        return Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(name, true) }?.language ?: c
    }

    /** Bibliographic ISO 639-2 codes Locale doesn't map, plus common names. */
    private val LANGS = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
        "pob" to "pt", "pb" to "pt", "spn" to "es",
    )
}

/** What one subtitle addon answered for one title (no secrets in [requestUrl]). */
data class AddonAnswer(
    val addon: String,
    val requestUrl: String?,
    val status: Int?,
    val tracks: List<SubtitleTrack>,
    val error: String? = null,
) {
    /** Tracks per language, most first. */
    val perLanguage: Map<String, Int>
        get() = tracks.groupingBy { it.language }.eachCount().entries.sortedByDescending { it.value }.associate { it.key to it.value }
}

/** Every addon's answer plus the merged, capped tracks the player attaches. */
data class SubtitleSearch(val answers: List<AddonAnswer>, val tracks: List<SubtitleTrack>)

/**
 * The subtitle language setting as stored. Unset (new installs, and everyone before Off was stored)
 * means English; Off is its own value, so choosing Off is never mistaken for "never chosen".
 */
object SubtitleLanguagePref {
    const val DEFAULT = "en"
    const val OFF = "off"

    /** Stored value → language code, or null for Off. */
    fun decode(stored: String?): String? = when {
        stored.isNullOrBlank() -> DEFAULT
        stored.trim().equals(OFF, ignoreCase = true) -> null
        else -> Subtitles.normalizeLanguage(stored)
    }

    /** Language code (null = Off) → what to store. */
    fun encode(language: String?): String = language?.let(Subtitles::normalizeLanguage) ?: OFF
}
