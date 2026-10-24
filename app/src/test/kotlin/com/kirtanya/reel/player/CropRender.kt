package com.kirtanya.reel.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.takahirom.roborazzi.captureRoboImage
import com.kirtanya.reel.core.playback.PictureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Crop bars on a 16:9 file with black bars baked into the picture (a 2.4:1 film letterboxed by the
 * release): the picture modes without a crop (the problem: Fill / Stretch keep the black) and with
 * the bars cropped (13 %), the phone's adjust bar and the TV's page.
 * Record: ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.player.CropRender'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PhoneControlsRender.LAND)
class CropRender {
    // Without a crop: the baked-in bars stay, whatever the mode.
    @Test fun phoneFit() = picture("crop_phone_fit", PictureMode.FIT, 0f)
    @Test fun phoneFill() = picture("crop_phone_fill", PictureMode.FILL, 0f)
    @Test fun phoneStretch() = picture("crop_phone_stretch", PictureMode.STRETCH, 0f)
    @Test @Config(qualifiers = PhoneControlsRender.PORT) fun portraitFit() = picture("crop_phone_portrait_fit", PictureMode.FIT, 0f)
    @Test @Config(qualifiers = TV) fun tvFit() = picture("crop_tv_fit", PictureMode.FIT, 0f)
    @Test @Config(qualifiers = TV) fun tvStretch() = picture("crop_tv_stretch", PictureMode.STRETCH, 0f)

    // Cropped: Fit shows the film at its true shape, Fill / Stretch fill the screen with picture.
    @Test fun phoneFitCropped() = picture("crop_phone_fit_cropped", PictureMode.FIT, CROP)
    @Test fun phoneFillCropped() = picture("crop_phone_fill_cropped", PictureMode.FILL, CROP, caption = true)
    @Test fun phoneStretchCropped() = picture("crop_phone_stretch_cropped", PictureMode.STRETCH, CROP)
    @Test @Config(qualifiers = PhoneControlsRender.PORT) fun portraitFitCropped() = picture("crop_phone_portrait_fit_cropped", PictureMode.FIT, CROP, caption = true)
    @Test @Config(qualifiers = TV) fun tvFitCropped() = picture("crop_tv_fit_cropped", PictureMode.FIT, CROP, caption = true)
    @Test @Config(qualifiers = TV) fun tvStretchCropped() = picture("crop_tv_stretch_cropped", PictureMode.STRETCH, CROP)

    // Adjusting: the phone's bar at the bottom (picture live above it), the TV's page in the panel.
    @Test fun phoneAdjust() = phone("crop_phone_adjust", auto = true, PictureMode.STRETCH)
    @Test @Config(fontScale = 1.3f) fun phoneAdjust130() = phone("crop_phone_adjust_130", auto = true, PictureMode.FILL)
    @Test @Config(qualifiers = PhoneControlsRender.PORT) fun portraitAdjust() = phone("crop_phone_portrait_adjust", auto = false, PictureMode.FIT)
    @Test @Config(qualifiers = PhoneControlsRender.PORT, fontScale = 1.3f) fun portraitAdjust130() = phone("crop_phone_portrait_adjust_130", auto = true, PictureMode.FIT)
    @Test @Config(qualifiers = TV) fun tvAdjust() {
        val scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, cropPercent = CROP, crop = true, video = letterboxVideo(PictureMode.STRETCH, CROP))
        scene.root.captureRoboImage("build/outputs/roborazzi/crop_tv_adjust.png")
        scene.release()
    }

    /** The settings panels with the new row (next to Picture). */
    @Test fun phoneSettings() {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, cropPercent = CROP)
        scrollTo(scene.root, CROP_ROW)
        scene.root.captureRoboImage("build/outputs/roborazzi/crop_phone_settings.png")
        scene.release()
    }
    @Test @Config(qualifiers = TV) fun tvSettings() {
        val scene = PlayerScenes.tv(PlayerScenes.MID, panel = true, cropPercent = CROP)
        scene.root.findViewWithTag<android.view.View>(CROP_ROW)!!.requestFocus()
        scrollTo(scene.root, CROP_ROW)
        scene.root.captureRoboImage("build/outputs/roborazzi/crop_tv_settings.png")
        scene.release()
    }

    /** The layout itself: what shows is exactly the film between the bars, at its shape in Fit. */
    @Test fun croppedFitShowsTheFilmAtItsOwnShape() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val pv = PlayerView(activity).apply { useController = false }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        letterboxVideo(PictureMode.FIT, CROP)(root, pv)
        ShadowLooper.idleMainLooper()
        val frame = pv.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        val shape = frame.width.toFloat() / frame.height
        assertEquals("16:9 less 2 × 13 %", 16f / 9f / 0.74f, shape, 0.02f)
        val stand = frame.getChildAt(1)
        assertEquals(1f / 0.74f, stand.scaleY, 1e-4f)
        assertTrue("the frame clips the cropped rows", frame.clipChildren)
    }

    private fun picture(name: String, mode: PictureMode, crop: Float, caption: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val playerView = PlayerView(activity).apply { useController = false; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        letterboxVideo(mode, crop)(root, playerView)
        if (caption) playerView.subtitleView?.setCues(listOf(Cue.Builder().setText("Captions stay on the picture").build()))
        ShadowLooper.idleMainLooper()
        root.captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun phone(name: String, auto: Boolean, mode: PictureMode) {
        val scene = PlayerScenes.phone(panel = true, withPlayer = true, cropPercent = CROP, crop = true, cropAuto = auto, video = letterboxVideo(mode, CROP))
        scene.root.captureRoboImage("build/outputs/roborazzi/$name.png")
        scene.release()
    }

    private fun scrollTo(root: android.view.View, tag: String) {
        val row = root.findViewWithTag<android.view.View>(tag) ?: return
        var p = row.parent
        while (p != null && p !is android.widget.ScrollView) p = p.parent
        (p as? android.widget.ScrollView)?.scrollTo(0, row.top - root.resources.displayMetrics.density.times(120).toInt())
        ShadowLooper.idleMainLooper()
    }

    companion object {
        const val TV = "w960dp-h540dp-land-television-xhdpi"

        /** Share of the height that is black, per side (2.4:1 inside 16:9 ≈ 12.9 %), and the crop that removes it. */
        const val BAR = 0.129f
        const val CROP = 13f

        /** The stand-in video in the player's content frame, laid out by the player's own PictureLayout. */
        fun letterboxVideo(mode: PictureMode, crop: Float): (FrameLayout, PlayerView) -> Unit = { root, playerView ->
            root.setBackgroundColor(PlayerColors.VIDEO_BACKGROUND)
            val frame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val stand = ImageView(playerView.context).apply { setImageBitmap(letterboxed()); scaleType = ImageView.ScaleType.FIT_XY }
            frame.addView(stand, 1, FrameLayout.LayoutParams(-1, -1))
            PictureLayout.apply(playerView, mode, 16f / 9f, crop, extra = listOf(stand))
        }

        /**
         * A 16:9 frame with black bands top and bottom: sky to sunset, a round sun (so a squeezed picture shows), a dark ground.
         */
        fun letterboxed(w: Int = 640, h: Int = 360): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(0xFF000000.toInt())
            val top = h * BAR
            val bottom = h * (1 - BAR)
            val horizon = top + (bottom - top) * 0.68f
            c.drawRect(0f, top, w.toFloat(), horizon, Paint().apply {
                shader = LinearGradient(0f, top, 0f, horizon, 0xFF3A6EA5.toInt(), 0xFFF2A65A.toInt(), Shader.TileMode.CLAMP)
            })
            c.drawCircle(w * 0.62f, horizon - (bottom - top) * 0.18f, (bottom - top) * 0.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE08A.toInt() })
            c.drawRect(0f, horizon, w.toFloat(), bottom, Paint().apply {
                shader = LinearGradient(0f, horizon, 0f, bottom, 0xFF2E4A2B.toInt(), 0xFF16241A.toInt(), Shader.TileMode.CLAMP)
            })
        }
    }
}
