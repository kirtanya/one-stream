package com.kirtanya.reel.core.sources

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class PluginException(message: String) : Exception(message)

/**
 * Runs one Nuvio-format scraper in a fresh QuickJS sandbox per call.
 * The sandbox can only reach the outside world through the host functions bound here.
 */
class PluginRuntime(
    private val http: HttpFetcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = 30_000,
    private val memoryLimitBytes: Long = 64L * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Calls the plugin's `getStreams` for [req]. [settings] become `SCRAPER_SETTINGS`.
     * [log] receives console output and every URL the plugin fetches.
     */
    suspend fun getStreams(
        code: String,
        providerName: String,
        req: SourceRequest,
        settings: Map<String, String> = emptyMap(),
        log: (String) -> Unit = {},
    ): List<Source> {
        val raw = run(code, req, settings, log)
        return NuvioPlugins.parseResults(raw, providerName)
    }

    /** Returns the raw JSON the plugin produced. */
    suspend fun run(code: String, req: SourceRequest, settings: Map<String, String>, log: (String) -> Unit): String {
        val result = CompletableDeferred<String>()
        val dom = DomBridge()
        val js = QuickJs.create(dispatcher)
        try {
            js.memoryLimit = memoryLimitBytes
            bindHost(js, dom, result, log)
            js.evaluate<Any?>(PluginJs.PRELUDE, "prelude.js", false)
            js.evaluate<Any?>(globals(req, settings), "globals.js", false)
            js.evaluate<Any?>(PluginJs.wrapModule(code), "plugin.js", false)
            return withTimeout(timeoutMs) {
                js.evaluate<Any?>(PluginJs.call(args(req)), "call.js", false)
                result.await()
            }
        } catch (e: PluginException) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw PluginException("Took longer than ${timeoutMs / 1000}s")
        } catch (e: Exception) {
            throw PluginException(e.message ?: e::class.simpleName ?: "Plugin failed")
        } finally {
            runCatching { js.close() }
        }
    }

    private fun bindHost(js: QuickJs, dom: DomBridge, result: CompletableDeferred<String>, log: (String) -> Unit) {
        js.function("__host_log") { a -> log("[${a[0]}] ${a.getOrNull(1)}"); null }
        js.function("__host_done") { a -> result.complete(a[0] as String); null }
        js.function("__host_fail") { a -> result.completeExceptionally(PluginException((a[0] as? String)?.lineSequence()?.firstOrNull() ?: "Plugin failed")); null }
        js.asyncFunction("__host_sleep") { a -> delay(((a[0] as? Number)?.toLong() ?: 0L).coerceIn(0, 10_000)); null }
        js.asyncFunction("__host_fetch") { a -> fetch(a, log) }

        js.function("__host_atob") { a -> String(Base64.getDecoder().decode((a[0] as String).trim()), Charsets.ISO_8859_1) }
        js.function("__host_btoa") { a -> Base64.getEncoder().encodeToString((a[0] as String).toByteArray(Charsets.ISO_8859_1)) }
        js.function("__host_b64_utf8") { a -> Base64.getEncoder().encodeToString((a[0] as String).toByteArray()) }
        js.function("__host_b64_to_utf8") { a -> String(Base64.getDecoder().decode((a[0] as String).trim())) }
        js.function("__host_hex_b64") { a -> Base64.getEncoder().encodeToString(hexToBytes(a[0] as String)) }
        js.function("__host_hash") { a ->
            MessageDigest.getInstance(a[0] as String).digest((a[1] as String).toByteArray()).joinToString("") { "%02x".format(it) }
        }

        js.function("__host_dom_load") { a -> dom.load(a[0] as String) }
        js.function("__host_dom_fragment") { a -> dom.fragment(a[0] as String) }
        js.function("__host_dom_select") { a -> dom.select(a.int(0), a[1] as String) }
        js.function("__host_dom_children") { a -> dom.children(a.int(0)) }
        js.function("__host_dom_parent") { a -> dom.parent(a.int(0)) }
        js.function("__host_dom_next") { a -> dom.next(a.int(0)) }
        js.function("__host_dom_prev") { a -> dom.prev(a.int(0)) }
        js.function("__host_dom_is") { a -> dom.matches(a.int(0), a[1] as String) }
        js.function("__host_dom_attr") { a -> dom.attr(a.int(0), a[1] as String) }
        js.function("__host_dom_attrs") { a -> dom.attrs(a.int(0)) }
        js.function("__host_dom_text") { a -> dom.text(a.int(0)) }
        js.function("__host_dom_html") { a -> dom.html(a.int(0)) }
        js.function("__host_dom_outer") { a -> dom.outer(a.int(0)) }
        js.function("__host_dom_tag") { a -> dom.tag(a.int(0)) }
    }

    /** JS fetch → [HttpFetcher]. Errors come back as `{error}` so JS can throw a TypeError. */
    private suspend fun fetch(a: Array<Any?>, log: (String) -> Unit): String {
        val url = a[0] as String
        log("fetch ${a[1]} $url")
        return try {
            val headers = (a[2] as? String)?.let { h ->
                json.parseToJsonElement(h).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            }.orEmpty()
            val response = http.fetch(
                FetchRequest(url = url, method = a[1] as String, headers = headers, body = (a[3] as? String)?.toByteArray(), followRedirects = a[4] as? Boolean ?: true),
            )
            buildJsonObject {
                put("status", response.status)
                put("url", response.url)
                put("headers", JsonObject(response.headers.mapKeys { it.key.lowercase() }.mapValues { JsonPrimitive(it.value) }))
                put("text", response.text)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: e::class.simpleName) }.toString()
        }
    }

    private fun globals(req: SourceRequest, settings: Map<String, String>): String {
        val reel = buildJsonObject {
            put("tmdbId", req.tmdbId)
            put("imdbId", req.imdbId?.let(::JsonPrimitive) ?: JsonNull)
            put("type", if (req.type.path == "tv") "tv" else "movie")
            put("title", req.title)
            put("year", req.year?.let(::JsonPrimitive) ?: JsonNull)
            put("season", req.season?.let(::JsonPrimitive) ?: JsonNull)
            put("episode", req.episode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val s = JsonObject(settings.mapValues { JsonPrimitive(it.value) })
        return "var TMDB_API_KEY = '$TMDB_KEY_PLACEHOLDER'; var SCRAPER_SETTINGS = $s; var REEL = $reel;"
    }

    private fun args(req: SourceRequest) = buildJsonObject {
        put("tmdbId", req.tmdbId.toString())
        put("mediaType", if (req.type.path == "tv") "tv" else "movie")
        put("season", req.season?.let(::JsonPrimitive) ?: JsonNull)
        put("episode", req.episode?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    private fun Array<Any?>.int(i: Int) = (this[i] as Number).toInt()

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
