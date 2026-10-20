package com.kirtanya.reel.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The player controls set up as on a device, over a stand-in picture, mid-film. Shared by the
 * renders (PhoneControlsRender, TvControlsRender) and the design rules (design/PlayerDesignRules).
 */
internal object PlayerScenes {
    const val TITLE = "Spider-Man: Into the Spider-Verse"

    class Phone(val root: FrameLayout, val controls: PhoneControls, val player: ExoPlayer?) {
        /** Renders only: no real playback, so put the mid-film numbers back after the controls refreshed. */
        fun midFilm() = controls.preview(2_712_000, 7_020_000, 3_300_000, playing = true)
        fun release() = player?.release()
    }

    class Tv(val root: FrameLayout, val controls: TvControls, val player: ExoPlayer) {
        fun midFilm() = controls.preview(2_712_000, 7_020_000, 3_300_000, playing = true)
        fun release() = player.release()
    }

    private fun playerView(activity: ComponentActivity) =
        PlayerView(activity).apply { useController = false; setShutterBackgroundColor(Color.TRANSPARENT); setBackgroundColor(Color.TRANSPARENT) }

    /** Phone landscape. [withPlayer] attaches a (silent) ExoPlayer so the settings panel has its rows. */
    fun phone(panel: Boolean, withPlayer: Boolean = false): Phone {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6E8FB5.toInt(), 0xFF2B3A55.toInt(), 0xFFC98B5E.toInt()))
        val playerView = playerView(activity)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = PhoneControls(activity, playerView, TITLE, { null }, { listOf("Open in another player" to {}) },
            onBack = {}, onPip = {}, nextLabel = { "Next · S1 E4" }, onNext = {})
        root.addView(c, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val exo = if (withPlayer) ExoPlayer.Builder(activity).build().also { c.attach(it) } else null
        val scene = Phone(root, c, exo)
        c.show()
        ShadowLooper.idleMainLooper()
        scene.midFilm()
        if (panel) c.openPanelForPreview()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        scene.midFilm()
        return scene
    }

    /** TV strip over a picture [picture] (top → bottom), in the colour the sampler would pick for it. */
    fun tv(picture: IntArray, color: Int, panel: Boolean = false): Tv {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, picture)
        val playerView = playerView(activity)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = TvControls(playerView, TITLE, { null }, extraSettings = { listOf("Open in another player" to {}) })
        root.addView(c.view, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val exo = ExoPlayer.Builder(activity).build()
        c.attach(exo)
        c.show()
        if (panel) c.openPanel()
        ShadowLooper.idleMainLooper()
        // No video surface here: set the colour the sampler would pick for this picture.
        c.applyColor(color)
        val scene = Tv(root, c, exo)
        scene.midFilm()
        return scene
    }

    val BRIGHT = intArrayOf(0xFFBFD9F2.toInt(), 0xFFF4EBD9.toInt())
    val DARK = intArrayOf(0xFF1B2433.toInt(), 0xFF0B0B10.toInt())
    val MID = intArrayOf(0xFF3C5A7A.toInt(), 0xFF11161F.toInt())
}
