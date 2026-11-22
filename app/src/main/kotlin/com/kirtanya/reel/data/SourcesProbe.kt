package com.kirtanya.reel.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
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
 *   `--es clipUrl <url> --es provider "Torrentio AD (test)"`: a local test server's link instead (status clips by name,
 *   the debrid wait), no debrid involved.
 * `--es removeTitle <titleKey>` removes a film/show from watch history.
 * `--ez report true [--es note <text>]` saves a manual problem report (screenshot of the screen on top, if ours).
 * `--es reportTest crash|freeze` tests the automatic reports: crash the app / block its main thread for 8 s.
 * `--es home state|test|open|request|jobs|config|net` home disk checks (docs/HOME_DISK.md, see homeProbe).
 * `--es usbDownload start|status|unplug|replug|cleanup [--ei sample 0..2]` the USB download check on the TV (docs/HOME_DISK.md §24.10):
 *   start saves a legal Blender sample like the Download button would; status lists its files and sizes on the disk;
 *   unplug / replug make the app pretend the USB volume is gone / back (nothing is unmounted); cleanup deletes the sample.
 * `--es httpDownload start|status|kill|cleanup [--ei sample 0..1]` the direct (HTTP) download check (docs/HOME_DISK.md §25.10): start adds a legal
 *   sample (0 = Big Buck Bunny 720p, 62 MB, archive.org, via a redirect; 1 = Sintel 1080p, 1.2 GB, Blender) like the Download button; status
 *   logs its row, its files on the disk and the player's uri; kill ends the app process 500 ms later (to prove the resume); cleanup removes it.
 *   Each returns at once; results are in logcat.
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
        intent.getStringExtra("usbDownload")?.let { cmd ->
            usbDownloadProbe(app, cmd, intent.getIntExtra("sample", 1), goAsync())
            return
        }
        intent.getStringExtra("httpDownload")?.let { cmd ->
            httpDownloadProbe(app, cmd, intent.getIntExtra("sample", 0), goAsync())
            return
        }
        if (intent.hasExtra("sync")) {
            syncProbe(context, app, intent, goAsync())
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
     * `--es sync export` / `--es sync import` (all sources → / ← `files/$SOURCES_FILE` in the app's external dir),
     * `--es sync add --es link <url>` / `--es sync remove --es link <url>` (add / remove an addon or repo by link).
     */
    private fun syncProbe(context: Context, app: ReelApp, intent: Intent, pending: PendingResult) {
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
                        // Copy every source to another phone (no TV in between): export here, `adb pull` / `adb push` the
                        // file, import there. The file holds addon links with their keys, so import deletes it.
                        "export" -> {
                            val f = java.io.File(context.getExternalFilesDir(null), SOURCES_FILE)
                            f.writeText(kotlinx.serialization.json.Json.encodeToString(com.kirtanya.reel.core.sync.SourcesBundle.serializer(), app.sources.exportBundle("adb")))
                            Log.i(TAG, "sync export: ${f.path}")
                        }
                        "import" -> {
                            val f = java.io.File(context.getExternalFilesDir(null), SOURCES_FILE)
                            val bundle = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                .decodeFromString(com.kirtanya.reel.core.sync.SourcesBundle.serializer(), f.readText())
                            f.delete()
                            val r = app.sources.applyBundle(bundle)
                            Log.i(TAG, "sync import: ${r.summary} failed=${r.failed}")
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
            com.kirtanya.reel.core.sources.SourceKind.DIRECT, "Big Buck Bunny 1080p (placeholder test)",
            intent.getStringExtra("provider") ?: "Debridio (test)", q("1080p"),
            url = intent.getStringExtra("clipUrl") ?: PLACEHOLDER_CLIP,
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
     * `--es home backend --es backend usb|network`   choose where the library lives
     * `--es home test [--ez big true]`   on the USB backend: write/read/split check on the disk (big = real 4095 MiB boundary)
     */
    private fun homeProbe(app: ReelApp, cmd: String, intent: Intent, pending: PendingResult) {
        val home = app.home
        var answered = false
        app.appScope.launch {
            try {
                when (cmd) {
                    "open" -> app.startActivity(com.kirtanya.reel.MainActivity.openHomeStorage(app))
                    "test" -> {
                        Log.i(TAG, "home test: " + when (val o = home.disk.test(intent.getBooleanExtra("confirmHome", false))) {
                            is com.kirtanya.reel.homedisk.TestOutcome.Done -> o.line
                            is com.kirtanya.reel.homedisk.TestOutcome.Refused -> "refused: ${o.line}"
                            com.kirtanya.reel.homedisk.TestOutcome.NeedsConfirm -> "no home network saved yet: add --ez confirmHome true (only at home)"
                        })
                        // USB disk only: the real 4095 MiB piece boundary (about a minute on an SSD, cleans up after itself).
                        if (intent.getBooleanExtra("big", false) && home.settings.value.backend == com.kirtanya.reel.core.homedisk.HomeBackend.USB) {
                            // Hand the broadcast back first: Android stops an app whose receiver is still busy after
                            // ~10-60 s (an ANR, seen on the TV 2026-11-19), and this takes minutes. The work goes on in appScope.
                            if (!answered) { answered = true; pending.finish() }
                            Log.i(TAG, "home test big: " + (home.disk.testUsbBig()?.let { "ok=${it.ok} ms=${it.millis} oneFileOver4GRefused=${it.oneBigFileRefused} ${it.detail}" } ?: "no usb disk"))
                        }
                    }
                    // `--es backend usb|network` picks where the library lives (the same choice as Settings → Home storage).
                    "backend" -> {
                        val b = com.kirtanya.reel.core.homedisk.HomeBackend.entries.firstOrNull { it.name.equals(intent.getStringExtra("backend"), ignoreCase = true) }
                        if (b != null) home.update { it.copy(backend = b) }
                        home.disk.refresh(deep = false)
                        Log.i(TAG, "home backend=${home.settings.value.backend} disk=${home.disk.ui.value.state}")
                    }
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
                        Log.i(TAG, "home settings backend=${s.backend} usbVolume=${s.usbVolume} address=${s.address} shareSet=${s.share.isNotEmpty()} userSet=${s.user.isNotEmpty()} passwordSaved=${home.passwordSaved.value} keystore=${home.secrets.available} homeSaved=${s.homeFingerprint != null} where=${s.downloadWhere} mobileData=${s.mobileData} networks=${s.networks.size} retention=${s.retention}")
                        Log.i(TAG, "home disk ${home.disk.ui.value.state} lastTest=${home.disk.ui.value.lastTest}")
                        if (app.isTv) Log.i(TAG, "home tv jobs=${home.agent.state.value.jobs.size} addrs=${home.agent.addresses()}")
                        else Log.i(TAG, "home outbox=${home.client.outbox.value.size} jobs=${home.client.jobs.value.size} tv=${home.client.pairedTv()?.name} waiting=${home.gate.waiting.value}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "home $cmd failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (!answered) pending.finish()
            }
        }
    }

    /** `httpDownload` (see the class comment): a legal direct file through the same path as the Download button. */
    private fun httpDownloadProbe(app: ReelApp, cmd: String, sampleIdx: Int, pending: PendingResult) {
        val sample = HTTP_SAMPLES.getOrNull(sampleIdx)
        if (sample == null) { Log.w(TAG, "httpDownload: no sample $sampleIdx"); pending.finish(); return }
        val (title, url, size) = sample
        if (cmd == "kill") {
            Log.i(TAG, "httpDownload kill: ending the process in 500 ms")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500)
            pending.finish()
            return
        }
        app.appScope.launch {
            try {
                val downloads = app.httpDownloads
                val row = downloads.items.value.firstOrNull { it.url == url }
                when (cmd) {
                    "start" -> {
                        val source = com.kirtanya.reel.core.sources.Source(
                            kind = com.kirtanya.reel.core.sources.SourceKind.DIRECT, title = title, provider = "Probe",
                            quality = com.kirtanya.reel.core.sources.QualityParser.parse(title), url = url, sizeBytes = size,
                        )
                        val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { downloads.add(source, title, null, "probe") }
                        Log.i(TAG, "httpDownload start $title -> $added")
                    }
                    "status" -> {
                        if (row == null) { Log.i(TAG, "httpDownload status: no download of $title"); return@launch }
                        val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = downloads.folderOf(row)?.let { java.io.File(it, row.id) }
                            // What is really on the disk (part files and pieces included), not only what is committed.
                            dir?.listFiles()?.sortedBy { it.name }?.joinToString { "${it.name}:${it.length()}" }
                        }
                        Log.i(
                            TAG,
                            "httpDownload status ${row.key} state=${row.state} done=${row.doneBytes} total=${row.totalBytes} place=${row.place} " +
                                "dir=${row.baseDir} error=${row.error} files=[$files] active=${downloads.activeCount()} " +
                                "uri=${com.kirtanya.reel.core.httpdl.HomeFileRef.format(row.id, row.fileName)}",
                        )
                    }
                    "cleanup" -> {
                        if (row != null) downloads.remove(row.key)
                        kotlinx.coroutines.delay(1_500)
                        Log.i(TAG, "httpDownload cleanup rowsLeft=${downloads.items.value.count { it.url == url }}")
                    }
                    else -> Log.w(TAG, "httpDownload: unknown command $cmd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "httpDownload $cmd failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /** `usbDownload` (see the class comment): one legal sample, saved where the Download button would save it. */
    private fun usbDownloadProbe(app: ReelApp, cmd: String, sampleIdx: Int, pending: PendingResult) {
        app.appScope.launch {
            try {
                val sample = com.kirtanya.reel.ui.SAMPLE_TORRENTS.getOrNull(sampleIdx) ?: run { Log.w(TAG, "usbDownload: no sample $sampleIdx"); return@launch }
                val hash = com.kirtanya.reel.core.magnet.Magnet.parse(sample.magnet)?.infoHash?.lowercase()
                val engine = app.engine
                when (cmd) {
                    "start" -> {
                        val place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.downloadPlacer.decide("button", null) }
                        Log.i(TAG, "usbDownload start place=${place.placement::class.simpleName} dir=${place.saveDir} refused=${place.refused?.message}")
                        place.refused?.let { return@launch }
                        val key = engine.addMagnet(sample.magnet, com.kirtanya.reel.engine.TorrentMode.DOWNLOAD, saveDir = place.saveDir)
                        Log.i(TAG, "usbDownload start added ${sample.title} key=${key.take(12)}")
                    }
                    "status" -> {
                        val t = engine.torrents.value.firstOrNull { it.infoHash.equals(hash, true) }
                        Log.i(TAG, "usbDownload status ${t?.name} phase=${t?.phase} progress=${t?.progress?.times(100)?.toInt()}% done=${t?.doneBytes} wanted=${t?.wantedBytes} onUsb=${t?.onUsb} waiting=${t?.waitingForDisk} notice=${t?.notice} error=${t?.error} path=${t?.savePath}")
                        val roots = listOfNotNull(t?.savePath?.takeIf { it.isNotBlank() }?.let(::File), engine.downloadsDir())
                        for (root in roots.distinct()) root.walkTopDown().filter { it.isFile && !it.name.endsWith(".parts") }.take(40).forEach {
                            Log.i(TAG, "usbDownload file ${it.path} ${it.length()} bytes")
                        }
                    }
                    "unplug" -> {
                        com.kirtanya.reel.engine.DownloadVolumes.pretendGone = com.kirtanya.reel.engine.DownloadVolumes.mountedIds(app).filter { it.contains('-') }.toSet()
                        engine.volumesChanged()
                        Log.i(TAG, "usbDownload unplug pretending gone=${com.kirtanya.reel.engine.DownloadVolumes.pretendGone}")
                    }
                    "replug" -> {
                        com.kirtanya.reel.engine.DownloadVolumes.pretendGone = emptySet()
                        engine.volumesChanged()
                        Log.i(TAG, "usbDownload replug")
                    }
                    "cleanup" -> {
                        com.kirtanya.reel.engine.DownloadVolumes.pretendGone = emptySet()
                        if (hash != null) {
                            engine.remove(hash, deleteFiles = true)
                            app.downloadOrigins.remove(hash)
                        }
                        kotlinx.coroutines.delay(2_000)
                        val left = engine.torrents.value.filter { it.infoHash.equals(hash, true) }.size
                        Log.i(TAG, "usbDownload cleanup torrentsLeft=$left")
                        val dir = app.downloadPlacer.decide("probe", null).saveDir
                        Log.i(TAG, "usbDownload cleanup folder ${dir} entries=${dir?.list()?.toList()}")
                    }
                    else -> Log.w(TAG, "usbDownload: unknown command $cmd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "usbDownload $cmd failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReelProbe"
        private const val SOURCES_FILE = "sources.json"
        /** 10 s of Big Buck Bunny (CC BY, Blender Foundation), 1 MB, HTTPS: stands in for a debrid "downloading" clip. */
        /** Legal direct files for `httpDownload`: title, link, size. */
        private val HTTP_SAMPLES = listOf(
            Triple("Big Buck Bunny (2008) 720p", "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", 61_878_609L),
            Triple("Sintel (2010) 1080p", "https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv", 1_180_090_590L),
        )
        private const val PLACEHOLDER_CLIP = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
        /** The whole film (9:56) on archive.org: the cached source the placeholder test falls back to. */
        private const val BBB_FULL = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"
    }
}
