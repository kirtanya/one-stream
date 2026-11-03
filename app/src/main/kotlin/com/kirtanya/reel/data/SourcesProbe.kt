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
 * `--ez subsTest true [--ei tmdb <id> --es type tv --ei season N --ei episode N --es filename <f>]` checks every subtitle addon.
 * `--ez playTest true --es url <direct> [--ei tmdb --ei season --ei episode --ei nextEpisode --el startMs --el durationMs]` plays a test video as an episode.
 * `--ez placeholderTest true [--ei runtimeMin 90] [--ez fallback true]` plays a 10 s legal clip as if a debrid addon
 *   sent its "downloading" placeholder for Big Buck Bunny (tmdb 10378): the Not ready card, or with `fallback` the
 *   switch to the full film (archive.org). Afterwards: `--es removeTitle movie:10378` (fallback plays and saves it).
 * `--es removeTitle <titleKey>` removes a film/show from watch history.
 * `--ez report true [--es note <text>]` saves a manual problem report (screenshot of the screen on top, if ours).
 * `--es reportTest crash|freeze` tests the automatic reports: crash the app / block its main thread for 8 s.
 * `--es home state|test|open|request|jobs|config|net` home disk checks (docs/HOME_DISK.md, see homeProbe).
 * Results go to logcat under "ReelProbe".
 */
class SourcesProbe : BroadcastReceiver() {
    private suspend fun restoreEntry(app: ReelApp, intent: Intent, prefix: String) {
        val engine = app.engine
        engine.start()
        var snap = engine.torrents.value.firstOrNull { it.infoHash.startsWith(prefix) }
        var waited = 0
        while (snap == null && waited < 15_000) { kotlinx.coroutines.delay(500); waited += 500; snap = engine.torrents.value.firstOrNull { it.infoHash.startsWith(prefix) } }
        if (snap == null) { Log.w(TAG, "restore: no torrent $prefix"); return }
        val hash = snap.infoHash
        val isTv = intent.getStringExtra("type") == "tv"
        val ref = TitleRef(if (isTv) MediaType.TV else MediaType.MOVIE, intent.getIntExtra("tmdb", 0))
        val d = app.tmdb.details(ref)
        val ep = if (isTv) com.kirtanya.reel.core.playback.EpisodeNumber(intent.getIntExtra("season", 1), intent.getIntExtra("episode", 1)) else null
        val video = engine.files(hash).maxByOrNull { it.size }
        val source = com.kirtanya.reel.core.sources.Source(
            kind = com.kirtanya.reel.core.sources.SourceKind.TORRENT, title = snap.name, provider = "Torrent",
            quality = com.kirtanya.reel.core.sources.QualityParser.parse(snap.name),
            magnet = "magnet:?xt=urn:btih:$hash", infoHash = hash, fileIdx = video?.index, sizeBytes = video?.size,
        )
        val type = if (isTv) "tv" else "movie"
        val playback = com.kirtanya.reel.core.playback.Playback(
            key = com.kirtanya.reel.core.playback.Playback.keyFor(type, ref.id, ep), titleKey = "$type:${ref.id}",
            title = d.summary.title, tmdbType = type, tmdbId = ref.id, imdbId = d.imdbId, year = d.summary.year, episode = ep,
            posterPath = d.summary.posterPath, backdropPath = d.summary.backdropPath,
            next = ep?.let { com.kirtanya.reel.core.playback.EpisodeNumber(it.season, it.episode + 1) }, source = source,
        )
        val duration = intent.getLongExtra("durationMs", (d.runtimeMin ?: 0) * 60_000L)
        app.history.save(playback, intent.getLongExtra("ms", 0), duration)
        Log.i(TAG, "restored ${playback.key} at ${intent.getLongExtra("ms", 0)} ms from $hash file ${video?.index}")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = ReelApp.get(context)
        if (intent.getBooleanExtra("report", false)) {
            app.reports.saveManual(app.reports.currentActivity, "adb", intent.getStringExtra("note")) { dir -> Log.i(TAG, "report saved: $dir") }
            return
        }
        when (intent.getStringExtra("reportTest")) {
            "crash" -> android.os.Handler(android.os.Looper.getMainLooper()).post { throw IllegalStateException("Report test crash (SourcesProbe)") }
            "freeze" -> android.os.Handler(android.os.Looper.getMainLooper()).post { Log.i(TAG, "blocking the main thread for 8 s"); Thread.sleep(8_000) }
        }
        if (intent.hasExtra("reportTest")) return
        intent.getStringExtra("uiFlags")?.let {
            com.kirtanya.reel.ui.common.UiFlags.write(context, it)
            Log.i(TAG, "uiFlags ${com.kirtanya.reel.ui.common.UiFlags.summary()}")
            return
        }
        if (intent.getBooleanExtra("warmEngine", false)) {
            // What One Launcher's warm_engine does; logcat ReelEngineIdle has the time.
            com.kirtanya.reel.engine.EngineIdle.warm(app, "probe")
            return
        }
        if (intent.getBooleanExtra("history", false)) {
            // Watch history + what Continue watching would show right now.
            app.history.entries.value.take(20).forEach { e ->
                Log.i(TAG, "history ${e.playback.key} ${e.positionMs / 1000}s/${e.durationMs / 1000}s finished=${e.finished} next=${e.playback.next?.label} src=${e.playback.source.provider}")
            }
            app.history.continueWatching().forEach { Log.i(TAG, "continue ${it::class.simpleName} ${it.entry.playback.key}") }
            Log.i(TAG, "history done")
            return
        }
        intent.getStringExtra("home")?.let { cmd ->
            homeProbe(app, cmd, intent, goAsync())
            return
        }
        if (intent.hasExtra("sync")) {
            syncProbe(app, intent, goAsync())
            return
        }
        // Rebuild a lost Continue watching entry: --es restoreHash <infohash prefix> --es type movie|tv --ei tmdb N
        //   [--ei season S --ei episode E] --el ms <position> [--el durationMs D]
        intent.getStringExtra("restoreHash")?.let { prefix ->
            val done = goAsync()
            app.appScope.launch {
                runCatching { restoreEntry(app, intent, prefix) }.onFailure { Log.w(TAG, "restore failed", it) }
                done.finish()
            }
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
        if (intent.getBooleanExtra("placeholderTest", false)) {
            placeholderTest(context, intent)
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
        if (intent.getBooleanExtra("subsTest", false)) {
            subsTest(app, intent, goAsync())
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
     * Debrid placeholder check (core/player/PlaceholderCheck) without a debrid account: a 10 s Big Buck Bunny clip
     * (CC BY, test-videos.co.uk) plays as a "Debridio" link for the film, expected to run [runtimeMin] (default 90;
     * TMDB says 10, too short to count). With `fallback` the only other source is the full film on archive.org.
     */
    private fun placeholderTest(context: Context, intent: Intent) {
        val runtimeMin = intent.getIntExtra("runtimeMin", 90)
        val fallback = intent.getBooleanExtra("fallback", false)
        val q = { t: String -> com.kirtanya.reel.core.sources.QualityParser.parse(t) }
        val clip = com.kirtanya.reel.core.sources.Source(
            com.kirtanya.reel.core.sources.SourceKind.DIRECT, "Big Buck Bunny 1080p (placeholder test)", "Debridio (test)", q("1080p"),
            url = PLACEHOLDER_CLIP,
        )
        val full = com.kirtanya.reel.core.sources.Source(
            com.kirtanya.reel.core.sources.SourceKind.DIRECT, "Big Buck Bunny 720p surround", "Internet Archive (test)", q("720p"),
            url = BBB_FULL, cache = com.kirtanya.reel.core.sources.CacheState.CACHED,
        )
        val playback = com.kirtanya.reel.core.playback.Playback(
            key = "movie:10378", titleKey = "movie:10378", title = "Big Buck Bunny", tmdbType = "movie", tmdbId = 10378, year = 2008, source = clip,
        )
        context.startActivity(
            com.kirtanya.reel.player.PlayerActivity.forPlaceholderTest(context, playback, runtimeMin * 60_000L, if (fallback) listOf(full) else emptyList())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(TAG, "placeholderTest started: expected ${runtimeMin} min, fallback=$fallback")
    }

    /**
     * Do the subtitle addons work? `--ez subsTest true [--ei tmdb <id>] [--es type tv --ei season N --ei episode N]
     * [--es filename <name>]` lists every installed subtitle addon, asks the enabled ones for the title
     * exactly as the player does (IMDb id from TMDB), and logs per addon: request URL (addon config
     * hidden), HTTP status, tracks per language, error; then the merged total and which language the
     * player would show by itself with the current setting. Nothing plays; no P2P.
     */
    private fun subsTest(app: ReelApp, intent: Intent, pending: PendingResult) {
        val type = if (intent.getStringExtra("type") == "tv") MediaType.TV else MediaType.MOVIE
        val ref = TitleRef(type, intent.getIntExtra("tmdb", 10378))
        val season = intent.getIntExtra("season", -1).takeIf { it >= 0 } ?: if (type == MediaType.TV) 1 else null
        val episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 } ?: if (type == MediaType.TV) 1 else null
        app.appScope.launch {
            try {
                withTimeout(60_000) {
                    val all = app.sources.state.value.addons.mapNotNull { a -> runCatching { a.manifest }.getOrNull()?.takeIf { it.hasSubtitles }?.let { a to it } }
                    Log.i(TAG, "subs addons installed=${all.size}: " + all.joinToString { (a, m) -> "${m.name} ${com.kirtanya.reel.core.sources.Subtitles.safeUrl(a.url)}${if (a.enabled) "" else " (off, not asked)"}" })
                    val d = app.tmdb.details(ref)
                    val req = requestFor(d, season, episode)
                    val language = app.playbackSettings.state.value.subtitleLanguage
                    Log.i(TAG, "subs title ${req.key} \"${req.title}\" (${req.year}) imdb=${req.imdbId} setting=${language ?: "off"}")
                    val search = com.kirtanya.reel.core.sources.Subtitles.searchDetailed(req, app.sources.subtitleAddons(), app.fetcher, filename = intent.getStringExtra("filename"))
                    com.kirtanya.reel.core.sources.Subtitles.describe(search).forEach { Log.i(TAG, "subs $it") }
                    val shown = com.kirtanya.reel.core.sources.Subtitles.autoSelect(search.tracks, language)
                    Log.i(TAG, "subs player would show: ${shown?.let { "${it.language} from ${it.name}" } ?: "none (all ${search.tracks.size} tracks listed in the menu)"}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "subs test failed: " + generateSequence(t) { it.cause }.joinToString(" ← ") { it.toString() })
            } finally {
                Log.i(TAG, "subs test done")
                pending.finish()
            }
        }
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

    /**
     * Home disk (docs/HOME_DISK.md), no P2P anywhere:
     * `--es home state`   settings (no password), disk state, home decision, network rule, outbox / jobs
     * `--es home test [--ez confirmHome true]`   runs the disk Test (signs in only at home) and logs its line
     * `--es home open`    opens Settings → Home storage
     * `--es home request [--es magnet <m>]`   phone: "Download at home" for a legal sample (Big Buck Bunny) and sends it
     * `--es home jobs`    phone: polls the TV's jobs; TV: lists its jobs
     * `--es home config`  phone: sends the disk settings to the TV and logs the TV's Test line
     * `--es home net`     the network facts and what the rule says for downloads and plays
     */
    private fun homeProbe(app: ReelApp, cmd: String, intent: Intent, pending: PendingResult) {
        val home = app.home
        app.appScope.launch {
            try {
                when (cmd) {
                    "open" -> app.startActivity(com.kirtanya.reel.MainActivity.openHomeStorage(app))
                    "test" -> Log.i(TAG, "home test: " + when (val o = home.disk.test(intent.getBooleanExtra("confirmHome", false))) {
                        is com.kirtanya.reel.homedisk.TestOutcome.Done -> o.line
                        is com.kirtanya.reel.homedisk.TestOutcome.Refused -> "refused: ${o.line}"
                        com.kirtanya.reel.homedisk.TestOutcome.NeedsConfirm -> "no home network saved yet: add --ez confirmHome true (only at home)"
                    })
                    "request" -> {
                        val magnet = intent.getStringExtra("magnet") ?: com.kirtanya.reel.ui.SAMPLE_TORRENTS[0].magnet
                        val id = home.client.requestDownload(magnet, null, null, intent.getStringExtra("label") ?: "Probe: Big Buck Bunny", null)
                        home.client.flush(force = true)
                        Log.i(TAG, "home request $id → ${home.client.jobs.value.firstOrNull { it.requestId == id }} error=${home.client.lastError.value}")
                    }
                    "jobs" -> if (app.isTv) home.agent.state.value.jobs.forEach { Log.i(TAG, "home job $it") } else {
                        home.client.poll()
                        home.client.jobs.value.forEach { Log.i(TAG, "home job $it") }
                    }
                    "config" -> Log.i(TAG, "home config: ${home.client.sendConfig()}")
                    "net" -> {
                        home.disk.refresh(deep = true)
                        val net = home.network.now.value
                        Log.i(TAG, "home net transport=${net.transport} metered=${net.metered} fp=${net.fingerprint?.take(12)} home=${home.disk.ui.value.home}")
                        if (!app.isTv) Log.i(TAG, "home rule download=${home.gate.verdict(com.kirtanya.reel.core.homedisk.P2pUse.DOWNLOAD)} play=${home.gate.verdict(com.kirtanya.reel.core.homedisk.P2pUse.STREAM)} held=${home.gate.held.value.size}")
                    }
                    else -> {
                        home.disk.refresh(deep = false)
                        val s = home.settings.value
                        Log.i(TAG, "home settings address=${s.address} shareSet=${s.share.isNotEmpty()} userSet=${s.user.isNotEmpty()} passwordSaved=${home.passwordSaved.value} keystore=${home.secrets.available} homeSaved=${s.homeFingerprint != null} where=${s.downloadWhere} mobileData=${s.mobileData} networks=${s.networks.size} retention=${s.retention}")
                        Log.i(TAG, "home disk ${home.disk.ui.value.state} lastTest=${home.disk.ui.value.lastTest}")
                        if (app.isTv) Log.i(TAG, "home tv jobs=${home.agent.state.value.jobs.size} addrs=${home.agent.addresses()}")
                        else Log.i(TAG, "home outbox=${home.client.outbox.value.size} jobs=${home.client.jobs.value.size} tv=${home.client.pairedTv()?.name} waiting=${home.gate.waiting.value}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "home $cmd failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReelProbe"
        /** 10 s of Big Buck Bunny (CC BY, Blender Foundation), 1 MB, HTTPS: stands in for a debrid "downloading" clip. */
        private const val PLACEHOLDER_CLIP = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
        /** The whole film (9:56) on archive.org: the cached source the placeholder test falls back to. */
        private const val BBB_FULL = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"
    }
}
