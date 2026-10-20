package com.kirtanya.reel.player

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.core.playback.Ambient

/**
 * Cinema mode: light seems to spill out of the picture into the black bars, like YouTube's ambient
 * mode. A tiny copy of the frame (PixelCopy, 64×36) is blurred ([Ambient.glow]); each bar shows the
 * blurred strip of the picture next to it, mirrored outward so it starts in the boundary's own colours
 * ([drawBars]); then every bar fades from [Ambient.Level.edge] brightness at the video's edge to black
 * at the screen's edge ([Ambient.falloff]). New copies crossfade in. Runs only while playing with bars on screen; a video
 * that fills the screen costs nothing. DRM pictures can't be copied: the bars stay black.
 */
internal class AmbientGlow(private val playerView: PlayerView) : View(playerView.context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var copying = false
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var mix = 1f // 0 = previous, 1 = current
    private var mixAnim: ValueAnimator? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint()
    private val video = Rect()
    private val dst = RectF()
    private val srcRect = Rect()

    /** How strong the glow is (Off = plain black bars). */
    var level = Ambient.Level.SUBTLE
        set(v) {
            field = v
            invalidate()
            kick()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun attach(p: Player) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = kick()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = kick()
        })
    }

    /** The picture's size or place changed (Picture setting): look again for bars. */
    fun refresh() {
        invalidate()
        kick()
    }

    fun release() {
        main.removeCallbacks(tick)
        player = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (sample()) main.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun kick() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Renders only: a fixed frame and video position, no player. */
    private var previewVideo: Rect? = null
    internal fun preview(frame: Bitmap, videoAt: Rect) {
        previewVideo = Rect(videoAt)
        val px = IntArray(frame.width * frame.height)
        frame.getPixels(px, 0, frame.width, 0, 0, frame.width, frame.height)
        val glow = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        glow.setPixels(Ambient.glow(px, frame.width, frame.height, brightness = 1f), 0, frame.width, 0, 0, frame.width, frame.height)
        current = glow
        previous = null
        mix = 1f
        invalidate()
    }

    /** Where the video is, in this view's coordinates; false when there are no bars to fill. */
    private fun videoRect(): Boolean {
        previewVideo?.let { video.set(it); return true }
        val sv = playerView.videoSurfaceView as? SurfaceView ?: return false
        if (!Ambient.hasBars(sv.width, sv.height, width, height)) return false
        val a = IntArray(2).also { sv.getLocationInWindow(it) }
        val b = IntArray(2).also { getLocationInWindow(it) }
        video.set(a[0] - b[0], a[1] - b[1], a[0] - b[0] + sv.width, a[1] - b[1] + sv.height)
        return true
    }

    /** One copy if it's worth it; false = stop until the next [kick] (paused, no bars, off). */
    private fun sample(): Boolean {
        val p = player ?: return false
        if (level == Ambient.Level.OFF || !videoRect()) { invalidate(); return false }
        val sv = playerView.videoSurfaceView as SurfaceView
        if (sv.holder.surface?.isValid != true) return true
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
        return p.isPlaying // paused: this copy is the last until play resumes
    }

    private fun show(frame: Bitmap) {
        val px = IntArray(W * H)
        frame.getPixels(px, 0, W, 0, 0, W, H)
        frame.setPixels(Ambient.glow(px, W, H, brightness = 1f), 0, W, 0, 0, W, H)
        mixAnim?.cancel()
        previous?.takeIf { it !== current }?.recycle()
        previous = current
        current = frame
        mix = if (previous == null) 1f else 0f
        mixAnim = ValueAnimator.ofFloat(mix, 1f).apply {
            duration = FADE_MS
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cur = current ?: return
        if (level == Ambient.Level.OFF || !videoRect()) return
        val prev = previous?.takeIf { mix < 1f }
        if (prev != null) drawBars(canvas, prev, 255)
        drawBars(canvas, cur, if (prev == null) 255 else (255 * mix).toInt())
        // Each bar: black over the glow, from (1 − edge) at the video to fully black at the screen edge.
        val stops = Ambient.falloff(level)
        fun shadeBar(left: Int, top: Int, right: Int, bottom: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            if (right <= left || bottom <= top) return
            shade.shader = LinearGradient(x0, y0, x1, y1, stops.colors, stops.positions, Shader.TileMode.CLAMP)
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), shade)
        }
        shadeBar(0, 0, width, video.top, 0f, video.top.toFloat(), 0f, 0f) // above
        shadeBar(0, video.bottom, width, height, 0f, video.bottom.toFloat(), 0f, height.toFloat()) // below
        shadeBar(0, video.top, video.left, video.bottom, video.left.toFloat(), 0f, 0f, 0f) // left
        shadeBar(video.right, video.top, width, video.bottom, video.right.toFloat(), 0f, width.toFloat(), 0f) // right
    }

    /**
     * Fills each bar from the strip of the picture next to it ([Ambient.EDGE_SHARE] of the frame:
     * bottom rows for the bar below, top rows above, side columns at the sides), mirrored outward so
     * the row / column touching the video is the video's own edge — the glow starts in the colours
     * right at the boundary and carries them out.
     */
    private fun drawBars(canvas: Canvas, frame: Bitmap, alpha: Int) {
        bitmapPaint.alpha = alpha
        val fw = frame.width
        val fh = frame.height
        val rows = (fh * Ambient.EDGE_SHARE).toInt().coerceAtLeast(1)
        val cols = (fw * Ambient.EDGE_SHARE).toInt().coerceAtLeast(1)
        fun mirrored(src: Rect, l: Float, t: Float, r: Float, b: Float, sx: Float, sy: Float, px: Float, py: Float) {
            canvas.save()
            canvas.scale(sx, sy, px, py)
            dst.set(l, t, r, b)
            canvas.drawBitmap(frame, src, dst, bitmapPaint)
            canvas.restore()
        }
        val top = video.top.toFloat()
        val bottom = video.bottom.toFloat()
        val left = video.left.toFloat()
        val right = video.right.toFloat()
        if (video.top > 0) { // above: rows 0…25 %, row 0 against the video
            srcRect.set(0, 0, fw, rows)
            mirrored(srcRect, left, top, right, top + video.top, 1f, -1f, 0f, top)
        }
        if (video.bottom < height) { // below: rows 75…100 %, the last row against the video
            srcRect.set(0, fh - rows, fw, fh)
            mirrored(srcRect, left, bottom - (height - video.bottom), right, bottom, 1f, -1f, 0f, bottom)
        }
        if (video.left > 0) { // left: columns 0…25 %
            srcRect.set(0, 0, cols, fh)
            mirrored(srcRect, left, top, left + video.left, bottom, -1f, 1f, left, 0f)
        }
        if (video.right < width) { // right: columns 75…100 %
            srcRect.set(fw - cols, 0, fw, fh)
            mirrored(srcRect, right - (width - video.right), top, right, bottom, -1f, 1f, right, 0f)
        }
    }

    private companion object {
        const val W = 64
        const val H = 36
        const val INTERVAL_MS = 700L
        const val FADE_MS = 600L
    }
}
