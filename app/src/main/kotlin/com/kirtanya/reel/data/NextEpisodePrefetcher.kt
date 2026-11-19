package com.kirtanya.reel.data

import android.os.StatFs
import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.P2pUse
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.library.NextPrefetch
import com.kirtanya.reel.core.stream.NightRule
import com.kirtanya.reel.core.library.PrefetchFacts
import com.kirtanya.reel.core.library.PrefetchPick
import com.kirtanya.reel.core.library.PrefetchStep
import com.kirtanya.reel.core.library.PrefetchWhy
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.engine.DownloadRulesStore
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.EngineStats
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.requestFor
import com.kirtanya.reel.ui.sources.wantedLanguages
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/**
 * "Download the next episode while watching" (docs/STREAMING_V2.md §7.7). The player calls [consider] on its
 * progress tick; once the decision ([NextPrefetch.check]) says go, this searches the next episode's sources, picks
 * like autoplay-next and adds a normal download through the engine, the same calls as the Sources page's Download.
 * It never pauses, removes or edits anything that exists.
 */
class NextEpisodePrefetcher(private val app: ReelApp) {
    /** Episode key → when a prefetch was last started or failed (this run only). */
    private val tried = ConcurrentHashMap<String, Long>()

    /** Episode key → the source being fetched: autoplay-next plays exactly this one. */
    private val chosen = ConcurrentHashMap<String, Source>()

    /** Episode keys with a search in flight, so two ticks never start two. */
    private val searching = ConcurrentHashMap.newKeySet<String>()

    /** "key:why" already written to ReelStats, so a tick every 10 s doesn't repeat itself. */
    private val loggedSkips = ConcurrentHashMap.newKeySet<String>()

    /** The source this episode is being downloaded from, if a prefetch chose one. */
    fun chosenFor(episodeKey: String): Source? = chosen[episodeKey]

    fun consider(current: Playback, bufferHealthy: Boolean) {
        val next = current.next
        val show = current.tmdbId
        val key = if (next != null && show != null) Playback.keyFor("tv", show, next) else current.key
        val step = NextPrefetch.check(facts(current, key, bufferHealthy))
        when (step) {
            PrefetchStep.Go -> if (searching.add(key)) {
                app.appScope.launch {
                    try {
                        run(current, next!!, show!!, key)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        tried[key] = System.currentTimeMillis()
                        Log.w(TAG, "prefetch failed: ${e.message}")
                    } finally {
                        searching.remove(key)
                    }
                }
            }
            is PrefetchStep.Skip -> if (step.why == PrefetchWhy.ALREADY_HAVE) logSkip(key, next, step.why)
            is PrefetchStep.Wait -> Unit
        }
    }

    private fun facts(current: Playback, key: String, bufferHealthy: Boolean): PrefetchFacts {
        val triedAt = tried[key]
        return PrefetchFacts(
            enabled = app.playbackSettings.state.value.prefetchNext,
            isEpisode = current.tmdbType == "tv" && current.episode != null,
            tmdbId = current.tmdbId,
            next = current.next,
            downloads = knownDownloads(),
            lastTryAgoMs = triedAt?.let { System.currentTimeMillis() - it },
            // Never asks the user: only "Allow" goes ahead (the TV's verdict is always Allow).
            downloadVerdict = if (app.isTv) P2pVerdict.Allow else app.home.gate.verdict(P2pUse.DOWNLOAD),
            nightHolds = NightRule.holds(DownloadRulesStore.get(app).rules.value.night, minuteNow(), download = true, startNow = false, finished = false, claimed = false),
            bufferHealthy = bufferHealthy,
        )
    }

    private fun minuteNow() = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    private fun knownDownloads(): List<KnownDownload> {
        val origins = app.downloadOrigins.all.value
        return app.engine.torrents.value.filter { it.mode == TorrentMode.DOWNLOAD }.mapNotNull { t ->
            origins[t.infoHash.lowercase()]?.let { KnownDownload(t.infoHash, it, t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING) }
        }
    }

    private suspend fun run(current: Playback, next: EpisodeNumber, show: Int, key: String) {
        val ref = TitleRef(MediaType.TV, show)
        val d = runCatching { app.tmdb.details(ref) }.getOrNull() ?: return
        // Announced but not out yet: nothing to fetch (the same check as Up next).
        val airDate = runCatching { app.tmdb.season(show, next.season) }.getOrNull()?.firstOrNull { it.number == next.episode }
            ?.airDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val aired = if (airDate != null) !airDate.isAfter(java.time.LocalDate.now()) else hasAired(next, d.lastAired)
        if (!aired) return

        app.sources.ensureReady()
        val req = requestFor(d, next.season, next.episode)
        val search = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            app.aggregator.search(req, app.sources.providers(), rankPrefs(app, app.isTv, wantedLanguages(app, d), forDownload = true)).first { it.done }
        }
        val held = app.engine.torrents.value.mapTo(HashSet()) { it.infoHash.lowercase() }
        // TV with the USB disk ready (docs/HOME_DISK.md §24): a file of 4 GiB or more cannot be saved there, so a smaller source
        // at the same or one-lower quality is preferred, and the room check is the one of the volume it goes to.
        val onUsb = withContext(Dispatchers.IO) { app.downloadPlacer.usbInPlay() }
        val picked = when (val pick = NextPrefetch.pick(current.source, search?.sources.orEmpty(), held, underFatLimit = onUsb)) {
            is PrefetchPick.Chosen -> pick.source
            is PrefetchPick.None -> return skip(key, next, pick.why)
        }
        val source = picked
        if (onUsb) (NextPrefetch.pick(current.source, search?.sources.orEmpty(), held) as? PrefetchPick.Chosen)?.let { app.downloadPlacer.logSwap("prefetch", it.source, source) }
        var saveDir: java.io.File? = null
        if (onUsb) {
            val place = withContext(Dispatchers.IO) { app.downloadPlacer.decide("prefetch", source.sizeBytes) }
            place.refused?.let { return skip(key, next, if (it.why == com.kirtanya.reel.core.homedisk.PlaceWhy.TOO_BIG) PrefetchWhy.TOO_BIG else PrefetchWhy.NO_ROOM) }
            saveDir = place.saveDir
        } else {
            val form = if (app.isTv) FormFactor.TV else FormFactor.PHONE
            val free = runCatching { StatFs(app.engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
            if (!NextPrefetch.fits(source, free, form)) return skip(key, next, PrefetchWhy.NO_ROOM)
        }

        // Things moved while we searched (a network change, the user starting the same download): decide again.
        // The buffer was healthy when we started and the search is short, so it isn't asked again.
        if (NextPrefetch.check(facts(current, key, bufferHealthy = true)) != PrefetchStep.Go) return

        val magnet = source.magnetUri() ?: return
        val label = "${d.summary.title} · ${next.label}"
        tried[key] = System.currentTimeMillis()
        val hash = try {
            app.engine.addMagnet(magnet, TorrentMode.DOWNLOAD, source.fileIdx, source.filename, saveDir = saveDir)
        } catch (e: EngineException) {
            Log.w(TAG, "prefetch not added: ${e.message}")
            return
        }
        chosen[key] = source
        app.downloadOrigins.put(hash, DownloadOrigin("tv", show, next.season, next.episode, label))
        val startedAt = EngineStats.now()
        Log.i(TAG, "next-episode download $label → ${source.provider}")
        EngineStats.log(
            "nextPrefetch",
            linkedMapOf(
                "state" to "start", "key" to hash.take(12), "ep" to epTag(next), "prov" to source.provider,
                "res" to source.quality.resolution, "size" to source.sizeBytes, "bufMs" to NextPrefetch.HOLD_MS,
            ),
        )
        watchFinish(hash, next, startedAt)
    }

    /** Writes `state=done` when the torrent finishes (`gone` if it was removed first, `timeout` after 12 h). */
    private suspend fun watchFinish(hash: String, next: EpisodeNumber, startedAt: Long) {
        var seen = false
        val end = withTimeoutOrNull(WATCH_MS) {
            app.engine.torrents.first { list ->
                val t = list.firstOrNull { it.infoHash.equals(hash, ignoreCase = true) }
                if (t != null) seen = true
                when {
                    t == null -> seen
                    t.phase == TorrentPhase.FINISHED || t.phase == TorrentPhase.SEEDING -> true
                    t.wantedBytes > 0 && t.doneBytes >= t.wantedBytes -> true
                    else -> false
                }
            }
        }
        val t = end?.firstOrNull { it.infoHash.equals(hash, ignoreCase = true) }
        val state = when {
            end == null -> "timeout"
            t == null -> "gone"
            else -> "done"
        }
        EngineStats.log(
            "nextPrefetch",
            linkedMapOf("state" to state, "key" to hash.take(12), "ep" to epTag(next), "ms" to EngineStats.now() - startedAt, "bytes" to t?.doneBytes),
        )
    }

    private fun skip(key: String, next: EpisodeNumber, why: PrefetchWhy) {
        // A search that found nothing usable isn't repeated every 10 s.
        tried[key] = System.currentTimeMillis()
        logSkip(key, next, why)
    }

    private fun logSkip(key: String, next: EpisodeNumber?, why: PrefetchWhy) {
        if (!loggedSkips.add("$key:${why.tag}")) return
        EngineStats.log("nextPrefetch", linkedMapOf("state" to "skip", "why" to why.tag, "ep" to next?.let(::epTag)))
    }

    private fun epTag(e: EpisodeNumber) = "s${e.season}e${e.episode}"

    private companion object {
        const val TAG = "ReelPrefetch"
        const val SEARCH_TIMEOUT_MS = 45_000L
        const val WATCH_MS = 12 * 60 * 60_000L
    }
}
