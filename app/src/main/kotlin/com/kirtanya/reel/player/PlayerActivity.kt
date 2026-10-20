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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.MainActivity
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
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
    private var leavingForNext = false
    private var firstFrameShown = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var externalHandoff = false
    /** We were stopped after handing off: an external player really took over (not a cancelled chooser). */
    private var stoppedSinceHandoff = false

    private lateinit var playerView: PlayerView
    /** Cinema mode: the glow in the bars around the video. */
    private lateinit var ambient: AmbientGlow
    /** TV only: the bottom-strip controls (PlayerView's own controller is off there). */
    private var tvControls: TvControls? = null
    /** Phone only: our controls and gestures (PlayerView's controller is off everywhere). */
    private var phoneControls: PhoneControls? = null
    private lateinit var status: TextView
    private lateinit var resumeChip: Button
    private lateinit var nextCard: LinearLayout
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView
    private lateinit var nextPlay: Button
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Phones: landscape from the first frame (most video is wide; a portrait video turns back below).
        if (!isTelevision()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(buildLayout())
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
        val resumeAt = ContinueWatching.resumePositionMs(app.history.get(playback.key))
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
                // Torrents spend seconds finding peers, so subtitles are usually ready by now;
                // don't hold a direct link up for long.
                // Wait (briefly) only when a subtitle language is wanted; otherwise don't hold the start up.
                val wantSubs = prefs.subtitleLanguage != null ||
                    (getSystemService(CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager)?.isEnabled == true
                val tracks = if (wantSubs) withTimeoutOrNull(400L) { subtitles.await() } else null
                // Started in the background (the user left while peers were found): don't play
                // there; hold the torrent until we're visible again.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    preparedStream?.let { app.engine.suspendStream(it.infoHash, it) }
                    lifecycle.withStarted { }
                }
                val fromSource = source.subtitles.map {
                    it.copy(language = Subtitles.normalizeLanguage(it.language), format = it.format ?: Subtitles.formatOf(it.url))
                }
                play(url, (fromSource + tracks.orEmpty()).distinctBy { it.url }, resumeAt)
                if (tracks == null && wantSubs) {
                    // Slow subtitle addons: add their tracks if they arrive before the picture does.
                    val late = runCatching { subtitles.await() }.getOrNull().orEmpty()
                    val exo = player
                    val item = exo?.currentMediaItem
                    if (late.isNotEmpty() && exo != null && item != null && !firstFrameShown && errorPanel.visibility != View.VISIBLE) {
                        val have = item.localConfiguration?.subtitleConfigurations.orEmpty()
                        val haveUris = have.map { it.uri.toString() }.toSet()
                        val extra = late.filter { it.url !in haveUris }.map(::subtitleConfig)
                        if (extra.isNotEmpty()) {
                            exo.setMediaItem(item.buildUpon().setSubtitleConfigurations(have + extra).build(), exo.currentPosition.coerceAtLeast(resumeAt))
                            exo.prepare()
                        }
                    }
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

    /** Subtitles from subtitle addons (needs an IMDb id); [] when there's nothing to ask. */
    private suspend fun findSubtitles(): List<SubtitleTrack> {
        val req = sourceRequest(playback.episode) ?: return emptyList()
        val addons = app.sources.subtitleAddons()
        if (addons.isEmpty()) return emptyList()
        return runCatching {
            withContext(Dispatchers.Default) { Subtitles.search(req, addons, app.fetcher, filename = playback.source.filename) }
        }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "subtitles failed", it) }
            .getOrDefault(emptyList())
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
        val exo = ExoPlayer.Builder(this)
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
                    if (nextReady?.second != null && !nextCancelled && prefs.autoplayNext) playNext()
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
                stats { it.seek(now()) }
                preparedStream?.let { app.engine.onPlayerEvent(it, "seek") }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
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
        playerView.subtitleView?.apply {
            setFractionalTextSize(prefs.subtitleSize.fraction)
            setStyle(CaptionStyleCompat(PlayerColors.SUBTITLE_TEXT, PlayerColors.SUBTITLE_BOX, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, PlayerColors.SUBTITLE_EDGE, null))
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

    /** From 80 % in (or 5 min left), finds the next episode's best matching source in the background. */
    private fun maybePrepareNext(exo: ExoPlayer) {
        val next = playback.next ?: return
        if (nextJob != null || !prefs.autoplayNext) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exo.currentPosition
        if (position < duration * 0.8 && duration - position > 5 * 60_000) return
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

    /** Shows the "Up next" card for the last 30 s; counts down 10 s, then plays the next episode. */
    private fun watchForCredits() {
        lifecycleScope.launch {
            while (isActive) {
                val exo = player ?: return@launch
                val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                if (nextCancelled) return@launch
                if (duration != null && duration - exo.currentPosition <= 30_000) {
                    showNextCard()
                    return@launch
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
        nextTitle.text = "Up next · ${next.subtitle ?: next.episode?.label.orEmpty()}"
        nextPlay.text = if (source != null) "Play now" else "Choose a source"
        nextCard.visibility = View.VISIBLE
        if (isTelevision()) nextPlay.requestFocus()
        if (source == null) {
            nextCountdown.text = "No matching source found automatically"
            return
        }
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            var left = 10
            while (left > 0) {
                nextCountdown.text = "Playing in $left…"
                delay(1_000)
                val exo = player ?: return@launch
                // Paused by the user → the countdown waits too.
                if (exo.isPlaying || exo.playbackState == Player.STATE_ENDED) left--
            }
            playNext()
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

    private fun playNext() {
        val (next, source) = nextReady ?: return
        // Never jump to the next episode from the background.
        if (leavingForNext || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        leavingForNext = true
        saveProgress()
        if (source != null) startActivity(forPlayback(this, next)) else openSources(next.episode)
        finish()
    }

    // ---------------------------------------------------------------- remote control

    /** Key whose press we used for seeking: its release is ours too, or PlayerView would open the controls. */
    private var seekKey: Int? = null
    private var lastSeekAt = 0L

    /**
     * TV remote: ←/→ skip 10 s while the controls are hidden (with them shown, the controls take
     * the keys). Holding the key keeps skipping, about three times a second, 30 s at a time after 2 s.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        val isSeekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (isSeekKey && event.action == KeyEvent.ACTION_UP && seekKey == event.keyCode) {
            seekKey = null
            return true
        }
        val overlayUp = nextCard.visibility == View.VISIBLE || resumeChip.visibility == View.VISIBLE || errorPanel.visibility == View.VISIBLE
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
            val step = (if (held > 2_000) 30_000L else 10_000L) * (if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
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

    /** Back to Reel's Sources screen for this title / episode. */
    private fun openSources(episode: EpisodeNumber?) {
        val type = playback.tmdbType ?: return
        val id = playback.tmdbId ?: return
        startActivity(MainActivity.openSources(this, type, id, episode?.season, episode?.episode))
    }

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
        ) exo.prepare()
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

    // ---------------------------------------------------------------- picture (Fit / Fill / Stretch / shapes)

    /** Picks and remembers how the picture fills the screen. */
    private fun setPicture(mode: com.kirtanya.reel.core.playback.PictureMode) {
        app.playbackSettings.update { it.copy(picture = mode) }
        phoneControls?.syncZoom(mode == com.kirtanya.reel.core.playback.PictureMode.FILL)
        applyPicture(mode)
    }

    /**
     * Fit = whole picture with bars; Fill = crop to the screen; Stretch = squeeze to the screen; a
     * forced shape (16:9, 4:3, 21:9) fits that shape instead of the file's (for files that say the
     * wrong one). Cinema mode looks for bars again afterwards (Fill / Stretch leave none).
     */
    private fun applyPicture(mode: com.kirtanya.reel.core.playback.PictureMode = prefs.picture) {
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

    private val accent get() = Tokens.Night.accent.toArgb()
    private val glassColor get() = Tokens.Night.glass.toArgb()
    private val hairline get() = Tokens.Night.hairline.toArgb()

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
        val levels = com.kirtanya.reel.core.playback.Ambient.Level.entries
        val cinema = PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Cinema mode", levels.map { it.label }, { prefs.cinemaGlow.ordinal }) { i ->
            app.playbackSettings.update { it.copy(cinemaGlow = levels[i]) }
            ambient.level = levels[i]
        }
        val modes = com.kirtanya.reel.core.playback.PictureMode.entries
        val picture = PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Picture", modes.map { it.label }, { prefs.picture.ordinal }, asList = true) { i ->
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
                    (if (preparedStream != null || playback.source.kind == SourceKind.DIRECT) listOf("Open in another player" to { openExternal() })
                    else emptyList()) + (REPORT_ROW to { reportFromSettings() })
                },
                hasNext = { nextReady != null },
                onNext = { playNext() },
            ).also {
                it.setToggles(listOf(picture, cinema))
                root.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            }
            buildTvChips(root)
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
                (if (preparedStream != null || playback.source.kind == SourceKind.DIRECT) listOf("Open in another player" to { openExternal() })
                else emptyList()) + (REPORT_ROW to { reportFromSettings() })
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
        phoneControls?.syncZoom(prefs.picture == com.kirtanya.reel.core.playback.PictureMode.FILL)
        root.addView(phoneControls, FrameLayout.LayoutParams(-1, -1))
        // The same small chips as the TV, under the top bar on the right.
        buildTvChips(root, topMarginDp = 64)
        buildErrorPanel(root)
        return root
    }

    /**
     * Every status chip is small and sits at the top right (under the top bar on phones), side by side: Up next, Start over,
     * then the buffering chip (spinner + speed) at the corner.
     */
    private fun buildTvChips(root: FrameLayout, topMarginDp: Int = 20) {
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        root.addView(chips, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(24), dp(topMarginDp), dp(24), 0) })
        fun chipGap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }

        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(4), dp(4))
            background = glass(radius = 999)
            visibility = View.GONE
            nextTitle = text(12f, PlayerColors.FOREGROUND, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(220) }
            nextCountdown = text(12f, Tokens.Night.textSecondary.toArgb()).apply { setPadding(dp(8), 0, dp(8), 0); maxLines = 1 }
            addView(nextTitle)
            addView(nextCountdown)
            nextPlay = smallButton("Play now", primary = true) { playNext() }
            addView(nextPlay)
            addView(smallButton("Cancel") { cancelNext() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        chips.addView(nextCard, chipGap())

        resumeChip = smallButton("") {
            player?.seekTo(0)
            hideResumeChip()
        }.apply { visibility = View.GONE }
        chips.addView(resumeChip, chipGap())

        val statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = glass(radius = 999)
            visibility = View.GONE
            addView(android.widget.ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(PlayerColors.FOREGROUND)
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
        }
        // The chip shows and hides with its text (every caller sets the text's visibility).
        status = object : TextView(this) {
            override fun setVisibility(visibility: Int) {
                super.setVisibility(visibility)
                statusChip.visibility = visibility
            }
        }.apply {
            setTextColor(PlayerColors.FOREGROUND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = font(medium = true)
            maxLines = 1
            maxWidth = dp(260)
        }
        statusChip.addView(status)
        status.visibility = View.GONE
        chips.addView(statusChip, chipGap())
    }

    private fun smallButton(label: String, primary: Boolean = false, onClick: () -> Unit) = button(label, primary, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minHeight = dp(30)
        minimumHeight = dp(30)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(4), dp(12), dp(4))
        if (!isTelevision()) {
            // Phone: a 48 dp touch area around the same 30 dp pill (transparent bands above and below).
            background = android.graphics.drawable.InsetDrawable(background, 0, dp(9), 0, dp(9))
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(13), dp(12), dp(13))
        }
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
                openSources(playback.episode)
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

    private fun text(sizeSp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font(medium)
    }

    /** Pill button: filled primary (Material) or glass; a bright ring when the remote focuses it. */
    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = font(medium = true)
        setTextColor(if (primary) Tokens.Night.onAccent.toArgb() else PlayerColors.FOREGROUND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        stateListAnimator = null
        fun shape(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(if (primary) Tokens.Night.accent.toArgb() else glassColor)
            setStroke(dp(if (focused) 2 else 1), if (focused) PlayerColors.FOREGROUND else if (primary) Color.TRANSPARENT else hairline)
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
        setOnClickListener { onClick() }
    }

    private fun glass(radius: Int) = GradientDrawable().apply {
        setColor(glassColor)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), hairline)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTelevision() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {
        private const val TAG = "ReelPlayer"
        /** Buffering this long while wanting to play saves a "stall" report. */
        private const val STALL_REPORT_MS = 15_000L
        private const val EXTRA_PLAYBACK = "playback"
        private val json = Json { ignoreUnknownKeys = true }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun forPlayback(context: Context, playback: Playback): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_PLAYBACK, json.encodeToString(Playback.serializer(), playback))

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
