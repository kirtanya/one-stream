package com.kirtanya.reel.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.core.playback.Ambient

/**
 * Cinema mode: fills the black bars around the video with its own colours. A tiny copy of the frame
 * (PixelCopy, 48×27) is blurred and dimmed ([Ambient.glow]) and stretched over the whole screen
 * behind the video; the video covers the middle, so only the bars show it. New copies crossfade in,
 * so the glow follows the film without flicker. Runs only while playing with bars on screen — a
 * video that fills the screen costs nothing. DRM pictures can't be copied: the bars stay black.
 */
internal class AmbientGlow(private val playerView: PlayerView) {
    private val context = playerView.context
    private val main = Handler(Looper.getMainLooper())
    private val back = image()
    private val front = image().apply { alpha = 0f }
    private var player: Player? = null
    private var copying = false

    /** Added under the PlayerView (which must have no background of its own). */
    val view = FrameLayout(context).apply {
        addView(back, FrameLayout.LayoutParams(-1, -1))
        addView(front, FrameLayout.LayoutParams(-1, -1))
        alpha = 0f
    }

    /** Off: the bars are plain black. */
    var enabled = true
        set(v) {
            field = v
            if (!v) fade(false)
            kick()
        }

    fun attach(p: Player) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = kick()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = kick()
        })
    }

    fun release() {
        main.removeCallbacks(tick)
        player = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!sample()) return
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    /** (Re)start sampling now: playing, size or setting changed. */
    private fun kick() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** One copy if it's worth it; false = stop until the next [kick] (paused, no bars, off). */
    private fun sample(): Boolean {
        val p = player ?: return false
        val sv = playerView.videoSurfaceView as? SurfaceView
        val bars = sv != null && Ambient.hasBars(sv.width, sv.height, playerView.width, playerView.height)
        if (!enabled || !bars) { fade(false); return false }
        if (sv == null || sv.holder.surface?.isValid != true) return true
        if (!copying) {
            copying = true
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(sv, bmp, { result ->
                    copying = false
                    if (result == PixelCopy.SUCCESS) show(bmp) else bmp.recycle()
                }, main)
            } catch (_: IllegalArgumentException) {
                copying = false
                bmp.recycle()
            }
        }
        // Paused: this copy is the last until play resumes.
        return p.isPlaying
    }

    private fun show(frame: Bitmap) {
        val px = IntArray(W * H)
        frame.getPixels(px, 0, W, 0, 0, W, H)
        frame.setPixels(Ambient.glow(px, W, H), 0, W, 0, 0, W, H)
        // New glow fades in over the old, then becomes the old.
        front.animate().cancel()
        front.setImageBitmap(frame)
        front.alpha = 0f
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            (back.tag as? Bitmap)?.recycle()
            back.setImageBitmap(frame)
            back.tag = frame
            front.alpha = 0f
        }.start()
        fade(true)
    }

    private fun fade(on: Boolean) {
        val to = if (on) 1f else 0f
        if (view.alpha != to) view.animate().alpha(to).setDuration(FADE_MS).start()
    }

    private fun image() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY // the whole frame over the whole screen: each bar takes the edge it touches
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private companion object {
        const val W = 48
        const val H = 27
        const val INTERVAL_MS = 700L
        const val FADE_MS = 600L
    }
}
