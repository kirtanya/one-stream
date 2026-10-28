package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.sources.CacheState
import com.kirtanya.reel.core.sources.DebridCache
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceFacts
import com.kirtanya.reel.core.sources.SourceKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** How the player knew a debrid link played the addon's "downloading" clip instead of the film. */
enum class PlaceholderReason(val stat: String) { DURATION("duration"), PATTERN("pattern") }

/**
 * Debrid addons answer a link the service hasn't cached yet with a short clip ("[Debridio] Downloading to
 * Provider — Please wait") while the service downloads the torrent. The player must not play it as the film.
 *
 * Primary check: the media is under 3 minutes while a film / episode is expected to run over 10 (TMDB runtime,
 * else a remembered duration, else assumed). Secondary: the link redirected to a file named like a placeholder.
 */
object PlaceholderCheck {
    const val MAX_PLACEHOLDER_MS = 3 * 60_000L
    const val MIN_TITLE_MS = 10 * 60_000L

    /** Only direct http(s) links of a film or episode ([isTitle] = it has a TMDB id); trailers / extras are short. */
    fun applies(source: Source, isTitle: Boolean): Boolean =
        isTitle && source.kind == SourceKind.DIRECT && !source.isExtra &&
            source.url?.let { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) } == true

    /**
     * How long the film / episode should run: TMDB's runtime first, else the duration history remembered —
     * but only one that looks like a real title (a placeholder may have been saved before this check existed).
     * Null = unknown (then a film / episode is assumed to run over 10 minutes).
     */
    fun expectedMs(runtimeMin: Int?, historyMs: Long?): Long? =
        runtimeMin?.takeIf { it > 0 }?.let { it * 60_000L } ?: historyMs?.takeIf { it >= MIN_TITLE_MS }

    /** A clip under 3 minutes where something over 10 is expected. Short titles (TMDB ≤ 10 min) are never flagged. */
    fun byDuration(actualMs: Long, expectedMs: Long?): Boolean =
        actualMs in 1 until MAX_PLACEHOLDER_MS && (expectedMs == null || expectedMs > MIN_TITLE_MS)

    /**
     * File names the addons use for their placeholder clips ("downloading.mp4", "downloading_v2.mp4",
     * "not_cached.mp4", "torrent_not_downloaded.mp4"…): the stem is only letters, "_" and "-" around the keyword,
     * so a release name with dots / a year ("Downloading.Nancy.2019.1080p.mkv") never matches. Extend here.
     */
    private val PATTERNS = listOf(
        "placeholder-name" to Regex(
            "/[a-z_-]{0,30}(downloading|download[_-]?started|not[_-]?cached|uncached|caching|not[_-]?downloaded|not[_-]?ready)" +
                "[a-z_-]{0,30}(?:[_-]?v\\d+)?\\.(?:mp4|mkv|webm|mov)$",
        ),
        // MediaFusion / Comet-style folders of static status clips.
        "static-videos" to Regex("/static/(?:videos|exceptions)/[^/]+\\.(?:mp4|mkv|webm|mov)$"),
    )

    /**
     * The pattern a (final, after redirects) link's path matches, or null. Only the path is read (lower-cased,
     * query dropped) and only the pattern name is returned, so nothing of the user's addon config is logged.
     */
    fun matchUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').let { u ->
            val rest = u.substringAfter("://", u)
            rest.substring(rest.indexOf('/').let { if (it < 0) rest.length else it })
        }.lowercase()
        if (path.isEmpty()) return null
        for ((name, regex) in PATTERNS) {
            val m = regex.find(path) ?: continue
            return m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: name
        }
        return null
    }

    /** The card when no other source can take over. */
    fun notReadyText(addon: String) =
        "$addon is still downloading this to your debrid service. It's usually ready in a few minutes."

    /** The chip on the source that took over. */
    fun switchNote(addon: String, instead: Source) =
        "$addon is still fetching that one — playing ${label(instead)} instead"

    /** "Comet 1080p": the provider's short name and the quality badge. */
    fun label(s: Source) = "${SourceFacts.providerLabel(s.provider)} ${s.quality.label}"

    /** Placeholder after placeholder: stop switching after this many hops and show the card. */
    const val MAX_SWITCHES = 3
}

/**
 * Which source takes over from one that played a placeholder: a direct link (no peers needed, it starts like the
 * one the user picked) of the same film / episode, not that file, not one found not-ready this session, not
 * marked uncached. Cached ones first, then unknown; the ranker's order within each.
 */
object PlaceholderFallback {
    fun pick(current: Source, ranked: List<Source>, notReady: (String) -> Boolean): Source? =
        candidates(current, ranked, notReady).firstOrNull()

    /**
     * Every source that could take over, best first (see [pick]); [skip] drops ones this device can't play
     * (AV1 where there's no decoder). Debrid addons list hundreds of links and most aren't cached, so the player
     * checks these with [ReadyCheck] before switching instead of trying them one by one.
     */
    fun candidates(current: Source, ranked: List<Source>, notReady: (String) -> Boolean, skip: (Source) -> Boolean = { false }): List<Source> =
        ranked.filter {
            it.kind == SourceKind.DIRECT && !it.isExtra && it.url != null && it.key != current.key && !notReady(it.key) &&
                DebridCache.of(it) != CacheState.UNCACHED && !skip(it)
        }.sortedBy { if (DebridCache.of(it) == CacheState.CACHED) 0 else 1 }
}

/** What a debrid link leads to right now. */
enum class Readiness { READY, NOT_READY, FAILED }

/**
 * Is a link's file there yet, without playing it: a debrid addon's link redirects to the service's file when
 * it's cached and to its "downloading" clip when not (Debridio: `static.debridio.com/…/downloading.mp4`). One
 * tiny ranged request follows the redirects and reads where it ended ([PlaceholderCheck.matchUrl]).
 *
 * Opening an uncached link makes the addon start fetching it on the service, as playing it would: it's only
 * done for the few the player would otherwise try anyway.
 */
object ReadyCheck {
    /** Where a link ended up: placeholder → not ready; an answer with the file → ready; an error → failed. */
    fun classify(finalUrl: String?, httpCode: Int): Readiness = when {
        finalUrl == null || httpCode !in 200..299 -> Readiness.FAILED
        PlaceholderCheck.matchUrl(finalUrl) != null -> Readiness.NOT_READY
        else -> Readiness.READY
    }

    /** Follows [url]'s redirects with a 1-byte request (blocking; call off the main thread). */
    fun check(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap(), userAgent: String? = null): Readiness = try {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
            userAgent?.let { header("User-Agent", it) }
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { classify(it.request.url.toString(), it.code) }
    } catch (e: Exception) {
        Readiness.FAILED
    }

    /**
     * The best-ranked of [candidates] that's ready, checking [parallel] at a time within [budgetMs] (a later one
     * that answered first still waits for the better ones). Also returns every answer, so the player can mark the
     * not-ready ones. Null source = none ready in time.
     */
    suspend fun firstReady(
        candidates: List<Source>,
        parallel: Int = 3,
        budgetMs: Long = 12_000,
        check: suspend (Source) -> Readiness,
    ): Pair<Source?, Map<Source, Readiness>> {
        val answers = java.util.concurrent.ConcurrentHashMap<Source, Readiness>()
        val found = withTimeoutOrNull(budgetMs) {
            coroutineScope {
                val gate = Semaphore(parallel)
                val jobs: List<Deferred<Readiness>> = candidates.map { s ->
                    async { gate.withPermit { check(s).also { answers[s] = it } } }
                }
                var winner: Source? = null
                for ((i, job) in jobs.withIndex()) {
                    if (job.await() == Readiness.READY) {
                        winner = candidates[i]
                        break
                    }
                }
                jobs.forEach { it.cancel() } // the rest aren't needed (or the winner came first)
                winner
            }
        }
        return found to answers.toMap()
    }

    /** How many candidates are checked at most: enough to get past a run of uncached links, not the whole list. */
    const val MAX_CHECKS = 8
}

/**
 * Debrid sources that played a placeholder: "not ready" for [ttlMs] (the service is still downloading them), so
 * the Sources page tags them "Fetching…" and lists them last, and fallbacks skip them. In memory only.
 * [now] is a monotonic clock (elapsedRealtime).
 */
class NotReadySources(private val ttlMs: Long = TTL_MS) {
    private val until = HashMap<String, Long>()

    @Synchronized fun mark(key: String, now: Long) {
        until[key] = now + ttlMs
    }

    @Synchronized fun clear(key: String) {
        until.remove(key)
    }

    @Synchronized fun isNotReady(key: String, now: Long): Boolean {
        val end = until[key] ?: return false
        if (now < end) return true
        until.remove(key)
        return false
    }

    companion object {
        const val TTL_MS = 30 * 60_000L
        const val FETCHING_TAG = "Fetching…"

        /** [ranked] with the not-ready ones moved to the end, both parts in their order. */
        fun demote(ranked: List<Source>, notReady: (String) -> Boolean): List<Source> {
            val (later, now) = ranked.partition { notReady(it.key) }
            return now + later
        }
    }
}
