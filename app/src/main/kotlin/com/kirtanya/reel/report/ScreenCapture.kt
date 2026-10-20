package com.kirtanya.reel.report

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * The screen for a problem report: the activity's window, with the player's video under it.
 * A SurfaceView is a separate layer the window only leaves a transparent hole for, so a window copy
 * alone shows a black film; both are copied (PixelCopy) and stacked. Everything is scaled down to
 * at most [MAX_WIDTH] px wide while copying (PixelCopy scales into the bitmap it is given).
 */
internal object ScreenCapture {
    const val MAX_WIDTH = 1280
    private const val TIMEOUT_MS = 2_500L

    /** The copied layers; [video] sits at ([videoX], [videoY]) in [window]'s (scaled) pixels. */
    class Shot(val window: Bitmap?, val video: Bitmap?, val videoX: Int, val videoY: Int) {
        fun recycle() { window?.recycle(); video?.recycle() }
    }

    /**
     * Starts both copies (main thread). Completes with null when nothing could be copied (no window
     * yet, DRM video, a surface that's gone); never fails, and gives up after [TIMEOUT_MS].
     */
    fun start(activity: Activity, video: SurfaceView?): CompletableFuture<Shot?> {
        val out = CompletableFuture<Shot?>()
        val decor = activity.window?.peekDecorView()
        if (decor == null || decor.width == 0 || decor.height == 0) return out.apply { complete(null) }
        val scale = minOf(1f, MAX_WIDTH.toFloat() / decor.width)
        fun scaled(v: Int) = (v * scale).toInt().coerceAtLeast(1)
        val main = Handler(Looper.getMainLooper())
        val window = Bitmap.createBitmap(scaled(decor.width), scaled(decor.height), Bitmap.Config.ARGB_8888)
        val sv = video?.takeIf { it.width > 0 && it.height > 0 && it.isShown && it.holder.surface?.isValid == true }
        val videoBmp = sv?.let { Bitmap.createBitmap(scaled(it.width), scaled(it.height), Bitmap.Config.ARGB_8888) }
        val loc = IntArray(2).also { sv?.getLocationInWindow(it) }
        var windowOk = false
        var videoOk = false
        val left = AtomicInteger(if (videoBmp != null) 2 else 1)
        fun finish(force: Boolean = false) {
            if (out.isDone || (!force && left.get() > 0)) return
            // Not recycled here: a copy still running after the timeout may write into its bitmap.
            if (!windowOk && !videoOk) out.complete(null)
            else out.complete(Shot(window.takeIf { windowOk }, videoBmp?.takeIf { videoOk }, scaled(loc[0]), scaled(loc[1])))
        }
        try {
            PixelCopy.request(activity.window, window, { r -> windowOk = r == PixelCopy.SUCCESS; left.decrementAndGet(); finish() }, main)
        } catch (_: IllegalArgumentException) {
            left.decrementAndGet()
        }
        if (sv != null && videoBmp != null) {
            try {
                PixelCopy.request(sv, videoBmp, { r -> videoOk = r == PixelCopy.SUCCESS; left.decrementAndGet(); finish() }, main)
            } catch (_: IllegalArgumentException) {
                left.decrementAndGet()
            }
        }
        finish()
        // A copy that never answers (surface torn down mid-copy) must not hold the report up.
        main.postDelayed({ finish(force = true) }, TIMEOUT_MS)
        return out
    }

    /** Stacks the layers (video under the window, black behind both) and writes a PNG. */
    fun writePng(shot: Shot, file: File): Boolean {
        val base = shot.window ?: shot.video ?: return false
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out)
            c.drawColor(Color.BLACK)
            if (shot.window != null) {
                shot.video?.let { c.drawBitmap(it, shot.videoX.toFloat(), shot.videoY.toFloat(), null) }
                c.drawBitmap(shot.window, 0f, 0f, null)
            } else {
                c.drawBitmap(base, 0f, 0f, null)
            }
            return file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            out.recycle()
        }
    }
}
