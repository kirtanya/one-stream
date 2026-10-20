package com.kirtanya.reel.core.sources

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
)

data class FetchResponse(val status: Int, val url: String, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** All network access for addons and plugins goes through here (easy to fake in tests). */
fun interface HttpFetcher {
    suspend fun fetch(request: FetchRequest): FetchResponse
}

/** Placeholder plugins see as `TMDB_API_KEY`; requests carrying it are signed with Reel's token instead. */
const val TMDB_KEY_PLACEHOLDER = "reel-tmdb-key"

/**
 * OkHttp-backed fetcher. TMDB requests made with [TMDB_KEY_PLACEHOLDER] get the
 * `api_key` removed and Reel's read token added as a Bearer header.
 */
/** The browser user agent sent to sources and direct links (some CDNs refuse Java/okhttp agents). */
const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

class OkHttpFetcher(
    private val client: OkHttpClient,
    private val tmdbBearer: String? = null,
    private val userAgent: String = CHROME_UA,
) : HttpFetcher {
    private val noRedirects by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** Runs on IO: callers include Compose effects on the main thread, and reading a body is network I/O. */
    override suspend fun fetch(request: FetchRequest): FetchResponse = withContext(Dispatchers.IO) { fetchBlocking(request) }

    private suspend fun fetchBlocking(request: FetchRequest): FetchResponse {
        var url = request.url.toHttpUrlOrNull() ?: throw IOException("Bad URL: ${request.url}")
        val headers = request.headers.toMutableMap()
        if (url.host == "api.themoviedb.org" && url.queryParameter("api_key") == TMDB_KEY_PLACEHOLDER && !tmdbBearer.isNullOrBlank()) {
            url = url.newBuilder().removeAllQueryParameters("api_key").build()
            headers["Authorization"] = "Bearer $tmdbBearer"
        }
        val builder = Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", userAgent)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val method = request.method.uppercase()
        val body = request.body?.toRequestBody(headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull())
        builder.method(method, if (method == "GET" || method == "HEAD") null else body ?: ByteArray(0).toRequestBody())
        val call = (if (request.followRedirects) client else noRedirects).newCall(builder.build())
        return call.await().use { r ->
            FetchResponse(r.code, r.request.url.toString(), r.headers.toMap(), r.body?.bytes() ?: ByteArray(0))
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
        cont.invokeOnCancellation { cancel() }
    }
}
