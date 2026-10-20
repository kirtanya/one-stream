package com.kirtanya.reel.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.media3.ui.PlayerView
import com.github.takahirom.roborazzi.captureRoboImage
import com.kirtanya.reel.core.playback.Ambient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.junit.Assert.assertTrue

/** Cinema mode on a letterboxed film (2.4:1 on a 16:9 TV): the bars glow with the picture's own edges. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class AmbientGlowRender {
    @Test fun barsTakeTheColoursOfTheEdgeNextToThem() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val glow = AmbientGlow(PlayerView(activity)).apply { level = Ambient.Level.STRONG }
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))
        // The "film": warm sky on top, a dark blue ground at the bottom (colours chosen for the test).
        val film = frame(64, 36, top = 0xFFE89A3C.toInt(), bottom = 0xFF1E3A8A.toInt())
        val shown = ImageView(activity).apply { setImageBitmap(frame(640, 360, 0xFFE89A3C.toInt(), 0xFF1E3A8A.toInt())); scaleType = ImageView.ScaleType.FIT_XY }
        val w = 1920; val h = 1080; val vh = 800; val top = (h - vh) / 2
        root.addView(shown, FrameLayout.LayoutParams(w, vh).apply { topMargin = top })
        activity.setContentView(root)
        ShadowLooper.idleMainLooper()
        glow.preview(film, Rect(0, top, w, top + vh))
        ShadowLooper.idleMainLooper()
        root.captureRoboImage("build/outputs/roborazzi/cinema_mode_letterbox.png")

        val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        val above = shot.getPixel(w / 2, top - 4)
        val below = shot.getPixel(w / 2, top + vh + 4)
        val r = { c: Int -> c shr 16 and 0xFF }; val b = { c: Int -> c and 0xFF }
        assertTrue("the bar above is warm like the sky it touches: #${Integer.toHexString(above)}", r(above) > b(above))
        assertTrue("the bar below is blue like the ground it touches: #${Integer.toHexString(below)}", b(below) > r(below))
        assertTrue("black at the screen's edge", shot.getPixel(w / 2, 1) and 0xFFFFFF < 0x101010)
    }

    private fun frame(w: Int, h: Int, top: Int, bottom: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
    }

    /**
     * A real frame (not committed: personal screenshots stay on the Mac). Run with
     * AMBIENT_FRAME=/path/to.png AMBIENT_CROP=x,y,w,h ./gradlew :app:recordRoborazziDebug --tests '*AmbientGlowRender*'
     * → build/outputs/roborazzi/cinema_mode_real_<level>.png (letterboxed at the frame's own shape).
     */
    @Test fun realFrame() {
        val path = System.getenv("AMBIENT_FRAME") ?: return
        val full = android.graphics.BitmapFactory.decodeFile(path) ?: return
        val crop = System.getenv("AMBIENT_CROP")?.split(",")?.map { it.trim().toInt() }
        val img = if (crop != null) Bitmap.createBitmap(full, crop[0], crop[1], crop[2], crop[3]) else full
        for (level in listOf(Ambient.Level.SUBTLE, Ambient.Level.MEDIUM, Ambient.Level.STRONG)) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val root = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            val glow = AmbientGlow(PlayerView(activity)).apply { this.level = level }
            root.addView(glow, FrameLayout.LayoutParams(-1, -1))
            val w = 1920; val h = 1080
            val vh = (w.toFloat() * img.height / img.width).toInt().coerceAtMost(h)
            val top = (h - vh) / 2
            root.addView(ImageView(activity).apply { setImageBitmap(img); scaleType = ImageView.ScaleType.FIT_XY },
                FrameLayout.LayoutParams(w, vh).apply { topMargin = top })
            activity.setContentView(root)
            ShadowLooper.idleMainLooper()
            // What PixelCopy would give the player: the frame at 64×36.
            glow.preview(Bitmap.createScaledBitmap(img, 64, 36, true), Rect(0, top, w, top + vh))
            ShadowLooper.idleMainLooper()
            root.captureRoboImage("build/outputs/roborazzi/cinema_mode_real_${level.name.lowercase()}.png")
        }
    }
}
