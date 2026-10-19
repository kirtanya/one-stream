package com.kirtanya.reel.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.EngineException
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a direct URL, or a torrent (magnet / info-hash) through the local stream server.
 * Shows "Finding peers… / Buffering" stats until video is rolling. See PLAN.md §1.5.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = ReelApp.get(this)
    private var player: ExoPlayer? = null
    private var hash: String? = null
    private var streamUrl: String? = null
    private var statsJob: Job? = null

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(buildLayout())
        start()
    }

    private fun start() {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val directUrl = intent.getStringExtra(EXTRA_URL)
        if (directUrl != null) {
            play(directUrl, title)
            return
        }
        lifecycleScope.launch {
            try {
                showStatus("Finding peers…")
                val engine = app.engine
                engine.start()
                val h = intent.getStringExtra(EXTRA_HASH)
                    ?: engine.addMagnet(intent.getStringExtra(EXTRA_MAGNET) ?: error("Nothing to play"), TorrentMode.STREAM)
                hash = h
                startStats(h)
                val fileIdx = intent.getIntExtra(EXTRA_FILE_INDEX, -1).takeIf { it >= 0 }
                val info = engine.prepareStream(h, fileIdx, intent.getStringExtra(EXTRA_FILENAME))
                play(app.streamServer.urlFor(info), title.ifBlank { info.file.name })
            } catch (e: EngineException) {
                showError(e.message ?: "Couldn't start this stream")
            } catch (t: Throwable) {
                showError("Couldn't start this stream: ${t.message}")
            }
        }
    }

    private fun play(url: String, title: String) {
        streamUrl = url
        val loadControl = DefaultLoadControl.Builder()
            // Torrent streams start slower than HTTP; keep a healthy buffer once rolling.
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .build()
        // Some direct sources need headers (Referer, User-Agent…) on every request.
        @Suppress("UNCHECKED_CAST")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>).orEmpty()
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
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status.visibility = View.GONE
                if (state == Player.STATE_BUFFERING && hash != null) status.visibility = View.VISIBLE
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
        })
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).build(),
        ).build())
        exo.prepare()
        exo.playWhenReady = true
        playerView.requestFocus()
    }

    private fun startStats(h: String) {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val s = app.engine.snapshot(h)
                if (s != null && status.visibility == View.VISIBLE) {
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

    private fun showError(text: String) {
        status.visibility = View.GONE
        errorText.text = text
        errorPanel.visibility = View.VISIBLE
        errorPanel.getChildAt(1)?.visibility = if (streamUrl != null) View.VISIBLE else View.GONE
        errorPanel.getChildAt(if (streamUrl != null) 1 else 2)?.requestFocus()
    }

    /** Hands the same localhost URL to VLC / MX / Just Player. Our server keeps serving. */
    private fun openExternal() {
        val url = streamUrl ?: return
        val mime = hash?.let { app.engine.streamInfo(it)?.mimeType } ?: "video/*"
        externalHandoff = true
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime), "Open with"))
    }

    private var externalHandoff = false

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        player?.release()
        player = null
        if (!externalHandoff) hash?.let { app.engine.stopStream(it) }
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(0x99000000.toInt())
        }
        // Top-left, so it never covers the seek bar or controls.
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(24), dp(32), dp(24), 0)
        })
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xE6000000.toInt())
            errorText = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setPadding(dp(48), 0, dp(48), dp(24))
            }
            addView(errorText)
            addView(Button(context).apply { text = "Open in another player"; setOnClickListener { openExternal() } })
            addView(Button(context).apply { text = "Back"; setOnClickListener { finish() } })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTelevision() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {
        private const val EXTRA_MAGNET = "magnet"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_FILE_INDEX = "fileIndex"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_HEADERS = "headers"

        fun forMagnet(context: Context, magnet: String, title: String? = null, fileIndex: Int? = null, filename: String? = null) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_MAGNET, magnet)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FILE_INDEX, fileIndex ?: -1)
                .putExtra(EXTRA_FILENAME, filename)

        fun forTorrent(context: Context, infoHash: String, title: String? = null, fileIndex: Int? = null) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_HASH, infoHash)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FILE_INDEX, fileIndex ?: -1)

        fun forUrl(context: Context, url: String, title: String? = null, headers: Map<String, String> = emptyMap()) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADERS, HashMap(headers))
    }
}
