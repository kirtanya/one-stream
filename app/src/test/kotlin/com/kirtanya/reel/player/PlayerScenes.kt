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

    class Phone(val root: FrameLayout, val controls: PhoneControls, val player: ExoPlayer?, val overlays: PlayerOverlays) {
        /** Renders only: no real playback, so put the mid-film numbers back after the controls refreshed. */
        fun midFilm() = controls.preview(2_712_000, 7_020_000, 3_300_000, playing = true)
        fun release() = player?.release()
    }

    class Tv(val root: FrameLayout, val controls: TvControls, val player: ExoPlayer, val overlays: PlayerOverlays) {
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

    /**
     * The section rows PlayerActivity adds (setPanelRows): subtitle delay + Search subtitles again,
     * night mode + voice boost + audio delay, sleep timer.
     */
    fun rows(): Triple<List<PanelRow>, List<PanelRow>, List<PanelRow>> {
        val onOff = listOf("Off", "On")
        val subtitle = listOf<PanelRow>(
            PanelSlider(com.kirtanya.reel.R.drawable.ic_pl_sync, "Subtitle delay", com.kirtanya.reel.core.player.Delay.SUBTITLES, { 0 }) {},
            PanelExtra("Search subtitles again", icon = com.kirtanya.reel.R.drawable.ic_pl_search) {},
        )
        val audio = listOf<PanelRow>(
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_night, "Night mode", onOff, { 1 }) {},
            PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_voice, "Voice boost", onOff, { 0 }) {},
            PanelSlider(com.kirtanya.reel.R.drawable.ic_pl_sync, "Audio delay", com.kirtanya.reel.core.player.Delay.AUDIO, { 150 }) {},
        )
        val sleeps = com.kirtanya.reel.core.player.SleepChoice.entries
        val playback = listOf<PanelRow>(PanelToggle(com.kirtanya.reel.R.drawable.ic_pl_timer, "Sleep timer", sleeps.map { it.label }, { 0 }, asList = true) {})
        return Triple(subtitle, audio, playback)
    }

    /**
     * The overlay states the renders show: Skip chip, Still watching?, the sleep chip, Up next at the credits, the
     * debrid Not ready card and the chip after switching away from a placeholder.
     */
    enum class Overlay { SKIP, SKIP_OVER_BAND, STILL_WATCHING, SLEEP, UP_NEXT_CREDITS, NOT_READY, PLACEHOLDER_SWITCH }

    /** Overlays that hold the screen with the controls hidden (as in the player). */
    private val CONTROLS_HIDDEN = setOf(Overlay.SKIP, Overlay.STILL_WATCHING, Overlay.UP_NEXT_CREDITS, Overlay.NOT_READY, Overlay.PLACEHOLDER_SWITCH)

    private fun showOverlay(o: PlayerOverlays, which: Overlay?, tv: Boolean) {
        when (which) {
            null -> Unit
            Overlay.SKIP, Overlay.SKIP_OVER_BAND -> {
                o.skipChip.text = "Skip intro"
                o.skipChip.visibility = android.view.View.VISIBLE
                if (tv) o.skipChip.requestFocus()
            }
            Overlay.STILL_WATCHING -> {
                o.stillCard.visibility = android.view.View.VISIBLE
                if (tv) o.stillContinue.requestFocus()
            }
            Overlay.SLEEP -> {
                o.sleepChip.text = "Sleep in 0:42"
                o.sleepChip.visibility = android.view.View.VISIBLE
            }
            Overlay.UP_NEXT_CREDITS -> {
                o.nextTitle.text = "Up next · S1 E4 · The Kingsroad"
                o.nextCountdown.text = "Playing in 10…"
                o.nextPlay.text = "Skip credits"
                o.nextCard.visibility = android.view.View.VISIBLE
                if (tv) o.nextPlay.requestFocus()
            }
            Overlay.NOT_READY -> {
                o.notReadyText.text = com.kirtanya.reel.core.player.PlaceholderCheck.notReadyText("Debridio")
                o.notReady.visibility = android.view.View.VISIBLE
                if (tv) o.notReadyRetry.requestFocus()
            }
            Overlay.PLACEHOLDER_SWITCH -> {
                val instead = com.kirtanya.reel.core.sources.Source(
                    com.kirtanya.reel.core.sources.SourceKind.DIRECT, "x", "Comet | RD", com.kirtanya.reel.core.sources.Quality(resolution = 1080),
                )
                o.noticeChip.text = com.kirtanya.reel.core.player.PlaceholderCheck.switchNote("Debridio", instead)
                o.noticeChip.visibility = android.view.View.VISIBLE
                o.status.text = "Loading…"
                o.status.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** Opens the slider of the panel row tagged [label] ("Audio delay", "Subtitle delay"). */
    fun openSlider(root: android.view.View, label: String) {
        root.findViewWithTag<android.view.View>(label)?.performClick()
        ShadowLooper.idleMainLooper()
    }

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
    fun phone(
        panel: Boolean, withPlayer: Boolean = false, next: Boolean = true, scrubAtMs: Long? = null, file: Boolean = false, panelEnd: Boolean = false,
        overlay: Overlay? = null, slider: String? = null,
    ): Phone {
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
        val (subRows, audioRows, playRows) = rows()
        c.setRows(subRows, audioRows, playRows) {}
        root.addView(c, FrameLayout.LayoutParams(-1, -1))
        val o = PlayerOverlays(activity, { null }, tv = false)
        o.addTo(root)
        activity.setContentView(root)
        val exo = if (withPlayer) ExoPlayer.Builder(activity).build().also { c.attach(it) } else null
        val scene = Phone(root, c, exo, o)
        if (overlay !in CONTROLS_HIDDEN) c.show()
        showOverlay(o, overlay, tv = false)
        ShadowLooper.idleMainLooper()
        scene.midFilm()
        if (panel) c.openPanelForPreview()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        if (panel && panelEnd) scrollPanelToEnd(root)
        if (panel && slider != null) openSlider(root, slider)
        if (overlay == Overlay.SKIP_OVER_BAND) {
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
            o.placeSkipChip(c.coveredBottomPx())
            ShadowLooper.idleMainLooper()
        }
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
    fun tv(picture: IntArray, panel: Boolean = false, file: Boolean = false, panelEnd: Boolean = false, overlay: Overlay? = null, slider: String? = null, strip: Boolean = false): Tv {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, picture)
        val playerView = playerView(activity)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val c = TvControls(playerView, TITLE, { null }, extraSettings = { extras(file) })
        val (subRows, audioRows, playRows) = rows()
        c.setRows(subRows, audioRows, playRows) {}
        root.addView(c.view, FrameLayout.LayoutParams(-1, -1))
        val o = PlayerOverlays(activity, { null }, tv = true)
        o.addTo(root)
        activity.setContentView(root)
        val exo = ExoPlayer.Builder(activity).build()
        c.attach(exo)
        // An overlay holds the remote with the strip hidden (as in the player); the sleep chip shows over the strip.
        // [strip]: the design rules need other controls on screen to move the focus to.
        if (overlay == null || overlay == Overlay.SLEEP) c.show() else if (strip) c.show(focus = null)
        showOverlay(o, overlay, tv = true)
        if (panel) c.openPanel()
        ShadowLooper.idleMainLooper()
        if (panel && panelEnd) scrollPanelToEnd(root)
        if (panel && slider != null) openSlider(root, slider)
        val scene = Tv(root, c, exo, o)
        scene.midFilm()
        return scene
    }

    val BRIGHT = intArrayOf(0xFFBFD9F2.toInt(), 0xFFF4EBD9.toInt())
    val DARK = intArrayOf(0xFF1B2433.toInt(), 0xFF0B0B10.toInt())
    val MID = intArrayOf(0xFF3C5A7A.toInt(), 0xFF11161F.toInt())
}
