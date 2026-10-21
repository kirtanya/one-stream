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

    /**
     * The extra rows PlayerActivity adds (buildLayout): Change source (off with its note for a file
     * without TMDB info), Open in another player, Report a problem.
     */
    fun extras(file: Boolean): List<PanelExtra> = listOf(
        if (file) PanelExtra(CHANGE_SOURCE_ROW, note = com.kirtanya.reel.core.playback.PlayerSourceChange.NO_SOURCES_NOTE, action = null)
        else PanelExtra(CHANGE_SOURCE_ROW) {},
        PanelExtra("Open in another player") {},
        PanelExtra(REPORT_ROW) {},
    )

    /** Scrolls the open settings panel to its end, where the extra rows are. */
    fun scrollPanelToEnd(root: android.view.View) {
        val row = root.findViewWithTag<android.view.View>(CHANGE_SOURCE_ROW) ?: root.findViewWithTag("$CHANGE_SOURCE_ROW (off)") ?: return
        var p = row.parent
        while (p != null && p !is android.widget.ScrollView) p = p.parent
        val sv = p as? android.widget.ScrollView ?: return
        sv.scrollTo(0, sv.getChildAt(0).height)
        ShadowLooper.idleMainLooper()
    }

    private fun playerView(activity: ComponentActivity) =
        PlayerView(activity).apply { useController = false; setShutterBackgroundColor(Color.TRANSPARENT); setBackgroundColor(Color.TRANSPARENT) }

    /**
     * The phone controls in whatever screen the test's qualifiers give (landscape or portrait, font
     * scale). [withPlayer] attaches a (silent) ExoPlayer so the settings panel has its rows; [next]
     * makes a next episode ready; [scrubAtMs] puts a finger on the seek bar there.
     */
    fun phone(panel: Boolean, withPlayer: Boolean = false, next: Boolean = true, scrubAtMs: Long? = null, file: Boolean = false, panelEnd: Boolean = false): Phone {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6E8FB5.toInt(), 0xFF2B3A55.toInt(), 0xFFC98B5E.toInt()))
        val playerView = playerView(activity)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = PhoneControls(activity, playerView, TITLE, { null }, { extras(file) },
            onBack = {}, onPip = {}, nextLabel = { if (next) "Next · S1 E4" else null }, onNext = {})
        // The rows the activity adds (PlayerActivity.buildLayout), so the panel shows its real length.
        val modes = com.kirtanya.reel.core.playback.PictureMode.entries
        val levels = com.kirtanya.reel.core.playback.Ambient.Level.entries
        c.setToggles(listOf(
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Picture", modes.map { it.label }, { 0 }, asList = true) {},
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_fit, "Cinema mode", levels.map { it.label }, { 0 }) {},
        ))
        root.addView(c, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val exo = if (withPlayer) ExoPlayer.Builder(activity).build().also { c.attach(it) } else null
        val scene = Phone(root, c, exo)
        c.show()
        ShadowLooper.idleMainLooper()
        scene.midFilm()
        if (panel) c.openPanelForPreview()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        if (panel && panelEnd) scrollPanelToEnd(root)
        scene.midFilm()
        if (scrubAtMs != null) {
            c.previewScrub(scrubAtMs)
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
            // The (empty) player's refresh may have cleared the length: put it back, then move the finger.
            scene.midFilm()
            c.previewScrub(scrubAtMs)
        }
        return scene
    }

    /** Where a scrub render puts the finger: an hour in. */
    const val SCRUB_MS = 3_600_000L

    /** TV strip over a picture [picture] (top → bottom); always white with its small shadow. */
    fun tv(picture: IntArray, panel: Boolean = false, file: Boolean = false, panelEnd: Boolean = false): Tv {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, picture)
        val playerView = playerView(activity)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = TvControls(playerView, TITLE, { null }, extraSettings = { extras(file) })
        root.addView(c.view, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val exo = ExoPlayer.Builder(activity).build()
        c.attach(exo)
        c.show()
        if (panel) c.openPanel()
        ShadowLooper.idleMainLooper()
        if (panel && panelEnd) scrollPanelToEnd(root)
        val scene = Tv(root, c, exo)
        scene.midFilm()
        return scene
    }

    val BRIGHT = intArrayOf(0xFFBFD9F2.toInt(), 0xFFF4EBD9.toInt())
    val DARK = intArrayOf(0xFF1B2433.toInt(), 0xFF0B0B10.toInt())
    val MID = intArrayOf(0xFF3C5A7A.toInt(), 0xFF11161F.toInt())
}
