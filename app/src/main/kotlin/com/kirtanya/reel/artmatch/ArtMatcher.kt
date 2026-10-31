package com.kirtanya.reel.artmatch

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.core.artmatch.ArtLookup
import com.kirtanya.reel.core.artmatch.ArtMatch
import com.kirtanya.reel.core.artmatch.ArtMatchCache
import com.kirtanya.reel.core.artmatch.ArtResolver
import com.kirtanya.reel.core.artmatch.MatchCandidate
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.data.TmdbRepository
import com.kirtanya.reel.data.WatchHistoryStore
import com.kirtanya.reel.engine.EngineStats
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** TMDB through the app's repository (its 15-minute cache and retries included). */
class TmdbArtLookup(private val tmdb: TmdbRepository) : ArtLookup {
    override suspend fun search(type: MediaType, query: String, year: Int?): List<MatchCandidate> = tmdb.matchCandidates(type, query, year)
    override suspend fun details(ref: TitleRef): TitleDetails = tmdb.details(ref)
    override suspend fun season(tvId: Int, season: Int): List<Episode> = tmdb.season(tvId, season)
}

/**
 * Artwork for Continue watching entries played from a raw torrent / magnet / file / link (docs/DECISIONS.md
 * 2026-10-24 "Artwork for raw files"). Matching itself is pure (core/artmatch); this runs it:
 * - a background pass over the watch history on app start and when the Continue row shows, at most
 *   [MAX_CALLS_PER_PASS] TMDB calls a pass, [GAP_MS] apart, passes [MIN_PASS_GAP_MS] apart;
 * - at play start ([forPlay]), so subtitles (IMDb id) and Up next work for the file.
 * Answers, "no match" included, are cached a week in files/art-match.json.
 */
class ArtMatcher(
    context: Context,
    /** Swapped for a fake in screen renders (no network there). */
    @Volatile var lookup: ArtLookup,
    private val history: WatchHistoryStore,
    private val scope: CoroutineScope,
) {
    private val file = AtomicFile(File(context.filesDir, "art-match.json"))
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val pass = Mutex()
    @Volatile private var loaded: ArtMatchCache? = null
    @Volatile private var lastPassAt = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (u in saves) {
                delay(SAVE_DELAY_MS)
                write()
            }
        }
    }

    private fun cache(): ArtMatchCache = loaded ?: synchronized(this) {
        loaded ?: ArtMatchCache.decode(runCatching { if (file.baseFile.exists()) file.readFully().decodeToString() else null }.getOrNull()).also { loaded = it }
    }

    /** Starts a background pass unless one ran in the last [MIN_PASS_GAP_MS]. */
    fun enrichSoon(reason: String) {
        if (lastPassAt != 0L && SystemClock.elapsedRealtime() - lastPassAt < MIN_PASS_GAP_MS) return
        scope.launch(Dispatchers.IO) { enrichHistory(reason) }
    }

    /** One pass over the history's unmatched entries, newest first. [force]: wait for a running pass and ignore the gap (tests). */
    suspend fun enrichHistory(reason: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && (pass.isLocked || lastPassAt != 0L && SystemClock.elapsedRealtime() - lastPassAt < MIN_PASS_GAP_MS)) return@withContext
        pass.withLock {
            lastPassAt = SystemClock.elapsedRealtime()
            val todo = history.entries.value.map { it.playback }.filter(ArtMatch::needsMatch).distinctBy { it.key }
            var budget = MAX_CALLS_PER_PASS
            for (p in todo) {
                if (budget <= 0) break
                // Offline / TMDB down: stop here, the next pass tries again.
                val calls = runOne(p, reason)?.calls ?: break
                budget -= calls
                if (calls > 0) delay(GAP_MS)
            }
        }
    }

    /**
     * [p] with its match, for the player to use from the start; null when there's none (or [p] already has
     * a title). The history entry, if any, is filled in too. The player waits only briefly for this.
     */
    suspend fun forPlay(p: Playback): Playback? = withContext(Dispatchers.IO) {
        if (!ArtMatch.needsMatch(p)) return@withContext null
        val known = ArtMatch.keepMatch(p, history.get(p.key)?.playback)
        if (!ArtMatch.needsMatch(known)) return@withContext known
        runOne(p, "play")?.playback
    }

    private suspend fun runOne(p: Playback, via: String): ArtResolver.Result? {
        val started = EngineStats.now()
        val result = try {
            ArtResolver(lookup, cache(), System::currentTimeMillis).resolve(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline / TMDB down: nothing cached, the next pass tries again.
            EngineStats.log("artMatch", mapOf("result" to "error", "via" to via, "ms" to EngineStats.now() - started, "error" to e.javaClass.simpleName))
            return null
        }
        result.playback?.let { matched -> history.enrich(p.key) { old -> if (ArtMatch.needsMatch(old)) matched else old } }
        if (result.calls > 0) saves.trySend(Unit)
        // Cached answers repeat every pass: only lookups that asked TMDB, and every play start, are worth a line.
        if (result.calls > 0 || via == "play") {
            EngineStats.log("artMatch", mapOf(
                "result" to if (result.playback != null) "matched" else "none",
                "why" to result.outcome,
                "score" to result.score?.let { "%.2f".format(java.util.Locale.ROOT, it) },
                "ms" to EngineStats.now() - started,
                "via" to via,
                "cached" to result.cached,
                "calls" to result.calls,
                "type" to result.playback?.tmdbType,
                // The clean title only (the raw name can carry a folder path); one token for the key=value line.
                "title" to result.searched?.replace(' ', '_'),
            ))
        }
        return result
    }

    private fun write() {
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(cache().encode(System.currentTimeMillis()).toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w(TAG, "art cache save failed", it)
        }
    }

    companion object {
        private const val TAG = "ReelArt"
        const val MAX_CALLS_PER_PASS = 24
        const val GAP_MS = 300L
        const val MIN_PASS_GAP_MS = 60_000L
        private const val SAVE_DELAY_MS = 2_000L
    }
}
