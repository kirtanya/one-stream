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
                        list.forEach { s ->
                            val existing = byKey[s.key]
                            if (existing == null || (s.seeders ?: -1) > (existing.seeders ?: -1)) byKey[s.key] = s
                        }
                    }.onFailure { e ->
                        failed[provider.name] = when (e) {
                            is kotlinx.coroutines.TimeoutCancellationException -> "Timed out"
                            else -> e.message ?: "Failed"
                        }
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
