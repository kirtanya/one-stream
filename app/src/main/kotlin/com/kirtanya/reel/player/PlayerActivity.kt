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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.sources.rankPrefs
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Plays one [Playback] (a torrent through the local stream server, or a direct URL) and
 * remembers where you got to. Resumes, loads subtitles, and rolls on to the next episode.
 * See PLAN.md §1.5.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = ReelApp.get(this)
    private val prefs get() = app.playbackSettings.state.value
    private lateinit var playback: Playback

    private var player: ExoPlayer? = null
    private var hash: String? = null
    private var streamUrl: String? = null
    private var statsJob: Job? = null
    private var progressJob: Job? = null
    private var nextJob: Job? = null
    private var countdownJob: Job? = null
    private var flashJob: Job? = null
    /** Next episode + the source chosen for it (null source = none found), once looked up. */
    private var nextReady: Pair<Playback, Source?>? = null
    private var nextCancelled = false
    private var leavingForNext = false
    private var externalHandoff = false

    private lateinit var playerView: PlayerView
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
        setContentView(buildLayout())
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
                    SourceKind.DIRECT -> source.url ?: error("No link to play")
                    SourceKind.TORRENT -> prepareTorrent(source)
                    SourceKind.EXTERNAL -> error("This source opens in another app")
                }
                // Torrents spend seconds finding peers, so subtitles are usually ready by now;
                // don't hold a direct link up for long.
                val tracks = withTimeoutOrNull(if (source.kind == SourceKind.TORRENT) 1_500L else 2_500L) { subtitles.await() }
                if (tracks == null) subtitles.cancel()
                play(url, (source.subtitles + tracks.orEmpty()).distinctBy { it.url }, resumeAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                showError(e.message ?: "Couldn't start this stream")
            } catch (t: Throwable) {
                showError("Couldn't start this: ${t.message}")
            }
        }
    }

    private suspend fun prepareTorrent(source: Source): String {
        showStatus("Finding peers…")
        val engine = app.engine
        engine.start()
        val magnet = source.magnetUri() ?: error("No magnet for this torrent")
        val h = engine.addMagnet(magnet, TorrentMode.STREAM)
        hash = h
        startStats(h)
        val info = engine.prepareStream(h, source.fileIdx, source.filename)
        return app.streamServer.urlFor(info)
    }

    /** Subtitles from subtitle addons (needs an IMDb id); [] when there's nothing to ask. */
    private suspend fun findSubtitles(): List<SubtitleTrack> {
        val req = sourceRequest(playback.episode) ?: return emptyList()
        val addons = app.sources.subtitleAddons()
        if (addons.isEmpty()) return emptyList()
        return runCatching { Subtitles.search(req, addons, app.fetcher, filename = playback.source.filename) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "subtitles failed", it) }
            .getOrDefault(emptyList())
    }

    private fun play(url: String, subtitles: List<SubtitleTrack>, resumeAtMs: Long) {
        streamUrl = url
        val loadControl = DefaultLoadControl.Builder()
            // Torrent streams start slower than HTTP; keep a healthy buffer once rolling.
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .build()
        // Some direct sources need headers (Referer, User-Agent…) on every request.
        val headers = playback.source.headers
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
            .apply { headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { setUserAgent(it.value) } }
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player = exo
        playerView.player = exo
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
        if (resumeAtMs > 0) showResumeChip(resumeAtMs)
        startProgressSaving()
        Log.i(TAG, "playing ${playback.key} from ${formatTime(resumeAtMs)} with ${subtitles.size} subtitle tracks")
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> status.visibility = View.GONE
                Player.STATE_BUFFERING -> if (hash != null) status.visibility = View.VISIBLE
                Player.STATE_ENDED -> {
                    saveProgress(ended = true)
                    if (nextReady?.second != null && !nextCancelled && prefs.autoplayNext) playNext()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Phones turn sideways for wide video; TVs are always landscape.
            if (videoSize.width > videoSize.height && !isTelevision()) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            showError("This device can't play this file (${error.errorCodeName}).")
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
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(lang)
            .setSelectUndeterminedTextLanguage(false)
            .setIgnoredTextSelectionFlags(if (lang == null) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
        playerView.subtitleView?.apply {
            setFractionalTextSize(prefs.subtitleSize.fraction)
            setStyle(CaptionStyleCompat(Color.WHITE, 0x99000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
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
        return playback.copy(
            key = Playback.keyFor("tv", id, next),
            subtitle = listOfNotNull(next.label, ep?.name).joinToString(" · "),
            episode = next,
            stillPath = ep?.stillPath,
            next = details?.let { nextEpisode(it.seasons, next) },
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
            for (s in 10 downTo 1) {
                nextCountdown.text = "Playing in $s…"
                delay(1_000)
            }
            playNext()
        }
    }

    private fun cancelNext() {
        nextCancelled = true
        countdownJob?.cancel()
        nextCard.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun playNext() {
        val (next, source) = nextReady ?: return
        if (leavingForNext) return
        leavingForNext = true
        saveProgress()
        if (source != null) startActivity(forPlayback(this, next)) else openSources(next.episode)
        finish()
    }

    // ---------------------------------------------------------------- remote control

    /** TV remote: ←/→ skip 10 s while the controls are hidden (with them shown, the controls take the keys). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        val overlayUp = nextCard.visibility == View.VISIBLE || resumeChip.visibility == View.VISIBLE || errorPanel.visibility == View.VISIBLE
        if (exo != null && event.action == KeyEvent.ACTION_DOWN && !overlayUp && !playerView.isControllerFullyVisible) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10_000L
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10_000L
                else -> 0L
            }
            if (step != 0L) {
                val end = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                val target = (exo.currentPosition + step).coerceIn(0, end)
                exo.seekTo(target)
                flashStatus((if (step > 0) "+10 s · " else "−10 s · ") + formatTime(target))
                return true
            }
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
                        else -> "Buffering · ↓ ${formatBytes(s.downBps.toLong())}/s · ${s.peers} peers"
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

    private fun showError(text: String) {
        status.visibility = View.GONE
        nextCard.visibility = View.GONE
        countdownJob?.cancel()
        errorText.text = text
        errorPanel.visibility = View.VISIBLE
        errorExternal.visibility = if (streamUrl != null) View.VISIBLE else View.GONE
        errorSources.visibility = if (playback.tmdbId != null) View.VISIBLE else View.GONE
        (if (errorSources.visibility == View.VISIBLE) errorSources else errorPanel.getChildAt(errorPanel.childCount - 1)).requestFocus()
    }

    /** Hands the same URL to VLC / MX / Just Player. Our stream server keeps serving. */
    private fun openExternal() {
        val url = streamUrl ?: return
        val mime = hash?.let { app.engine.streamInfo(it)?.mimeType } ?: "video/*"
        externalHandoff = true
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime), "Open with"))
    }

    /** Back to Reel's Sources screen for this title / episode. */
    private fun openSources(episode: EpisodeNumber?) {
        val type = playback.tmdbType ?: return
        val id = playback.tmdbId ?: return
        startActivity(MainActivity.openSources(this, type, id, episode?.season, episode?.episode))
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        progressJob?.cancel()
        player?.removeListener(listener)
        player?.release()
        player = null
        if (!externalHandoff) hash?.let { app.engine.stopStream(it) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun sourceRequest(episode: EpisodeNumber?): SourceRequest? {
        val id = playback.tmdbId ?: return null
        val type = if (playback.tmdbType == "tv") MediaType.TV else MediaType.MOVIE
        return SourceRequest(id, playback.imdbId, type, playback.title, playback.year, episode?.season, episode?.episode)
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        status = text(18f, Color.WHITE).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(0x99000000.toInt())
        }
        // Top-left, so it never covers the seek bar or controls.
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(32), dp(24), 0) })

        resumeChip = button("") {
            player?.seekTo(0)
            hideResumeChip()
        }.apply { visibility = View.GONE }
        root.addView(resumeChip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(24), dp(32), dp(32), 0) })

        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(0xE617191E.toInt())
            visibility = View.GONE
            nextTitle = text(18f, Color.WHITE)
            nextCountdown = text(14f, 0xFF9AA0AB.toInt())
            addView(nextTitle)
            addView(nextCountdown)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                nextPlay = button("Play now") { playNext() }
                addView(nextPlay)
                addView(button("Cancel") { cancelNext() })
            })
        }
        root.addView(nextCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(96)) })

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xE6000000.toInt())
            errorText = text(20f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(48), 0, dp(48), dp(24))
            }
            addView(errorText)
            errorSources = button("Other sources") {
                openSources(playback.episode)
                finish()
            }
            addView(errorSources)
            errorExternal = button("Open in another player") { openExternal() }
            addView(errorExternal)
            addView(button("Back") { finish() })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun text(sizeSp: Float, color: Int) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTelevision() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {
        private const val TAG = "ReelPlayer"
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
