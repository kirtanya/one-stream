package com.kirtanya.reel.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.MainActivity
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.PlayerSourceChange
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.SubtitleSearch
import com.kirtanya.reel.core.sources.SubtitleTrack
import com.kirtanya.reel.core.sources.Subtitles
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.sources.rankPrefs
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Plays one [Playback] (a torrent through the local stream server, or a direct URL) and
 * remembers where you got to. Resumes, loads subtitles, and rolls on to the next episode.
 * See PLAN.md §1.5.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), com.kirtanya.reel.report.ReportCenter.Screen {
    private val app get() = ReelApp.get(this)
    private val prefs get() = app.playbackSettings.state.value
    private lateinit var playback: Playback

    private var player: ExoPlayer? = null
    private var hash: String? = null
    /** When the user asked to play (ReelStats measures time to first frame from here). */
    private val tapMs = android.os.SystemClock.elapsedRealtime()
    /** The stream this player prepared; only this one is stopped when we leave. */
    private var preparedStream: com.kirtanya.reel.engine.StreamInfo? = null
    private var streamUrl: String? = null
    private var statsJob: Job? = null
    private var progressJob: Job? = null
    private var nextJob: Job? = null
    private var countdownJob: Job? = null
    private var flashJob: Job? = null
    /** Next episode + the source chosen for it (null source = none found), once looked up. */
    private var nextReady: Pair<Playback, Source?>? = null
    private var nextCancelled = false
    /** The Up next card was up when we went to the background; show it again on return. */
    private var nextCardPending = false
    /** Leaving for the next episode or for Change source: don't hold the stream in the background. */
    private var leavingForNext = false
    private var firstFrameShown = false
    /** Where this player started (resume / handed over); the position before the player exists. */
    private var startAtMs = 0L
    /** Started by Change source (the new source is remembered at the first frame). */
    private var sourceChanged = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var externalHandoff = false
    /** We were stopped after handing off: an external player really took over (not a cancelled chooser). */
    private var stoppedSinceHandoff = false

    // ---- roadmap A: skip intro / credits, auto-play next, sleep timer, per-title memory, audio
    /** Night mode / voice boost, countdown, seek step, delays and learned skips (player/PlayerStore). */
    private val store get() = PlayerStore.get(this)
    private val options get() = store.options.value
    /** This title's remembered tracks / picture / speed (the shared per-title store), read once at the start. */
    private val saved by lazy { app.titlePrefs.get(playback.titleKey) }
    private val delays = PlaybackDelays()
    private val effects = AudioEffects { e, f -> stat(e, f) }
    /** A key press or touch during this episode (Still watching? counts untouched episodes). */
    private var interacted = false
    /** Episodes before this one that played to the end untouched (carried from player to player). */
    private var untouchedBefore = 0
    /** Seeks the user made in a burst (remote presses), for learning the intro. */
    private val seekBurst = com.kirtanya.reel.core.player.SeekBurst()
    /** The next seek is ours (the Skip chip), not the user's: don't learn from it. */
    private var ownSeek = false
    /** Skippable chapters of this file (MKV chapter titles). */
    private var chapterSkips: List<Pair<com.kirtanya.reel.core.player.SkipKind, com.kirtanya.reel.core.player.SkipWindow>> = emptyList()
    private var chapterMarks: List<com.kirtanya.reel.core.player.ChapterMark> = emptyList()
    /** The Skip chip showing now (kind + where it skips to). */
    private var chipShown: Pair<com.kirtanya.reel.core.player.SkipKind, com.kirtanya.reel.core.player.SkipWindow>? = null
    private var tickJob: Job? = null
    private var sleepChoice = com.kirtanya.reel.core.player.SleepChoice.OFF
    /** elapsedRealtime when the sleep timer was set (survives into the next episode's player). */
    private var sleepSetAt = 0L
    private var sleepFading = false
    private var stillJob: Job? = null
    /** The Up next card appeared at the learned / chapter credits, before the last 30 s. */
    private var nextAtCredits = false

    private lateinit var playerView: PlayerView
    /** Cinema mode: the glow in the bars around the video. */
    private lateinit var ambient: AmbientGlow
    /** TV only: the bottom-strip controls (PlayerView's own controller is off there). */
    private var tvControls: TvControls? = null
    /** Phone only: our controls and gestures (PlayerView's controller is off everywhere). */
    private var phoneControls: PhoneControls? = null
    /** Chips and small cards at the top right (and the phone's Skip chip): PlayerOverlays. */
    private lateinit var overlays: PlayerOverlays
    private val status get() = overlays.status
    private val resumeChip get() = overlays.resumeChip
    private val nextCard get() = overlays.nextCard
    private val nextTitle get() = overlays.nextTitle
    private val nextCountdown get() = overlays.nextCountdown
    private val nextPlay get() = overlays.nextPlay
    private val skipChip get() = overlays.skipChip
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorExternal: Button
    private lateinit var errorSources: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val decoded = intent.getStringExtra(EXTRA_PLAYBACK)?.let { runCatching { json.decodeFromString(Playback.serializer(), it) }.getOrNull() }
        if (decoded == null) {
            finish()
            return
        }
        playback = decoded
        untouchedBefore = intent.getIntExtra(EXTRA_UNTOUCHED, 0)
        intent.getStringExtra(EXTRA_SLEEP)?.let { name ->
            com.kirtanya.reel.core.player.SleepChoice.entries.firstOrNull { it.name == name }?.let {
                sleepChoice = it
                sleepSetAt = intent.getLongExtra(EXTRA_SLEEP_AT, now())
            }
        }
        store.delays(playback.titleKey).let {
            delays.audioUs = it.audioDelayMs * 1_000L
            delays.subtitlesUs = it.subtitleDelayMs * 1_000L
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Phones: landscape from the first frame (most video is wide; a portrait video turns back below).
        if (!isTelevision()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(buildLayout())
        // A caption style changed in Settings (e.g. from the TV's panel or another screen) shows at once.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.playbackSettings.state.collect { applyCaptionLook() } }
        }
        // Phone: Back closes the settings panel before it leaves the player.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (phoneControls?.handleBack() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
        start()
    }

    // ---------------------------------------------------------------- start

    private fun start() {
        val source = playback.source
        // Change source hands the old player's position over: carry on exactly there.
        val handedOver = intent.getLongExtra(EXTRA_START_MS, -1L).takeIf { it >= 0 }
        sourceChanged = handedOver != null
        val resumeAt = ContinueWatching.startPositionMs(handedOver, app.history.get(playback.key))
        startAtMs = resumeAt
        lifecycleScope.launch {
            try {
                val subtitles = async { findSubtitles() }
                val url = when (source.kind) {
                    SourceKind.DIRECT -> {
                        showStatus("Loading…")
                        source.url ?: error("No link to play")
                    }
                    SourceKind.TORRENT -> prepareTorrent(source)
                    SourceKind.EXTERNAL -> error("This source opens in another app")
                }
                // Torrents spend seconds finding peers, so subtitles are usually ready by now; a direct
                // link waits briefly, and only when a language should show (Off never holds the start up).
                val wantSubs = wantedSubtitleLanguage() != null ||
                    (getSystemService(CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager)?.isEnabled == true
                val wait = Subtitles.startWaitMs(wantSubs)
                val found = when {
                    subtitles.isCompleted -> subtitles.await()
                    wait > 0 -> withTimeoutOrNull(wait) { subtitles.await() }
                    else -> null
                }
                // Started in the background (the user left while peers were found): don't play
                // there; hold the torrent until we're visible again.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    preparedStream?.let { app.engine.suspendStream(it.infoHash, it) }
                    lifecycle.withStarted { }
                }
                // Every track is attached (the Subtitles menu lists them all); the setting only picks
                // which one shows by itself (applySubtitlePrefs).
                val tracks = Subtitles.merge(source.subtitles, found?.tracks.orEmpty())
                play(url, tracks, resumeAt)
                subsStat("subsAttached", mapOf("attached" to tracks.size, "when" to "start", "addonsAnswered" to (found != null)))
                if (found == null) {
                    // Slow subtitle addons: their tracks join when they arrive (see addLateSubtitles).
                    val late = runCatching { subtitles.await() }.getOrNull()?.tracks.orEmpty()
                    if (late.isNotEmpty()) addLateSubtitles(late)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                errorCause = e
                showError(e.message ?: "Couldn't start this stream")
            } catch (t: Throwable) {
                errorCause = t
                showError("Couldn't start this: ${t.message}")
            }
        }
    }

    private suspend fun prepareTorrent(source: Source): String {
        showStatus("Finding peers…")
        val engine = app.engine
        engine.start()
        val magnet = source.magnetUri() ?: error("No magnet for this torrent")
        Magnet.parse(magnet)?.infoHash?.let {
            hash = it
            startStats(it)
        }
        val info = engine.startStream(magnet, source.fileIdx, source.filename, tapMs)
        hash = info.infoHash
        preparedStream = info
        // A duration we already know (Continue watching): size the read-ahead right from the start.
        app.history.get(playback.key)?.durationMs?.takeIf { it > 0 }?.let { engine.setStreamDuration(info, it) }
        // In-app reads go straight to the engine (no HTTP hop); the flag keeps the old path for diagnosis.
        return if (com.kirtanya.reel.engine.EngineFlags.inapp) info.uri else engine.httpUrlFor(info)
    }

    /**
     * Subtitles from subtitle addons. They need an IMDb id: a title that came without one (a link
     * from another app, an old Continue watching card) gets it from TMDB first. A raw magnet / file has
     * no TMDB id, so there's nothing to ask. Logs what each addon answered (ReelPlayer) and a ReelStats line.
     */
    private suspend fun findSubtitles(): SubtitleSearch {
        val none = SubtitleSearch(emptyList(), emptyList())
        val addons = app.sources.subtitleAddons()
        val base = sourceRequest(playback.episode)
        if (addons.isEmpty() || base == null) {
            subsStat("subs", mapOf("asked" to 0, "found" to 0, "why" to if (addons.isEmpty()) "noSubtitleAddons" else "noTmdbId"))
            return none
        }
        val req = if (base.imdbId != null) base else {
            val ref = TitleRef(if (playback.tmdbType == "tv") MediaType.TV else MediaType.MOVIE, base.tmdbId)
            base.copy(imdbId = withTimeoutOrNull(4_000) { runCatching { app.tmdb.details(ref).imdbId }.getOrNull() })
        }
        val search = runCatching {
            withContext(Dispatchers.Default) { Subtitles.searchDetailed(req, addons, app.fetcher, filename = playback.source.filename) }
        }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "subtitles failed", it) }
            .getOrDefault(none)
        Subtitles.describe(search).forEach { Log.i(TAG, "subs $it") }
        subsStat("subs", mapOf(
            "asked" to search.answers.size,
            "answered" to search.answers.count { it.status in 200..299 },
            "found" to search.tracks.size,
            "langs" to search.tracks.map { it.language }.distinct().take(8).joinToString(","),
            "imdb" to (req.imdbId != null),
            "imdbFromTmdb" to (base.imdbId == null && req.imdbId != null),
            "want" to (prefs.subtitleLanguage ?: "off"),
        ))
        return search
    }

    /** Subtitle tracks that came too late for the start, waiting for a moment that won't be noticed. */
    private var pendingSubs: List<SubtitleTrack> = emptyList()

    /**
     * Adding side-loaded subtitles means preparing the media item again (ExoPlayer can't add them to a
     * prepared one). Before the first frame that costs nothing visible, so they join at once. After it,
     * a re-prepare would show as a restart, so they wait for the next natural break: a seek (the buffer
     * is thrown away anyway), a pause (the last frame stays up: keepContentOnPlayerReset), or the
     * return from the background (onStart prepares again anyway).
     */
    private fun addLateSubtitles(late: List<SubtitleTrack>) {
        val have = player?.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().map { it.uri.toString() }.toSet()
        val extra = late.filter { it.url !in have }
        if (extra.isEmpty()) return
        if (!firstFrameShown) attachSubtitles(extra, "beforeFirstFrame")
        // The language the user reads arrived: show it now (one short re-buffer) rather than leave the
        // film without subtitles until a pause — on the phone OpenSubtitles took 5.6 s (device test).
        else if (wantedIn(extra)) attachSubtitles(extra, "lateWanted")
        else {
            pendingSubs = extra
            subsStat("subsLate", mapOf("pending" to extra.size))
        }
    }

    /** Does [tracks] contain the language the user wants to see by itself (not Off)? */
    private fun wantedIn(tracks: List<SubtitleTrack>): Boolean = Subtitles.containsLanguage(tracks, wantedSubtitleLanguage())

    /** The subtitle language that shows by itself: this title's choice first, then Settings (null = off). */
    private fun wantedSubtitleLanguage(): String? =
        com.kirtanya.reel.core.player.TitleStart.subtitles(saved)?.let { if (it.off) null else it.language } ?: prefs.subtitleLanguage

    /** Rebuilds the item with [extra] subtitle tracks at the current position; [prepare] = false when the caller prepares. */
    private fun attachSubtitles(extra: List<SubtitleTrack>, why: String, prepare: Boolean = true) {
        pendingSubs = emptyList()
        val exo = player ?: return
        val item = exo.currentMediaItem ?: return
        if (errorPanel.visibility == View.VISIBLE || exo.playbackState == Player.STATE_ENDED) return
        val have = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val haveUris = have.map { it.uri.toString() }.toSet()
        val add = extra.filter { it.url !in haveUris }
        if (add.isEmpty()) return
        exo.setMediaItem(item.buildUpon().setSubtitleConfigurations(have + add.map(::subtitleConfig)).build(), exo.currentPosition.coerceAtLeast(0))
        if (prepare) exo.prepare()
        subsStat("subsAttached", mapOf("attached" to add.size, "total" to have.size + add.size, "when" to why))
    }

    private fun attachPendingSubtitles(why: String) {
        val pending = pendingSubs.takeIf { it.isNotEmpty() } ?: return
        playerView.post { if (pendingSubs === pending) attachSubtitles(pending, why) }
    }

    /** Which subtitle shows by itself, logged whenever it changes (ReelStats `subsSelected`). */
    private var loggedSubtitle: String? = null

    private fun logSelectedSubtitle(tracks: androidx.media3.common.Tracks) {
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isEmpty()) return
        val selected = text.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language ?: C.LANGUAGE_UNDETERMINED } } ?: "none"
        val line = "$selected/${text.sumOf { it.length }}"
        if (line == loggedSubtitle) return
        loggedSubtitle = line
        subsStat("subsSelected", mapOf("selected" to selected, "tracks" to text.sumOf { it.length }, "want" to (prefs.subtitleLanguage ?: "off")))
    }

    private fun subsStat(event: String, fields: Map<String, Any?>) = stat(event, fields)

    /** Player telemetry: a ReelStats line (with the stream's key for torrents) and a ReelPlayer line. */
    private fun stat(event: String, fields: Map<String, Any?>) {
        val h = hash
        if (h != null) com.kirtanya.reel.engine.EngineStats.event(h, event, fields) else com.kirtanya.reel.engine.EngineStats.log(event, fields)
        Log.i(TAG, "$event ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    private fun play(url: String, subtitles: List<SubtitleTrack>, resumeAtMs: Long) {
        streamUrl = url
        val torrent = playback.source.kind == SourceKind.TORRENT
        val tv = isTelevision()
        val loadControl = if (torrent) {
            // Reads can't time out any more, so pull continuously (min = max) and keep a back buffer
            // sized by bitrate so short rewinds never touch the disk (docs/STREAMING_V2.md §6.2).
            val rate = com.kirtanya.reel.core.stream.PlayerBuffers.rate(
                preparedStream?.file?.size ?: playback.source.sizeBytes,
                app.history.get(playback.key)?.durationMs?.takeIf { it > 0 },
                playback.source.quality.resolution?.let { "${it}p" },
            )
            val form = if (tv) com.kirtanya.reel.core.stream.FormFactor.TV else com.kirtanya.reel.core.stream.FormFactor.PHONE
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(if (tv) 30_000 else 50_000, if (tv) 30_000 else 50_000, 2_000, 5_000)
                .apply { if (tv) setTargetBufferBytes(64 * 1024 * 1024) }
                .setBackBuffer(com.kirtanya.reel.core.stream.PlayerBuffers.backBufferMs(form, rate), true)
                .build()
        } else {
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_500, 5_000).build()
        }
        // Some direct sources need headers (Referer, User-Agent…) on every request.
        // Every request of the stream gets them (playlists, CDN segments, redirects), but they're
        // never sent to third-party subtitle hosts.
        val headers = playback.source.headers
        val subtitleUrls = subtitles.map { it.url }.toSet()
        // Direct links over the app's OkHttp (connection reuse, HTTP/2); never the torrent scheme.
        val okhttp = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            app.http.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        ).setUserAgent(com.kirtanya.reel.core.sources.CHROME_UA)
        val http = ResolvingDataSource.Factory(okhttp) { spec ->
            if (headers.isNotEmpty() && spec.uri.toString() !in subtitleUrls) spec.withAdditionalHeaders(headers) else spec
        }
        val other = if (url.startsWith("http://127.0.0.1")) {
            // Diagnostic loopback mode (EngineFlags.inapp = 0): long read timeout.
            DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setReadTimeoutMs(60_000))
        } else {
            DefaultDataSource.Factory(this, http)
        }
        val sources = ReelDataSourceFactory(TorrentDataSource.Factory(app.engine), other)
        // Our renderers: audio / subtitle delay; Dolby decoded here while night mode / voice boost is on.
        val exo = ExoPlayer.Builder(this, DelayRenderersFactory(this, delays, pcmOnly = options.nightMode || options.voiceBoost))
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setLoadErrorHandlingPolicy(ReelLoadErrorPolicy()))
            .build()
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        // Land on a keyframe: no decoding up to an exact frame (slow on the TV's 4K HEVC).
        exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        player = exo
        playerView.player = exo
        tvControls?.attach(exo)
        phoneControls?.attach(exo)
        ambient.attach(exo)
        applySubtitlePrefs(exo)
        applyTitlePrefs(exo)
        applyEffects()
        exo.addListener(listener)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(playback.displayTitle).build())
            .setSubtitleConfigurations(subtitles.map(::subtitleConfig))
            .build()
        exo.setMediaItem(item, resumeAtMs)
        exo.prepare()
        exo.playWhenReady = true
        playerView.requestFocus()
        startSignals()
        if (resumeAtMs > 0) showResumeChip(resumeAtMs)
        startProgressSaving()
        startTicker()
        Log.i(TAG, "playing ${playback.key} from ${formatTime(resumeAtMs)} with ${subtitles.size} subtitle tracks")
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    status.visibility = View.GONE
                    directRetries = 0
                    stats { s ->
                        s.ready(now())
                        s.bufferingEnd(now())
                        s.readyAfterSeek(now())?.let { ms -> logStat("seek", mapOf("ms" to ms)) }
                    }
                }
                Player.STATE_BUFFERING -> {
                    if (hash != null) status.visibility = View.VISIBLE
                    stats { it.bufferingStart(now()) }
                }
                Player.STATE_ENDED -> {
                    saveProgress(ended = true)
                    if (nextReady?.second != null && !nextCancelled && prefs.autoplayNext && !sleepAtEnd()) autoPlayNext()
                    else if (nextCard.visibility == View.VISIBLE) nextPlay.requestFocus()
                }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val d = player?.duration ?: return
            if (d != C.TIME_UNSET && d > 0) preparedStream?.let { app.engine.setStreamDuration(it, d) }
        }

        override fun onRenderedFirstFrame() {
            firstFrameShown = true
            // Changed source: remember the new one for this film / episode straight away.
            if (sourceChanged) playerView.post { saveProgress() }
            preparedStream?.let { app.engine.onPlayerEvent(it, "firstFrame") }
            stats { s ->
                if (s.firstFrameAt == null) {
                    s.firstFrame(now())
                    logStat("ttff", emptyMap())
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (ownSeek) ownSeek = false
                else if (playback.episode != null) seekBurst.seek(oldPosition.positionMs, newPosition.positionMs, now())?.let(::learnIntro)
                stats { it.seek(now()) }
                preparedStream?.let { app.engine.onPlayerEvent(it, "seek") }
                attachPendingSubtitles("seek")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
            // Paused by the user (not buffering): late subtitles can join behind the still frame.
            if (!isPlaying && player?.playWhenReady == false) attachPendingSubtitles("pause")
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            logSelectedSubtitle(tracks)
            readChapters(tracks)
            applySavedSubtitleLabel(tracks)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applyEffects()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // After PlayerView sets the video's own shape: put a forced one (16:9, 4:3…) back.
            playerView.post { applyPicture() }
            // Phones turn sideways for wide video; TVs are always landscape.
            if (!isTelevision() && videoSize.width > 0 && videoSize.height > 0) {
                requestedOrientation = if (videoSize.width >= videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    // ---------------------------------------------------------------- subtitles

    private fun subtitleConfig(t: SubtitleTrack): MediaItem.SubtitleConfiguration {
        val mime = when (t.format) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val language = Locale.forLanguageTag(t.language).getDisplayLanguage(Locale.ENGLISH).ifBlank { t.language }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
            .setMimeType(mime)
            .setLanguage(t.language)
            .setLabel(listOfNotNull(language, t.name).joinToString(" · "))
            .build()
    }

    /** Subtitles show in the chosen language, or stay off (still pickable from the CC button). */
    private fun applySubtitlePrefs(exo: ExoPlayer) {
        val lang = prefs.subtitleLanguage
        val captions = getSystemService(android.view.accessibility.CaptioningManager::class.java)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .apply {
                when {
                    lang != null -> setPreferredTextLanguage(lang)
                    // "Off" still honours Android's accessibility captions: their language, or the
                    // device language when captions are on with "Default" language.
                    captions.isEnabled && captions.locale == null -> setPreferredTextLanguage(Locale.getDefault().language)
                    captions.isEnabled -> setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(this@PlayerActivity)
                    else -> setPreferredTextLanguage(null)
                }
            }
            .setSelectUndeterminedTextLanguage(false)
            // A file's "default" flag shouldn't pick subtitles for you; language does.
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        applyCaptionLook()
    }

    /**
     * The user's caption style (Settings → Subtitles → Style). On the phone, while the bottom band shows,
     * captions sit just above it instead of under the buttons.
     */
    private fun applyCaptionLook() {
        val view = playerView.subtitleView ?: return
        val style = prefs.caption
        com.kirtanya.reel.captions.CaptionLook.apply(view, style)
        val covered = phoneControls?.coveredBottomPx() ?: 0
        if (covered > 0 && playerView.height > 0) {
            val lift = (covered + dp(8)).toFloat() / playerView.height
            view.setBottomPaddingFraction(maxOf(style.position.bottomFraction, lift))
        }
    }

    // ---------------------------------------------------------------- progress + resume

    private fun startProgressSaving() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                val exo = player ?: continue
                if (exo.isPlaying) saveProgress()
                maybePrepareNext(exo)
            }
        }
    }

    private fun saveProgress(ended: Boolean = false) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (ended) duration else exo.currentPosition
        app.history.save(playback, position, duration)
    }

    private fun showResumeChip(atMs: Long) {
        resumeChip.text = "Resumed at ${formatTime(atMs)} · Start over"
        resumeChip.visibility = View.VISIBLE
        if (isTelevision()) resumeChip.requestFocus()
        lifecycleScope.launch {
            delay(7_000)
            hideResumeChip()
        }
    }

    private fun hideResumeChip() {
        if (resumeChip.visibility != View.VISIBLE) return
        val hadFocus = resumeChip.hasFocus()
        resumeChip.visibility = View.GONE
        if (hadFocus) playerView.requestFocus()
    }

    // ---------------------------------------------------------------- next episode

    /**
     * From 80 % in (or 5 min left, or a minute before the learned credits), finds the next episode's
     * best matching source in the background.
     */
    private fun maybePrepareNext(exo: ExoPlayer) {
        val next = playback.next ?: return
        if (nextJob != null || !prefs.autoplayNext) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exo.currentPosition
        val credits = creditsStartMs(duration)
        if (position < duration * 0.8 && duration - position > 5 * 60_000 && (credits == null || position < credits - 60_000)) return
        nextJob = lifecycleScope.launch {
            val nextPlayback = buildNextPlayback(next) ?: return@launch
            val req = sourceRequest(next) ?: return@launch
            app.sources.ensureReady()
            val search = withTimeoutOrNull(45_000) {
                app.aggregator.search(req, app.sources.providers(), rankPrefs(this@PlayerActivity, isTelevision())).first { it.done }
            }
            val source = pickNextSource(playback.source, search?.sources.orEmpty())
            nextReady = (if (source != null) nextPlayback.copy(source = source) else nextPlayback) to source
            Log.i(TAG, "next ${next.label}: ${source?.provider ?: "no source"}")
            watchForCredits()
        }
    }

    /** Fills in the next episode's name, still and the one after it (from TMDB, cached). */
    private suspend fun buildNextPlayback(next: EpisodeNumber): Playback? {
        val id = playback.tmdbId ?: return null
        val details = runCatching { app.tmdb.details(TitleRef(MediaType.TV, id)) }.getOrNull()
        val ep = runCatching { app.tmdb.season(id, next.season) }.getOrNull()?.firstOrNull { it.number == next.episode }
        // Announced but not out yet → no Up next. Without an air date (or if TMDB is unreachable)
        // fall back to the show's latest aired episode; [playback.next] was already checked against it.
        val airDate = ep?.airDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val aired = if (airDate != null) !airDate.isAfter(java.time.LocalDate.now()) else hasAired(next, details?.lastAired)
        if (!aired) return null
        return playback.copy(
            key = Playback.keyFor("tv", id, next),
            subtitle = listOfNotNull(next.label, ep?.name).joinToString(" · "),
            episode = next,
            stillPath = ep?.stillPath,
            next = details?.let { nextEpisode(it.seasons, next, it.lastAired) },
        )
    }

    /** Where this episode's credits start: a "Credits" chapter, else what the user taught us (SkipPlan). */
    private fun creditsStartMs(durationMs: Long): Long? {
        if (playback.episode == null) return null
        val learned = com.kirtanya.reel.core.player.SkipLearning.creditsRemainingMs(store.show(playback.titleKey))
        return com.kirtanya.reel.core.player.SkipPlan.creditsStartMs(durationMs, chapterSkips, learned)
    }

    /**
     * Shows the "Up next" card at the credits (learned or a chapter) or for the last 30 s; counts down
     * (Settings → Playback, 5 / 10 / 20 s), then plays the next episode.
     */
    private fun watchForCredits() {
        lifecycleScope.launch {
            while (isActive) {
                val exo = player ?: return@launch
                val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                if (nextCancelled) return@launch
                if (duration != null) {
                    val credits = creditsStartMs(duration)
                    val at = com.kirtanya.reel.core.player.SkipPlan.upNextAtMs(duration, credits)
                    if (exo.currentPosition >= at) {
                        nextAtCredits = duration - exo.currentPosition > com.kirtanya.reel.core.player.SkipPlan.UP_NEXT_DEFAULT_MS + 2_000
                        if (nextAtCredits) stat("upNextAtCredits", mapOf("remainingS" to (duration - exo.currentPosition) / 1000, "chapter" to chapterSkips.any { it.first == com.kirtanya.reel.core.player.SkipKind.CREDITS }))
                        showNextCard()
                        return@launch
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun showNextCard() {
        val (next, source) = nextReady ?: return
        if (nextCancelled || errorPanel.visibility == View.VISIBLE) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            nextCardPending = true
            return
        }
        // Keep the controls from popping up over the card (they'd take the remote's focus at the end).
        playerView.controllerAutoShow = false
        playerView.hideController()
        tvControls?.hide()
        phoneControls?.hideNow()
        hideSkipChip()
        nextTitle.text = "Up next · ${next.subtitle ?: next.episode?.label.orEmpty()}"
        // At the credits it says what it does there: skips them.
        nextPlay.text = if (source == null) "Choose a source" else if (nextAtCredits) "Skip credits" else "Play now"
        nextCard.visibility = View.VISIBLE
        if (isTelevision()) nextPlay.requestFocus()
        if (source == null) {
            nextCountdown.text = "No matching source found automatically"
            return
        }
        if (sleepAtEnd()) {
            nextCountdown.text = "Sleep timer: stopping after this one"
            return
        }
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            var left = options.countdownS
            while (left > 0) {
                nextCountdown.text = "Playing in $left…"
                delay(1_000)
                val exo = player ?: return@launch
                // Paused by the user → the countdown waits too.
                if (exo.isPlaying || exo.playbackState == Player.STATE_ENDED) left--
            }
            autoPlayNext()
        }
    }

    private fun hideNextCard() {
        countdownJob?.cancel()
        nextCard.visibility = View.GONE
        playerView.controllerAutoShow = true
    }

    private fun cancelNext() {
        nextCancelled = true
        hideNextCard()
        playerView.requestFocus()
    }

    /** The countdown ran out (or the episode ended): play on, unless enough untouched episodes went by to ask first. */
    private fun autoPlayNext() {
        val untouched = com.kirtanya.reel.core.player.AutoNext.untouchedAfter(untouchedBefore, interacted)
        if (com.kirtanya.reel.core.player.AutoNext.askStillWatching(untouched)) showStillWatching()
        else playNext(auto = true)
    }

    /**
     * "Still watching?" with Continue / Stop instead of the next episode; nobody answers in 60 s → the
     * player stops and closes (the TV can go to its screensaver).
     */
    private fun showStillWatching() {
        if (overlays.stillCard.visibility == View.VISIBLE) return
        hideNextCard()
        overlays.stillCard.visibility = View.VISIBLE
        if (isTelevision()) overlays.stillContinue.requestFocus()
        stat("stillWatching", mapOf("asked" to true, "untouched" to untouchedBefore + 1))
        stillJob?.cancel()
        stillJob = lifecycleScope.launch {
            delay(com.kirtanya.reel.core.player.AutoNext.ASK_TIMEOUT_MS)
            stat("stillWatching", mapOf("answer" to "timeout"))
            player?.pause()
            saveProgress()
            finish()
        }
    }

    private fun answerStillWatching(keepGoing: Boolean) {
        stillJob?.cancel()
        overlays.stillCard.visibility = View.GONE
        stat("stillWatching", mapOf("answer" to if (keepGoing) "continue" else "stop"))
        if (keepGoing) playNext() else {
            player?.pause()
            saveProgress()
            finish()
        }
    }

    /**
     * Plays the next episode. [auto] = the countdown did it (the untouched-episodes count carries on);
     * by hand (Play now, Skip credits, Next) the remaining time teaches where this show's credits start.
     */
    private fun playNext(auto: Boolean = false) {
        val (next, source) = nextReady ?: return
        // Never jump to the next episode from the background.
        if (leavingForNext || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        leavingForNext = true
        saveProgress()
        if (!auto) learnCredits()
        flushIntroLearning()
        val untouched = if (auto) com.kirtanya.reel.core.player.AutoNext.untouchedAfter(untouchedBefore, interacted) else 0
        if (source != null) {
            startActivity(forPlayback(this, next).putExtra(EXTRA_UNTOUCHED, untouched).apply {
                // A sleep timer in minutes keeps running into the next episode.
                if (sleepChoice.minutes != null) putExtra(EXTRA_SLEEP, sleepChoice.name).putExtra(EXTRA_SLEEP_AT, sleepSetAt)
            })
        } else openSources(next.episode)
        finish()
    }

    // ---------------------------------------------------------------- remote control

    /** Key whose press we used for seeking: its release is ours too, or PlayerView would open the controls. */
    private var seekKey: Int? = null
    private var lastSeekAt = 0L

    /** Any touch counts as watching (Still watching? asks only after untouched episodes). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) interacted = true
        return super.dispatchTouchEvent(ev)
    }

    /**
     * TV remote: ←/→ skip one step (Settings → Playback, 10 s by default) while the controls are hidden
     * (with them shown, the controls take the keys). Holding the key keeps skipping, about three times a
     * second, 30 s at a time after 2 s. OK presses the Skip chip while it shows and the strip is hidden.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) interacted = true
        val exo = player
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && skipChip.visibility == View.VISIBLE && tvControls?.visible == false && nextCard.visibility != View.VISIBLE &&
            overlays.stillCard.visibility != View.VISIBLE && resumeChip.visibility != View.VISIBLE
        ) {
            if (event.action == KeyEvent.ACTION_UP) skipNow()
            return true
        }
        val isSeekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (isSeekKey && event.action == KeyEvent.ACTION_UP && seekKey == event.keyCode) {
            seekKey = null
            return true
        }
        val overlayUp = nextCard.visibility == View.VISIBLE || resumeChip.visibility == View.VISIBLE || errorPanel.visibility == View.VISIBLE ||
            overlays.stillCard.visibility == View.VISIBLE
        val controls = tvControls
        // TV: the strip takes OK/↑/↓/Back/media keys first (unless a chip or card holds the remote).
        if (controls != null && exo != null && errorPanel.visibility != View.VISIBLE && (controls.visible || !overlayUp) && controls.handleKey(event)) return true
        val controlsUp = controls?.visible ?: playerView.isControllerFullyVisible
        if (exo != null && isSeekKey && event.action == KeyEvent.ACTION_DOWN && !overlayUp &&
            (seekKey == event.keyCode || !controlsUp)
        ) {
            seekKey = event.keyCode
            val now = android.os.SystemClock.uptimeMillis()
            if (event.repeatCount > 0 && now - lastSeekAt < 300) return true
            lastSeekAt = now
            val held = event.eventTime - event.downTime
            val step = com.kirtanya.reel.core.player.SeekStep.remoteStepMs(options.seekStepS, held) * (if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            val end = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            val target = (exo.currentPosition + step).coerceIn(0, end)
            if (step < 0) {
                // Backward: the keyframe before the target (CLOSEST_SYNC could land after it), then back.
                exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC)
                exo.seekTo(target)
                exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            } else {
                exo.seekTo(target)
            }
            flashStatus((if (step > 0) "+" else "−") + "${kotlin.math.abs(step) / 1000} s · " + formatTime(target))
            return true
        }
        if (resumeChip.visibility == View.VISIBLE && resumeChip.hasFocus() && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER
        ) {
            hideResumeChip()
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- status + errors

    private fun startStats(h: String) {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val s = app.engine.snapshot(h)
                if (s != null && status.visibility == View.VISIBLE && player?.playbackState != Player.STATE_READY) {
                    val line = when {
                        s.phase == TorrentPhase.METADATA -> "Finding peers… ${s.peers} connected"
                        player == null -> "Preparing · ${s.peers} peers"
                        else -> "${formatBytes(s.downBps.toLong())}/s · ${s.peers} peers"
                    }
                    showStatus(line)
                }
                delay(1000)
            }
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun flashStatus(text: String) {
        showStatus(text)
        flashJob?.cancel()
        flashJob = lifecycleScope.launch {
            delay(1_200)
            if (player?.playbackState == Player.STATE_READY) status.visibility = View.GONE
        }
    }

    private fun showError(text: String, allowExternal: Boolean = true) {
        reportError(text)
        status.visibility = View.GONE
        nextCancelled = true
        hideNextCard()
        errorText.text = text
        errorPanel.visibility = View.VISIBLE
        errorExternal.visibility = if (allowExternal && (preparedStream != null || (playback.source.kind == SourceKind.DIRECT && streamUrl != null))) View.VISIBLE else View.GONE
        errorSources.visibility = if (playback.tmdbId != null) View.VISIBLE else View.GONE
        (if (errorSources.visibility == View.VISIBLE) errorSources else errorPanel.getChildAt(errorPanel.childCount - 1)).requestFocus()
    }

    /**
     * Hands the stream to VLC / MX / Just Player. Torrents get the engine's HTTP URL (the in-app
     * player reads directly, which other apps can't); direct links keep their own URL.
     */
    private fun openExternal() {
        val torrentInfo = preparedStream
        val url = when {
            torrentInfo != null -> app.engine.httpUrlFor(torrentInfo)
            playback.source.kind == SourceKind.DIRECT -> streamUrl
            else -> null
        } ?: return
        val mime = torrentInfo?.mimeType ?: "video/*"
        externalHandoff = true
        stoppedSinceHandoff = false
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime), "Open with"))
    }

    /** Back to Reel's Sources screen for this title / episode; [resumeAtMs] = the next source starts there. */
    private fun openSources(episode: EpisodeNumber?, resumeAtMs: Long? = null) {
        val type = playback.tmdbType ?: return
        val id = playback.tmdbId ?: return
        startActivity(MainActivity.openSources(this, type, id, episode?.season, episode?.episode, resumeAtMs))
    }

    /** Where this film / episode is now (or where it was about to start, while still finding peers). */
    private fun currentPositionMs(): Long =
        player?.takeIf { it.playbackState != Player.STATE_IDLE || firstFrameShown }?.currentPosition ?: startAtMs

    /**
     * Settings → Change source: the Sources page for this film / episode; the source picked there
     * plays from here. This player saves its position and closes, which stops its stream (onDestroy,
     * owner-checked), so the new one never competes with it. History keeps one entry (same key) and
     * the new player's first save makes the new source the remembered one.
     */
    private fun changeSource() {
        if (!PlayerSourceChange.available(playback) || leavingForNext) return
        val at = currentPositionMs()
        saveProgress()
        leavingForNext = true
        Log.i(TAG, "change source for ${playback.key} at ${formatTime(at)}")
        openSources(playback.episode, at)
        finish()
    }

    /** The Change source row for the settings panel: off (with a note) for a file without TMDB info. */
    private fun changeSourceRow() =
        if (PlayerSourceChange.available(playback)) PanelExtra(CHANGE_SOURCE_ROW) { changeSource() }
        else PanelExtra(CHANGE_SOURCE_ROW, note = PlayerSourceChange.NO_SOURCES_NOTE, action = null)

    // ---------------------------------------------------------------- picture in picture

    private fun pipParams(): android.app.PictureInPictureParams {
        val v = player?.videoSize
        val ratio = if (v != null && v.width > 0 && v.height > 0) {
            // Android allows 1:2.39 … 2.39:1.
            val r = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(1 / 2.39f, 2.39f)
            android.util.Rational((r * 1000).toInt(), 1000)
        } else android.util.Rational(16, 9)
        return android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()
    }

    private fun enterPip() {
        if (isTelevision() || player == null) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Home while a film plays: keep watching in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !externalHandoff && !leavingForNext) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            phoneControls?.hideNow()
            status.visibility = View.GONE
            hideResumeChip()
            hideSkipChip()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        preparedStream?.let { app.engine.resumeStream(it.infoHash, it) }
        // We stopped the player in onStop: reload from the same position (from disk, quickly).
        // After a real hand-off to another player, stay idle; its play button prepares again.
        val exo = player
        if (exo != null && exo.playbackState == Player.STATE_IDLE && streamUrl != null && errorPanel.visibility != View.VISIBLE &&
            !(externalHandoff && stoppedSinceHandoff)
        ) {
            // Preparing again anyway: late subtitles join here for free.
            if (pendingSubs.isNotEmpty()) attachSubtitles(pendingSubs, "return", prepare = false)
            exo.prepare()
        }
    }

    override fun onResume() {
        super.onResume()
        // The chooser is translucent: if it was dismissed without opening anything, we never
        // stopped, and the stream is ours again.
        if (externalHandoff && !stoppedSinceHandoff) externalHandoff = false
        // Lifecycle is STARTED here (not yet in onStart), so the card can come back.
        if (nextCardPending) {
            nextCardPending = false
            showNextCard()
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        flushIntroLearning()
        // Stop (not just pause): ends the loader and closes our reader, keeping the item and position.
        player?.stop()
        reconnectJob?.cancel()
        if (externalHandoff) stoppedSinceHandoff = true
        // In the background: stop fetching the torrent (an external player keeps it running).
        else if (!isFinishing && !leavingForNext) preparedStream?.let { app.engine.suspendStream(it.infoHash, it) }
        if (nextCard.visibility == View.VISIBLE) {
            nextCardPending = true
            hideNextCard()
        }
    }

    override fun onDestroy() {
        if (::ambient.isInitialized) ambient.release()
        effects.release()
        stillJob?.cancel()
        tickJob?.cancel()
        statsJob?.cancel()
        progressJob?.cancel()
        player?.removeListener(listener)
        player?.release()
        player = null
        preparedStream?.let { info ->
            val h = info.infoHash
            // Handed to VLC/MX: keep serving while it reads (or read in the last 30 s, between
            // its requests); the engine stops it once it goes quiet. Otherwise stop now.
            when {
                externalHandoff && app.engine.hasReaders(h, info) -> app.engine.stopWhenIdle(h, info)
                externalHandoff && app.engine.hasRecentActivity(h, info) -> app.engine.stopWhenIdle(h, info, idleMs = 2 * 60_000L)
                else -> app.engine.stopStream(h, info)
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- problem reports (docs/MACHINE_FIRST_PLAN.md step 1)

    /** Why the error panel is up (the exception behind [showError]), for its report. */
    private var errorCause: Throwable? = null

    /** Every error the user sees saves a report (rate-limited per title), once the panel is drawn. */
    private fun reportError(text: String) {
        val cause = errorCause
        val code = (cause as? PlaybackException)?.errorCodeName
        playerView.postDelayed({
            if (isDestroyed) return@postDelayed
            app.reports.auto(
                "error", text, key = playback.key,
                extra = mapOf("exception" to cause?.javaClass?.name, "message" to cause?.message, "errorCode" to code,
                    "causes" to cause?.let { c -> generateSequence(c) { it.cause }.drop(1).joinToString(" ← ") { it.toString() }.ifEmpty { null } }),
                stack = cause?.stackTraceToString(),
            ) { app.reports.capture(this) }
        }, 300)
    }

    /**
     * "Report a problem" in the settings panel. Waits for the closing panel to leave the screen, so
     * the screenshot shows the film. TV: saved at once (no typing on a remote); phone: optional note.
     */
    private fun reportFromSettings() {
        playerView.postDelayed({
            if (isDestroyed) return@postDelayed
            if (isTelevision()) app.reports.saveManual(this, "player-settings") { dir -> flashStatus(if (dir != null) "Report saved" else "Couldn't save the report") }
            else app.reports.ask(this, "player-settings")
        }, 400)
    }

    override fun reportState(): Map<String, Any?> {
        if (!::playback.isInitialized) return emptyMap()
        val exo = player
        val src = playback.source
        return linkedMapOf(
            "title" to playback.displayTitle,
            "titleKey" to playback.titleKey,
            "key" to playback.key,
            "sourceKind" to src.kind.name,
            "provider" to src.provider,
            "quality" to src.quality.label,
            "sourceSize" to src.sizeBytes,
            // The host only: direct links often carry a signed token in the path or query.
            "sourceHost" to src.url?.let { runCatching { Uri.parse(it).host }.getOrNull() },
            "state" to exo?.playbackState?.let { listOf("?", "idle", "buffering", "ready", "ended").getOrNull(it) },
            "playWhenReady" to exo?.playWhenReady,
            "playing" to exo?.isPlaying,
            "positionMs" to exo?.currentPosition,
            "durationMs" to exo?.duration?.takeIf { it != C.TIME_UNSET },
            "bufferedMs" to exo?.bufferedPosition,
            "notReadyForMs" to notReadySince.takeIf { it > 0 }?.let { now() - it },
            "openForMs" to now() - tapMs,
            "firstFrame" to firstFrameShown,
            "video" to exo?.videoFormat?.let { "${it.sampleMimeType} ${it.codecs ?: ""} ${it.width}x${it.height} ${it.bitrate}bps".trim() },
            "audio" to exo?.audioFormat?.let { "${it.sampleMimeType} ${it.codecs ?: ""} ${it.channelCount}ch ${it.language ?: ""}".trim() },
            "status" to status.text?.toString()?.takeIf { status.visibility == View.VISIBLE },
            "error" to errorText.text?.toString()?.takeIf { errorPanel.visibility == View.VISIBLE },
            "nextCard" to (nextCard.visibility == View.VISIBLE),
            "externalHandoff" to externalHandoff,
            "tv" to isTelevision(),
        )
    }

    override fun reportStreamKey(): String? = hash

    override fun reportVideo(): android.view.SurfaceView? =
        if (::playerView.isInitialized) playerView.videoSurfaceView as? android.view.SurfaceView else null

    // ---------------------------------------------------------------- per-title memory (tracks, speed, delays)

    /**
     * This title's remembered choices beat the app-wide ones: subtitles off / language, audio language,
     * speed (TitleStart). The subtitle track's label, when saved, is matched once the tracks are known.
     */
    private fun applyTitlePrefs(exo: ExoPlayer) {
        val subs = com.kirtanya.reel.core.player.TitleStart.subtitles(saved)
        val audio = saved?.audioLanguage
        if (subs != null || audio != null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
                if (subs?.off == true) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else if (subs?.language != null) setPreferredTextLanguage(subs.language)
                if (audio != null) setPreferredAudioLanguage(audio)
            }.build()
        }
        val speed = com.kirtanya.reel.core.player.TitleStart.speed(saved)
        if (speed != 1f) exo.setPlaybackSpeed(speed)
        if (saved != null) stat("titlePrefs", mapOf("subs" to (saved?.subtitleLanguage ?: "-"), "audio" to (audio ?: "-"), "speed" to speed, "picture" to (saved?.picture ?: "-")))
    }

    private var savedLabelApplied = false

    /** Two tracks in the remembered language (e.g. "SDH" and "Forced"): pick the one with the saved label. */
    private fun applySavedSubtitleLabel(tracks: androidx.media3.common.Tracks) {
        if (savedLabelApplied) return
        val label = saved?.subtitleLabel ?: return
        val lang = com.kirtanya.reel.core.player.TitleStart.subtitles(saved)?.takeIf { !it.off }?.language ?: return
        val exo = player ?: return
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (f.label == label && f.language?.startsWith(lang) == true) {
                    savedLabelApplied = true
                    if (!g.isTrackSelected(i)) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    return
                }
            }
        }
    }

    /** The user picked a track or a speed in the panel: remember it for this title (and its next episodes). */
    private fun onPanelChoice(kind: String) {
        val exo = player ?: return
        val params = exo.trackSelectionParameters
        fun chosen(type: Int): androidx.media3.common.Format? =
            params.overrides.values.firstOrNull { it.type == type && it.trackIndices.isNotEmpty() }?.let { it.mediaTrackGroup.getFormat(it.trackIndices[0]) }
        when (kind) {
            "sub" -> {
                val off = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                val f = chosen(C.TRACK_TYPE_TEXT)
                app.titlePrefs.update(playback.titleKey) {
                    if (off) it.copy(subtitleLanguage = com.kirtanya.reel.core.playback.TitlePrefs.SUBTITLES_OFF, subtitleLabel = null)
                    else it.copy(subtitleLanguage = f?.language?.takeIf { l -> l != C.LANGUAGE_UNDETERMINED } ?: it.subtitleLanguage, subtitleLabel = f?.label)
                }
            }
            "audio" -> chosen(C.TRACK_TYPE_AUDIO)?.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { lang ->
                app.titlePrefs.update(playback.titleKey) { it.copy(audioLanguage = lang) }
            }
            "speed" -> {
                val speed = exo.playbackParameters.speed
                app.titlePrefs.update(playback.titleKey) { it.copy(speed = if (speed == 1f) null else speed) }
            }
        }
        stat("titlePrefSaved", mapOf("what" to kind))
    }

    // ---------------------------------------------------------------- settings panel rows (audio, subtitles, sleep)

    private val onOff = listOf("Off", "On")

    /** The panel's Subtitles / Audio / Playback rows; set again when something they show changes (effects refused). */
    private fun setPanelRows() {
        val subtitle = listOf<PanelRow>(
            PanelSlider(com.kirtanya.reel.R.drawable.ic_pl_sync, "Subtitle delay", com.kirtanya.reel.core.player.Delay.SUBTITLES, { (delays.subtitlesUs / 1000).toInt() }) { ms ->
                delays.subtitlesUs = ms * 1_000L
                store.updateDelays(playback.titleKey) { it.copy(subtitleDelayMs = ms) }
            },
            if (playback.tmdbId != null) PanelExtra(SEARCH_SUBS_ROW, icon = com.kirtanya.reel.R.drawable.ic_pl_search) { searchSubtitlesAgain() }
            else PanelExtra(SEARCH_SUBS_ROW, note = "Needs a film or show from search", icon = com.kirtanya.reel.R.drawable.ic_pl_search, action = null),
        )
        val fxRows = if (effects.available) listOf<PanelRow>(
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_night, "Night mode", onOff, { if (options.nightMode) 1 else 0 }) { i ->
                store.updateOptions { it.copy(nightMode = i == 1) }
                applyEffects()
            },
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_voice, "Voice boost", onOff, { if (options.voiceBoost) 1 else 0 }) { i ->
                store.updateOptions { it.copy(voiceBoost = i == 1) }
                applyEffects()
            },
        ) else listOf<PanelRow>(
            PanelExtra("Night mode", note = FX_UNAVAILABLE, icon = com.kirtanya.reel.R.drawable.ic_pl_night, action = null),
            PanelExtra("Voice boost", note = FX_UNAVAILABLE, icon = com.kirtanya.reel.R.drawable.ic_pl_voice, action = null),
        )
        val audio = fxRows + PanelSlider(com.kirtanya.reel.R.drawable.ic_pl_sync, "Audio delay", com.kirtanya.reel.core.player.Delay.AUDIO, { (delays.audioUs / 1000).toInt() }) { ms ->
            delays.audioUs = ms * 1_000L
            store.updateDelays(playback.titleKey) { it.copy(audioDelayMs = ms) }
        }
        val sleeps = com.kirtanya.reel.core.player.SleepChoice.entries
        val playbackRows = listOf<PanelRow>(
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_timer, "Sleep timer", sleeps.map { it.label }, { sleepChoice.ordinal }, asList = true) { i -> setSleep(sleeps[i]) },
        )
        tvControls?.setRows(subtitle, audio, playbackRows, ::onPanelChoice)
        phoneControls?.setRows(subtitle, audio, playbackRows, ::onPanelChoice)
    }

    /** Night mode / voice boost on this player's audio session; a refusal turns the rows into notes. */
    private fun applyEffects() {
        val exo = player ?: return
        val wasAvailable = effects.available
        effects.apply(exo.audioSessionId, options.nightMode, options.voiceBoost)
        if (wasAvailable && !effects.available) {
            setPanelRows()
            tvControls?.refreshPanel()
            phoneControls?.refreshPanel()
        }
    }

    /** "Search subtitles again": asks every subtitle addon afresh and adds what's new (one short re-buffer). */
    private fun searchSubtitlesAgain() {
        flashStatus("Searching for subtitles…")
        lifecycleScope.launch {
            val started = now()
            val found = findSubtitles().tracks
            val have = player?.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().map { it.uri.toString() }.toSet()
            val extra = found.filter { it.url !in have }
            stat("subsResearch", mapOf("found" to found.size, "new" to extra.size, "ms" to now() - started))
            if (extra.isEmpty()) {
                flashStatus(if (found.isEmpty()) "No subtitles found" else "No new subtitles")
            } else {
                attachSubtitles(extra, "research")
                flashStatus("Added ${extra.size} subtitle ${if (extra.size == 1) "track" else "tracks"}")
            }
        }
    }

    // ---------------------------------------------------------------- skip intro / recap / credits

    /**
     * Twice a second while playing: the Skip chip (chapters, then the learned intro), the sleep timer,
     * and closing a quiet burst of seeks (learning the intro).
     */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    player?.let { exo ->
                        seekBurst.flush(now())?.let(::learnIntro)
                        updateSkipChip(exo)
                        tickSleep(exo)
                    }
                    delay(500)
                }
            }
        }
    }

    private fun updateSkipChip(exo: ExoPlayer) {
        if (!firstFrameShown || exo.playbackState == Player.STATE_ENDED) return hideSkipChip()
        // With a next episode the credits belong to Up next; a credits chapter only skips (to the end) otherwise.
        val chapters = if (playback.next != null) chapterSkips.filter { it.first != com.kirtanya.reel.core.player.SkipKind.CREDITS } else chapterSkips
        val learned = if (playback.episode != null) com.kirtanya.reel.core.player.SkipLearning.introWindow(store.show(playback.titleKey)) else null
        val chip = com.kirtanya.reel.core.player.SkipPlan.chipAt(exo.currentPosition, chapters, learned)
        val busy = nextCard.visibility == View.VISIBLE || overlays.stillCard.visibility == View.VISIBLE || errorPanel.visibility == View.VISIBLE || isInPictureInPictureMode ||
            phoneControls?.panelOpen == true || tvControls?.panelOpen == true
        if (chip == null || busy) return hideSkipChip()
        if (chip == chipShown) return
        chipShown = chip
        skipChip.text = chip.first.label
        skipChip.visibility = View.VISIBLE
        overlays.placeSkipChip(phoneControls?.coveredBottomPx() ?: 0)
        // TV: focused (its ring shows OK will press it) unless the strip or another chip has the remote.
        if (isTelevision() && tvControls?.visible != true && resumeChip.visibility != View.VISIBLE) skipChip.requestFocus()
        stat("skipChip", mapOf("kind" to chip.first.name, "from" to if (chapters.any { it == chip }) "chapter" else "learned",
            "startS" to chip.second.startMs / 1000, "endS" to chip.second.endMs / 1000))
    }

    private fun hideSkipChip() {
        if (chipShown == null && skipChip.visibility != View.VISIBLE) return
        chipShown = null
        val hadFocus = skipChip.hasFocus()
        skipChip.visibility = View.GONE
        if (hadFocus) playerView.requestFocus()
    }

    /** The Skip chip: to the end of the intro / recap / credits (credits of a last episode or film: the end). */
    private fun skipNow() {
        val (kind, window) = chipShown ?: return
        val exo = player ?: return
        ownSeek = true
        val end = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        stat("skipUsed", mapOf("kind" to kind.name, "skippedS" to (window.endMs - exo.currentPosition) / 1000))
        exo.seekTo(if (end != null) window.endMs.coerceAtMost(end) else window.endMs)
        hideSkipChip()
    }

    /** A burst of the user's seeks ended: a 30–150 s jump in the first 6 minutes is this show's intro. */
    private fun learnIntro(jump: Pair<Long, Long>) {
        val (from, to) = jump
        if (playback.episode == null || !com.kirtanya.reel.core.player.SkipLearning.isIntroSkip(from, to)) return
        store.updateShow(playback.titleKey) { com.kirtanya.reel.core.player.SkipLearning.addIntro(it, com.kirtanya.reel.core.player.IntroSkip(playback.key, from, to)) }
        stat("introLearned", mapOf("fromS" to from / 1000, "toS" to to / 1000,
            "episodes" to store.show(playback.titleKey)?.intros?.size))
    }

    private fun flushIntroLearning() {
        seekBurst.flush(now(), force = true)?.let(::learnIntro)
    }

    /** The user started the next episode by hand: how much was left says where this show's credits begin. */
    private fun learnCredits() {
        val exo = player ?: return
        if (playback.episode == null) return
        val d = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val left = d - exo.currentPosition
        val before = store.show(playback.titleKey)
        store.updateShow(playback.titleKey) { com.kirtanya.reel.core.player.SkipLearning.addCredits(it, playback.key, left) }
        if (store.show(playback.titleKey) != before) stat("creditsLearned", mapOf("remainingS" to left / 1000,
            "medianS" to com.kirtanya.reel.core.player.SkipLearning.creditsRemainingMs(store.show(playback.titleKey))?.div(1000)))
    }

    /** MKV chapters arrive as track metadata (media3 1.11): their titles say where intros / recaps / credits are. */
    private fun readChapters(tracks: androidx.media3.common.Tracks) {
        val marks = mutableListOf<com.kirtanya.reel.core.player.ChapterMark>()
        for (g in tracks.groups) for (i in 0 until g.length) {
            val meta = g.getTrackFormat(i).metadata ?: continue
            for (e in 0 until meta.length()) {
                val c = meta.get(e) as? androidx.media3.extractor.metadata.Chapter ?: continue
                if (c.isHidden) continue
                val end = c.endTimeMs.takeIf { it != C.TIME_UNSET && it > 0 }
                marks += com.kirtanya.reel.core.player.ChapterMark(c.startTimeMs, end, c.title?.value)
            }
        }
        val distinct = marks.distinct()
        if (distinct == chapterMarks) return
        chapterMarks = distinct
        val d = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        chapterSkips = com.kirtanya.reel.core.player.ChapterSkips.windows(distinct, d)
        stat("chapters", mapOf("count" to distinct.size, "skippable" to chapterSkips.joinToString(",") { it.first.name }))
    }

    // ---------------------------------------------------------------- sleep timer

    private fun setSleep(choice: com.kirtanya.reel.core.player.SleepChoice) {
        sleepChoice = choice
        sleepSetAt = now()
        if (sleepFading) { player?.volume = 1f; sleepFading = false }
        if (choice == com.kirtanya.reel.core.player.SleepChoice.OFF) overlays.sleepChip.visibility = View.GONE
        stat("sleepTimer", mapOf("set" to choice.name))
    }

    /** "End of episode": the countdown and auto-play stand aside; the episode fades out and stops. */
    private fun sleepAtEnd() = sleepChoice == com.kirtanya.reel.core.player.SleepChoice.END_OF_EPISODE

    /** The last 10 s fade the sound out, then playback pauses; under a minute a chip shows what's left. */
    private fun tickSleep(exo: ExoPlayer) {
        if (sleepChoice == com.kirtanya.reel.core.player.SleepChoice.OFF) return
        val d = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val left = com.kirtanya.reel.core.player.SleepTimer.remainingMs(sleepChoice, sleepSetAt, now(), exo.currentPosition, d, exo.playbackParameters.speed) ?: return
        val state = com.kirtanya.reel.core.player.SleepTimer.state(left)
        overlays.sleepChip.text = "Sleep in " + formatTime(state.remainingMs + 999)
        overlays.sleepChip.visibility = if (state.showChip && !state.done) View.VISIBLE else View.GONE
        if (state.volume < 1f || sleepFading) {
            sleepFading = true
            exo.volume = state.volume
        }
        if (state.done) {
            exo.pause()
            exo.volume = 1f
            sleepFading = false
            sleepChoice = com.kirtanya.reel.core.player.SleepChoice.OFF
            overlays.sleepChip.visibility = View.GONE
            cancelNext()
            saveProgress()
            stat("sleepTimer", mapOf("done" to true))
            flashStatus("Sleep timer · paused")
        }
    }

    // ---------------------------------------------------------------- picture (Fit / Fill / Stretch / shapes)

    /** How the picture fills the screen now: this title's choice, else the last one picked anywhere. */
    private var pictureOverride: com.kirtanya.reel.core.playback.PictureMode? = null
    private val currentPicture: com.kirtanya.reel.core.playback.PictureMode
        get() = pictureOverride ?: com.kirtanya.reel.core.player.TitleStart.picture(saved, prefs.picture)

    /** Picks and remembers how the picture fills the screen: for this title, and as the default for new ones. */
    private fun setPicture(mode: com.kirtanya.reel.core.playback.PictureMode) {
        pictureOverride = mode
        app.playbackSettings.update { it.copy(picture = mode) }
        app.titlePrefs.update(playback.titleKey) { it.copy(picture = mode.name) }
        phoneControls?.syncZoom(mode == com.kirtanya.reel.core.playback.PictureMode.FILL)
        applyPicture(mode)
    }

    /**
     * Fit = whole picture with bars; Fill = crop to the screen; Stretch = squeeze to the screen; a
     * forced shape (16:9, 4:3, 21:9) fits that shape instead of the file's (for files that say the
     * wrong one). Cinema mode looks for bars again afterwards (Fill / Stretch leave none).
     */
    private fun applyPicture(mode: com.kirtanya.reel.core.playback.PictureMode = currentPicture) {
        if (!::playerView.isInitialized) return
        playerView.resizeMode = when (mode) {
            com.kirtanya.reel.core.playback.PictureMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            com.kirtanya.reel.core.playback.PictureMode.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val frame = playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        val own = player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width * it.pixelWidthHeightRatio / it.height }
        (mode.ratio ?: own)?.let { frame?.setAspectRatio(it) }
        if (::ambient.isInitialized) playerView.post { ambient.refresh() }
    }

    // ---------------------------------------------------------------- helpers

    private fun sourceRequest(episode: EpisodeNumber?): SourceRequest? {
        val id = playback.tmdbId ?: return null
        val type = if (playback.tmdbType == "tv") MediaType.TV else MediaType.MOVIE
        return SourceRequest(id, playback.imdbId, type, playback.title, playback.year, episode?.season, episode?.episode)
    }

    // ---------------------------------------------------------------- Afterglow overlays (docs/DESIGN_STYLE.md)

    private var directRetries = 0

    /** What a playback error means for the user (docs/STREAMING_V2.md §6.3). */
    private fun handlePlayerError(error: PlaybackException) {
        errorCause = error
        val exo = player
        var cause: Throwable? = error.cause
        var gone: com.kirtanya.reel.core.stream.StreamGoneException? = null
        var engineError: com.kirtanya.reel.core.stream.StreamErrorException? = null
        var httpCode: Int? = null
        var failedUri: String? = null
        while (cause != null) {
            when (cause) {
                is com.kirtanya.reel.core.stream.StreamGoneException -> gone = cause
                is com.kirtanya.reel.core.stream.StreamErrorException -> engineError = cause
                is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> { httpCode = cause.responseCode; failedUri = cause.dataSpec.uri.toString() }
                is androidx.media3.datasource.HttpDataSource.HttpDataSourceException -> failedUri = failedUri ?: cause.dataSpec.uri.toString()
            }
            cause = cause.cause
        }
        // A dead subtitle link must never stop the film: drop it and carry on.
        val subtitleUris = exo?.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map { it.uri.toString() }.orEmpty()
        if (exo != null && failedUri != null && failedUri in subtitleUris) {
            val item = exo.currentMediaItem!!
            val kept = item.localConfiguration!!.subtitleConfigurations.filter { it.uri.toString() != failedUri }
            val pos = exo.currentPosition
            exo.setMediaItem(item.buildUpon().setSubtitleConfigurations(kept).build(), pos)
            exo.prepare()
            Log.i(TAG, "dropped a broken subtitle track")
            return
        }
        when {
            engineError != null -> showError(engineError.message ?: "This stream stopped", allowExternal = false)
            gone != null -> if (!isFinishing && !leavingForNext) showError("This stream stopped.", allowExternal = false)
            playback.source.kind == SourceKind.TORRENT && error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                stats { it.fatalSlowError() }
                showError("Waiting for peers that have this part of the video. Try again in a moment, or pick another source.")
            }
            playback.source.kind == SourceKind.DIRECT && error.errorCode in 2000..2999 -> {
                if (httpCode == 403 || httpCode == 410) {
                    showError("This link has expired. Try another source.")
                } else if (exo != null && directRetries < 4) {
                    // A network blip: reconnect from the same position with a growing backoff.
                    val wait = 1_000L shl directRetries++
                    showStatus("Reconnecting…")
                    reconnectJob?.cancel()
                    reconnectJob = lifecycleScope.launch {
                        delay(wait)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player?.prepare()
                    }
                } else {
                    showError("Couldn't reach this link. Check your connection or try another source.")
                }
            }
            error.errorCode in 2000..2999 -> {
                stats { it.fatalSlowError() }
                showError("This stream stopped (${error.errorCodeName}).")
            }
            else -> showError("This device can't play this file (${error.errorCodeName}).")
        }
    }

    /**
     * Twice a second while visible: tell the engine where the playhead and buffer are, so urgent
     * pieces are timed against real playback (docs/STREAMING_V2.md §3.5). Stops in the background,
     * so the signals go stale there.
     */
    private fun startSignals() {
        val info = preparedStream ?: return
        val signals = app.engine.playerSignals(info) ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    player?.let { exo ->
                        signals.positionMs = exo.currentPosition
                        signals.bufferedPositionMs = exo.bufferedPosition
                        signals.durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0
                        signals.playWhenReady = exo.playWhenReady
                        signals.playing = exo.isPlaying
                        signals.updatedAtMs = now()
                        stallWatch(exo)
                    }
                    delay(500)
                }
            }
        }
    }

    private var notReadySince = 0L
    private var lastStallDump = 0L
    private var stallReported = false

    /** Not ready for 5 s while wanting to play: log what ExoPlayer's threads are doing (every 5 s). */
    private fun stallWatch(exo: ExoPlayer) {
        val t = now()
        if (exo.playbackState == Player.STATE_READY || !exo.playWhenReady || exo.playbackState == Player.STATE_IDLE) { notReadySince = 0; return }
        if (notReadySince == 0L) notReadySince = t
        if (!stallReported && t - notReadySince >= STALL_REPORT_MS) {
            // Once per stream (and at most once per 10 min per title, ReportPolicy): a report with the screen and logs.
            stallReported = true
            app.reports.auto("stall", "Buffering for ${(t - notReadySince) / 1000} s", key = playback.key,
                extra = mapOf("bufferingMs" to t - notReadySince, "loading" to exo.isLoading)) { app.reports.capture(this) }
        }
        if (t - notReadySince < 5_000 || t - lastStallDump < 5_000) return
        lastStallDump = t
        logStat("stall", mapOf("ms" to t - notReadySince, "state" to exo.playbackState, "loading" to exo.isLoading,
            "pos" to exo.currentPosition, "buf" to exo.bufferedPosition))
        Thread.getAllStackTraces().filterKeys { it.name.contains("ExoPlayer") || it.name.contains("Loader") }.forEach { (th, st) ->
            Log.i("ReelStats", "ev=stack th=${th.name} state=${th.state} at=" + st.take(8).joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
        }
    }

    // ReelStats (docs/STREAMING_V2.md §11): the engine's per-stream counters, fed from player events.
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private inline fun stats(block: (com.kirtanya.reel.core.stream.StreamStats) -> Unit) {
        hash?.let { com.kirtanya.reel.engine.EngineStats.of(it) }?.let(block)
    }
    private fun logStat(event: String, fields: Map<String, Any?>) {
        hash?.let { com.kirtanya.reel.engine.EngineStats.event(it, event, fields) }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(PlayerColors.VIDEO_BACKGROUND) }
        val tv = isTelevision()
        overlays = PlayerOverlays(this, ::font, tv)
        val levels = com.kirtanya.reel.core.playback.Ambient.Level.entries
        val cinema = PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Cinema mode", levels.map { it.label }, { prefs.cinemaGlow.ordinal }) { i ->
            app.playbackSettings.update { it.copy(cinemaGlow = levels[i]) }
            ambient.level = levels[i]
        }
        val modes = com.kirtanya.reel.core.playback.PictureMode.entries
        val picture = PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Picture", modes.map { it.label }, { currentPicture.ordinal }, asList = true) { i ->
            setPicture(modes[i])
        }
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            // No centre spinner: a small chip at the top right says it (TV and phone).
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setShowSubtitleButton(true)
            if (tv) {
                useController = false
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }
        // Cinema mode: the glow sits under the video; the player view itself stays see-through so
        // the bars show it (the video surface still covers the middle).
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Re-preparing (late subtitles at a pause, back from the background) keeps the last frame up.
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        ambient = AmbientGlow(playerView).apply { level = prefs.cinemaGlow }
        applyPicture()
        root.addView(ambient, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        if (tv) {
            tvControls = TvControls(
                playerView,
                playback.displayTitle,
                ::font,
                extraSettings = {
                    listOf(changeSourceRow()) +
                        (if (preparedStream != null || playback.source.kind == SourceKind.DIRECT) listOf(PanelExtra("Open in another player") { openExternal() })
                        else emptyList()) + PanelExtra(REPORT_ROW) { reportFromSettings() }
                },
                hasNext = { nextReady != null },
                onNext = { playNext() },
            ).also {
                it.setToggles(listOf(picture, cinema))
                it.seekStepMs = options.seekStepS * 1_000L
                root.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            }
            setPanelRows()
            overlays.addTo(root)
            wireOverlays()
            buildErrorPanel(root)
            return root
        }
        playerView.useController = false
        playerView.findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(PlayerColors.FOREGROUND)
        phoneControls = PhoneControls(
            activity = this,
            playerView = playerView,
            title = playback.displayTitle,
            font = ::font,
            extraSettings = {
                listOf(changeSourceRow()) +
                    (if (preparedStream != null || playback.source.kind == SourceKind.DIRECT) listOf(PanelExtra("Open in another player") { openExternal() })
                    else emptyList()) + PanelExtra(REPORT_ROW) { reportFromSettings() }
            },
            onBack = { finish() },
            onPip = if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) ({ enterPip() }) else null,
            nextLabel = { nextReady?.let { (n, _) -> n.episode?.label?.let { "Next · $it" } ?: "Next episode" } },
            onNext = { playNext() },
            onZoom = { fill ->
                setPicture(if (fill) com.kirtanya.reel.core.playback.PictureMode.FILL else com.kirtanya.reel.core.playback.PictureMode.FIT)
            },
        )
        phoneControls?.setToggles(listOf(picture, cinema))
        phoneControls?.seekStepMs = options.seekStepS * 1_000L
        phoneControls?.syncZoom(currentPicture == com.kirtanya.reel.core.playback.PictureMode.FILL)
        root.addView(phoneControls, FrameLayout.LayoutParams(-1, -1))
        setPanelRows()
        phoneControls?.onShown = {
            applyCaptionLook()
            overlays.placeSkipChip(phoneControls?.coveredBottomPx() ?: 0)
        }
        // The same small chips as the TV, top right (the phone controls keep the top clear); Skip above the band.
        overlays.addTo(root)
        wireOverlays()
        buildErrorPanel(root)
        return root
    }

    /** What the overlays' buttons do (PlayerOverlays builds them). */
    private fun wireOverlays() {
        nextPlay.setOnClickListener { playNext() }
        overlays.nextCancel.setOnClickListener { cancelNext() }
        resumeChip.setOnClickListener {
            player?.seekTo(0)
            hideResumeChip()
        }
        skipChip.setOnClickListener { skipNow() }
        overlays.stillContinue.setOnClickListener { answerStillWatching(true) }
        overlays.stillStop.setOnClickListener { answerStillWatching(false) }
    }

    private fun buildErrorPanel(root: FrameLayout) {
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Tokens.Night.canvas.copy(alpha = 0.94f).toArgb())
            errorText = text(20f, PlayerColors.FOREGROUND, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(48), 0, dp(48), dp(28))
            }
            addView(errorText)
            val gap = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) }
            errorSources = button("Other sources", primary = true) {
                openSources(playback.episode, currentPositionMs())
                finish()
            }
            addView(errorSources, LinearLayout.LayoutParams(-2, -2))
            errorExternal = button("Open in another player") { openExternal() }
            addView(errorExternal, gap)
            addView(button("Back") { finish() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun font(medium: Boolean) =
        androidx.core.content.res.ResourcesCompat.getFont(this, if (medium) com.kirtanya.reel.R.font.instrument_sans_semibold else com.kirtanya.reel.R.font.instrument_sans_regular)

    private fun text(sizeSp: Float, color: Int, medium: Boolean = false) = overlays.text(sizeSp, color, medium)

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = overlays.button(label, primary, onClick)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTelevision() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {
        private const val TAG = "ReelPlayer"
        private const val SEARCH_SUBS_ROW = "Search subtitles again"
        private const val FX_UNAVAILABLE = "Not available on this device"
        /** Buffering this long while wanting to play saves a "stall" report. */
        private const val STALL_REPORT_MS = 15_000L
        private const val EXTRA_PLAYBACK = "playback"
        private const val EXTRA_START_MS = "startAtMs"
        /** Untouched episodes so far (Still watching?), carried to the next episode's player. */
        private const val EXTRA_UNTOUCHED = "untouched"
        /** A sleep timer in minutes carried to the next episode: its choice and when it was set (elapsedRealtime). */
        private const val EXTRA_SLEEP = "sleep"
        private const val EXTRA_SLEEP_AT = "sleepAt"
        private val json = Json { ignoreUnknownKeys = true }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        /** [startAtMs]: start exactly there (Change source hands the old player's position over). */
        fun forPlayback(context: Context, playback: Playback, startAtMs: Long? = null): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_PLAYBACK, json.encodeToString(Playback.serializer(), playback))
                .apply { if (startAtMs != null && startAtMs >= 0) putExtra(EXTRA_START_MS, startAtMs) }

        /** A magnet from Downloads or a browser: no TMDB info, history keyed by the file. */
        fun forMagnet(context: Context, magnet: String, title: String? = null, fileIndex: Int? = null, filename: String? = null): Intent {
            val parsed = Magnet.parse(magnet)
            val name = title ?: parsed?.name ?: "Torrent"
            return forPlayback(context, Playback.forTorrentFile(parsed?.infoHash.orEmpty(), fileIndex, name, magnet = magnet, filename = filename))
        }

        fun forTorrent(context: Context, infoHash: String, title: String? = null, fileIndex: Int? = null): Intent =
            forPlayback(context, Playback.forTorrentFile(infoHash, fileIndex, title ?: infoHash.take(8)))

        fun forUrl(context: Context, url: String, title: String? = null, headers: Map<String, String> = emptyMap()): Intent {
            val name = title ?: url.substringAfterLast('/')
            val source = Source(SourceKind.DIRECT, name, "Link", QualityParser.parse(name), url = url, headers = headers)
            return forPlayback(context, Playback(key = "url:$url", titleKey = "url:$url", title = name, source = source))
        }
    }
}
