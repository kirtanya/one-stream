package com.kirtanya.reel.core.sources

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Anything that can answer "where can I play this?" — an addon or a plugin. */
interface SourceProvider {
    val id: String
    val name: String
    fun supports(req: SourceRequest): Boolean
    suspend fun find(req: SourceRequest): List<Source>
}

/** A Stremio addon, installed by manifest URL. */
class AddonProvider(
    private val manifestUrl: String,
    private val manifest: StremioManifest,
    private val http: HttpFetcher,
) : SourceProvider {
    override val id = "addon:${manifest.id}"
    override val name = manifest.name
    override fun supports(req: SourceRequest) = manifest.hasStreams && manifest.streamIdFor(req) != null

    override suspend fun find(req: SourceRequest): List<Source> {
        val streamId = manifest.streamIdFor(req) ?: return emptyList()
        val response = http.fetch(FetchRequest(StremioAddon.streamUrl(manifestUrl, req, streamId)))
        if (response.status !in 200..299) throw PluginException("Addon answered ${response.status}")
        // A block page or captive portal answers 200 with HTML: that is a failure, not "no streams for this film".
        if (!StremioAddon.isStreamsAnswer(response.text)) throw PluginException("Answered with a web page, not sources")
        return StremioAddon.parseStreams(response.text, name)
    }
}

/** A Nuvio-format scraper; [code] is its downloaded JS. */
class PluginProvider(
    private val info: PluginInfo,
    private val code: String,
    private val runtime: PluginRuntime,
    private val settings: Map<String, String> = emptyMap(),
    private val log: (String) -> Unit = {},
) : SourceProvider {
    override val id = "plugin:${info.id}"
    override val name = info.name
    override fun supports(req: SourceRequest) = info.supportedTypes.contains(if (req.type.path == "tv") "tv" else "movie")
    override suspend fun find(req: SourceRequest) = runtime.getStreams(code, name, req, settings, log)
}

/** Progress of one search; emitted every time a provider answers. */
data class SourceSearch(
    val sources: List<Source>,
    /** Providers still working. */
    val pending: List<String>,
    /** Provider name → why it failed. */
    val failed: Map<String, String>,
    val total: Int,
) {
    val done: Boolean get() = pending.isEmpty()
}

/**
 * Asks every provider in parallel (at most [maxParallel] at once, [timeoutMs] each) and
 * emits the merged, de-duplicated, ranked list as answers arrive.
 */
class SourceAggregator(private val timeoutMs: Long = 30_000, private val maxParallel: Int = 10) {

    fun search(req: SourceRequest, providers: List<SourceProvider>, prefs: RankPrefs = RankPrefs()): Flow<SourceSearch> = channelFlow {
        val active = providers.filter { runCatching { it.supports(req) }.getOrDefault(false) }
        val lock = Mutex()
        val byKey = LinkedHashMap<String, Source>()
        val pending = active.map { it.name }.toMutableList()
        val failed = LinkedHashMap<String, String>()

        suspend fun publish() = send(SourceSearch(SourceRanker.rank(byKey.values.toList(), prefs), pending.toList(), failed.toMap(), active.size))

        lock.withLock { publish() }
        if (active.isEmpty()) {
            close()
            return@channelFlow
        }
        val gate = Semaphore(maxParallel)
        val jobs = active.map { provider ->
            launch {
                val outcome = runCatching { gate.withPermit { withTimeout(timeoutMs) { provider.find(req) } } }
                lock.withLock {
                    pending.remove(provider.name)
                    outcome.onSuccess { list ->
                        list.map { SourceExtras.mark(it, req) }.forEach { s ->
                            val existing = byKey[s.key]
                            if (existing == null || (s.seeders ?: -1) > (existing.seeders ?: -1)) byKey[s.key] = s
                        }
                    }.onFailure { e ->
                        failed[provider.name] = failureReason(e)
                    }
                    publish()
                }
            }
        }
        launch {
            jobs.forEach { it.join() }
            close()
        }
        awaitClose()
    }
}

/**
 * Plain words for why a provider failed: the empty Sources page shows them, so a TV that can't reach the addons
 * (site blocked by the network, wrong clock breaking secure connections) says so instead of a Java message.
 */
fun failureReason(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(5).toList()
    return when {
        chain.any { it is kotlinx.coroutines.TimeoutCancellationException || it is java.net.SocketTimeoutException } -> "Timed out"
        chain.any { it is java.net.UnknownHostException } -> "Site not found (blocked by this network, or no internet)"
        chain.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLException } ->
            "Secure connection failed (check this device's date and time)"
        chain.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException } -> "Can't connect (blocked by this network?)"
        chain.any { it is java.net.SocketException } -> "Connection dropped"
        else -> e.message?.take(80) ?: "Failed"
    }
}
