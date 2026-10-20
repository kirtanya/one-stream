package com.kirtanya.reel.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.requestFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Developer check without touching the screen. Only adb can send it (guarded by the DUMP permission):
 * `adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --ei tmdb 10378 [--es type tv --ei season 1 --ei episode 1]`
 * `[--es addAddon <manifest url>] [--es removeAddon <manifest url>]` installs / removes an addon around the search.
 * `--ez history true` dumps watch history and Continue watching instead of searching.
 * `--ez mainThreadTest true` adds OpenSubtitles (removed after), searches subtitles + sources on the main thread.
 * `--ez playTest true --es url <direct> [--ei tmdb --ei season --ei episode --ei nextEpisode --el startMs --el durationMs]` plays a test video as an episode.
 * `--es removeTitle <titleKey>` removes a film/show from watch history.
 * Results go to logcat under "ReelProbe".
 */
class SourcesProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = ReelApp.get(context)
        if (intent.getBooleanExtra("history", false)) {
            // Watch history + what Continue watching would show right now.
            app.history.entries.value.take(20).forEach { e ->
                Log.i(TAG, "history ${e.playback.key} ${e.positionMs / 1000}s/${e.durationMs / 1000}s finished=${e.finished} next=${e.playback.next?.label} src=${e.playback.source.provider}")
            }
            app.history.continueWatching().forEach { Log.i(TAG, "continue ${it::class.simpleName} ${it.entry.playback.key}") }
            Log.i(TAG, "history done")
            return
        }
        if (intent.hasExtra("sync")) {
            syncProbe(app, intent, goAsync())
            return
        }
        // Put a watch position back (after tests): --es setPosition <key> --el ms <position>
        intent.getStringExtra("setPosition")?.let { key ->
            val e = app.history.get(key) ?: return
            app.history.save(e.playback, intent.getLongExtra("ms", 0), e.durationMs)
            Log.i(TAG, "set $key to ${intent.getLongExtra("ms", 0)} ms")
            return
        }
        intent.getStringExtra("removeTitle")?.let {
            app.history.removeTitle(it)
            Log.i(TAG, "removed $it from history")
            return
        }
        // Cold-start test on a legal sample (Blender open movie): --ei playSample 0..2
        intent.getIntExtra("playSample", -1).takeIf { it >= 0 }?.let { i ->
            val sample = com.kirtanya.reel.ui.SAMPLE_TORRENTS.getOrNull(i) ?: return
            context.startActivity(com.kirtanya.reel.player.PlayerActivity.forMagnet(context, sample.magnet, sample.title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "playing sample ${sample.title}")
            return
        }
        if (intent.getBooleanExtra("playTest", false)) {
            playTest(context, app, intent)
            return
        }
        if (intent.getBooleanExtra("mainThreadTest", false)) {
            mainThreadTest(app, goAsync())
            return
        }
        val pending = goAsync()
        val type = if (intent.getStringExtra("type") == "tv") MediaType.TV else MediaType.MOVIE
        val ref = TitleRef(type, intent.getIntExtra("tmdb", 10378))
        val season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
        app.appScope.launch {
            try {
                withTimeout(60_000) {
                    app.sources.ensureReady()
                    intent.getStringExtra("addAddon")?.let { Log.i(TAG, "added addon ${app.sources.addAddon(it).manifest.name}") }
                    val providers = app.sources.providers { name, line -> Log.i(TAG, "  [$name] $line") }
                    Log.i(TAG, "providers: ${providers.joinToString { it.name }}")
                    val d = app.tmdb.details(ref)
                    val req = requestFor(d, season, episode)
                    Log.i(TAG, "searching ${req.key} \"${req.title}\" (${req.year}) imdb=${req.imdbId}")
                    app.aggregator.search(req, providers, rankPrefs(context, isTv = false)).collect { s ->
                        Log.i(TAG, "update: ${s.sources.size} found, pending=${s.pending}, failed=${s.failed}")
                        if (s.done) s.sources.forEachIndexed { i, src ->
                            Log.i(TAG, "#$i ${src.kind} ${src.quality.label} [${src.provider}] ${src.title.lineSequence().first()} " +
                                "size=${src.sizeBytes} seeds=${src.seeders} ${src.url ?: src.infoHash}")
                        }
                    }
                }
            } catch (t: Throwable) {
                // Log.e hides traces for network errors, so spell the cause chain out.
                Log.e(TAG, "probe failed: " + generateSequence(t) { it.cause }.joinToString(" ← ") { it.toString() })
            } finally {
                intent.getStringExtra("removeAddon")?.let { app.sources.removeAddon(it); Log.i(TAG, "removed addon $it") }
                pending.finish()
            }
        }
    }

    /**
     * Phone ↔ TV sync checks, all in the background:
     * `--es sync info` (TV: code, port, paired phones; any device: its sources), `--es sync open` (TV: open pairing),
     * `--es sync pair --es code 1234` (phone: find the first TV, pair, push),
     * `--es sync push` (phone: push to paired TVs now),
     * `--es sync add --es link <url>` / `--es sync remove --es link <url>` (add / remove an addon or repo by link).
     */
    private fun syncProbe(app: ReelApp, intent: Intent, pending: PendingResult) {
        app.appScope.launch {
            try {
                withTimeout(60_000) {
                    app.sources.ensureReady()
                    when (intent.getStringExtra("sync")) {
                        "pair" -> {
                            val tvs = app.phoneSync.discover()
                            Log.i(TAG, "sync found ${tvs.map { "${it.name} ${it.host}:${it.port}" }}")
                            val tv = tvs.firstOrNull() ?: error("no TV found")
                            Log.i(TAG, "sync pair: ${app.phoneSync.pair(tv, intent.getStringExtra("code").orEmpty())}")
                        }
                        "push" -> {
                            app.phoneSync.pushAll(force = true)
                            app.phoneSync.state.value.tvs.forEach { Log.i(TAG, "sync ${it.name}: ${it.lastSummary} err=${it.lastError}") }
                        }
                        "open" -> {
                            app.tvSync.openPairing() // TV: open the pairing window (as the Phone & TV card does)
                        }
                        "add" -> Log.i(TAG, "sync add: ${app.sources.addFromLink(intent.getStringExtra("link").orEmpty())}")
                        "remove" -> {
                            val url = com.kirtanya.reel.core.sync.SourceLinks.extract(intent.getStringExtra("link").orEmpty())
                            app.sources.removeAddon(url.orEmpty())
                            app.sources.removeRepo(url.orEmpty())
                            Log.i(TAG, "sync removed $url")
                        }
                    }
                    if (app.isTv) app.tvSync.state.value.let { Log.i(TAG, "sync tv code=${it.code} port=${app.tvSync.port()} paired=${it.pairedPhones.map { p -> p.name }} last=${it.lastUpdate}") }
                    else app.phoneSync.state.value.tvs.forEach { Log.i(TAG, "sync paired ${it.name} ${it.host}:${it.port} last=${it.lastSummary} err=${it.lastError} repair=${it.needsPairing}") }
                    val b = app.sources.exportBundle()
                    Log.i(TAG, "sync sources addons=${b.addons.map { "${it.url}${if (it.enabled) "" else " (off)"}" }} repos=${b.repos.map { "${it.url} ${it.plugins}" }}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sync probe failed: " + generateSequence(t) { it.cause }.joinToString(" ← ") { it.toString() })
            } finally {
                Log.i(TAG, "sync probe done")
                pending.finish()
            }
        }
    }

    /**
     * Plays a direct [url] labelled as an episode of a real show (TMDB [tmdb], S`season`E`episode`,
     * next = E`nextEpisode`), optionally starting at `startMs` of `durationMs` (seeded as history so
     * the player resumes there). Exercises Up next / Continue watching with legal test video.
     * Reel must be in the foreground (activity starts from the background are blocked).
     */
    private fun playTest(context: Context, app: ReelApp, intent: Intent) {
        val id = intent.getIntExtra("tmdb", 1396)
        val season = intent.getIntExtra("season", 1)
        val episode = intent.getIntExtra("episode", 1)
        val next = intent.getIntExtra("nextEpisode", episode + 1)
        val url = intent.getStringExtra("url") ?: return
        val playback = com.kirtanya.reel.core.playback.Playback(
            key = com.kirtanya.reel.core.playback.Playback.keyFor("tv", id, com.kirtanya.reel.core.playback.EpisodeNumber(season, episode)),
            titleKey = "tv:$id",
            title = intent.getStringExtra("title") ?: "Test show",
            subtitle = "S$season E$episode · test",
            tmdbType = "tv",
            tmdbId = id,
            imdbId = intent.getStringExtra("imdb"),
            year = null,
            episode = com.kirtanya.reel.core.playback.EpisodeNumber(season, episode),
            backdropPath = intent.getStringExtra("backdrop"),
            next = com.kirtanya.reel.core.playback.EpisodeNumber(season, next),
            source = com.kirtanya.reel.core.sources.Source(
                com.kirtanya.reel.core.sources.SourceKind.DIRECT, "Test video", "Test",
                com.kirtanya.reel.core.sources.QualityParser.parse("1080p"), url = url,
            ),
        )
        val start = intent.getLongExtra("startMs", 0)
        if (start > 0) app.history.save(playback, start, intent.getLongExtra("durationMs", start * 2))
        context.startActivity(com.kirtanya.reel.player.PlayerActivity.forPlayback(context, playback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "playTest started ${playback.key} at ${start / 1000}s")
    }

    /**
     * Runs what the screens run, on the main thread: add a subtitle addon (Settings), search
     * subtitles (player) and sources (Sources screen). Any network-on-main bug shows up here.
     */
    private fun mainThreadTest(app: ReelApp, pending: PendingResult) {
        val subsUrl = "https://opensubtitles-v3.strem.io/manifest.json"
        kotlinx.coroutines.MainScope().launch {
            val hadAddon = app.sources.state.value.addons.any { it.url == subsUrl }
            try {
                withTimeout(60_000) {
                    Log.i(TAG, "main-thread test on ${Thread.currentThread().name}")
                    app.sources.ensureReady()
                    if (!hadAddon) Log.i(TAG, "added ${app.sources.addAddon(subsUrl).manifest.name}")
                    val d = app.tmdb.details(TitleRef(MediaType.MOVIE, 10331))
                    val req = requestFor(d, null, null)
                    val subs = com.kirtanya.reel.core.sources.Subtitles.search(req, app.sources.subtitleAddons(), app.fetcher)
                    Log.i(TAG, "subtitles: ${subs.size} tracks, languages=${subs.map { it.language }.distinct().take(12)} formats=${subs.mapNotNull { it.format }.distinct()}")
                    val search = app.aggregator.search(req, app.sources.providers(), rankPrefs(app, isTv = false)).first { it.done }
                    Log.i(TAG, "sources: ${search.sources.size} found, failed=${search.failed}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "main-thread test failed: " + generateSequence(t) { it.cause }.joinToString(" ← ") { it.toString() })
            } finally {
                if (!hadAddon) app.sources.removeAddon(subsUrl)
                Log.i(TAG, "main-thread test done")
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReelProbe"
    }
}
