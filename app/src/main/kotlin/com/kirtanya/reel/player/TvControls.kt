package com.kirtanya.reel.player

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import java.util.Locale
import kotlin.math.pow

/**
 * TV player controls: one strip along the bottom, nothing in the middle of the picture and no
 * dimming. Transport on the left, a short seek bar on the right, Settings opens a panel above the
 * video. The strip has no background of its own, so its colour follows the picture behind it: the
 * pixels under it are sampled (PixelCopy, twice a second while it shows) and it turns white or
 * near-black, whichever keeps the higher contrast against the darkest/brightest part.
 */
internal class TvControls(
    private val playerView: PlayerView,
    title: String,
    private val font: (medium: Boolean) -> Typeface?,
    /** Extra rows at the end of the Settings panel (label → action), e.g. "Open in another player". */
    private val extraSettings: () -> List<Pair<String, () -> Unit>>,
) {
    private val context = playerView.context
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null

    /** Added over the PlayerView. */
    val view = FrameLayout(context)
    private val bar = LinearLayout(context)
    private val titleView = text(15f, medium = true)
    private val rew = icon(androidx.media3.ui.R.drawable.exo_ic_rewind, "Back 10 seconds")
    private val play = icon(androidx.media3.ui.R.drawable.exo_icon_pause, "Play or pause")
    private val ffwd = icon(androidx.media3.ui.R.drawable.exo_ic_forward, "Forward 10 seconds")
    private val settings = icon(androidx.media3.ui.R.drawable.exo_ic_settings, "Settings")
    private val position = text(13f)
    private val duration = text(13f)
    private val timeBar = DefaultTimeBar(context, null)
    private val panel = LinearLayout(context)
    private val panelScroll = ScrollView(context)

    private var fg = Color.WHITE
    private var colorAnim: ValueAnimator? = null
    private var scrubbing = false
    private var swallowUp: Int? = null

    val visible get() = view.visibility == View.VISIBLE
    val panelOpen get() = panelScroll.visibility == View.VISIBLE

    init {
        view.visibility = View.GONE
        view.clipChildren = false

        bar.orientation = LinearLayout.VERTICAL
        titleView.text = title
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6); bottomMargin = dp(6) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        for (b in listOf(rew, play, ffwd, settings)) row.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(position, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        // Short, on the right of the same line (not the full width).
        row.addView(timeBar, LinearLayout.LayoutParams(dp(320), dp(32)))
        row.addView(duration, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2))
        view.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(20)) })

        rew.setOnClickListener { seekBy(-10_000); touch() }
        ffwd.setOnClickListener { seekBy(10_000); touch() }
        play.setOnClickListener { togglePlay(); touch() }
        settings.setOnClickListener { openPanel() }
        // Left/right inside the strip move focus; the seek bar keeps them for scrubbing.
        rew.nextFocusLeftId = View.NO_ID
        timeBar.isFocusable = true
        timeBar.setKeyTimeIncrement(10_000)
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, pos: Long) { scrubbing = true; position.text = PlayerActivity.formatTime(pos); touch() }
            override fun onScrubMove(timeBar: TimeBar, pos: Long) { position.text = PlayerActivity.formatTime(pos); touch() }
            override fun onScrubStop(timeBar: TimeBar, pos: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(pos)
                touch()
            }
        })

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(8), dp(8), dp(8), dp(8))
        panelScroll.addView(panel)
        panelScroll.background = GradientDrawable().apply {
            setColor(0xE6141416.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
        panelScroll.visibility = View.GONE
        panelScroll.isFillViewport = false
        view.addView(panelScroll, FrameLayout.LayoutParams(dp(320), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, dp(24), dp(32), dp(104)) })

        applyColor(fg)
    }

    fun attach(p: Player) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (visible) refresh()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (visible) touch()
            }
        })
    }

    // ---------------------------------------------------------------- show / hide

    private val hideRunnable = Runnable { if (player?.isPlaying == true && !panelOpen && !scrubbing) hide() }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            main.postDelayed(this, 500)
        }
    }
    private val sampler = object : Runnable {
        override fun run() {
            if (!visible) return
            sample()
            main.postDelayed(this, 500)
        }
    }

    fun show(focus: View? = play) {
        if (!visible) {
            view.visibility = View.VISIBLE
            refresh()
            main.removeCallbacks(tick); main.post(tick)
            main.removeCallbacks(sampler); main.post(sampler)
        }
        if (focus != null && !panelOpen && view.findFocus() == null) focus.requestFocus()
        touch()
    }

    fun hide() {
        closePanel()
        view.visibility = View.GONE
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
        main.removeCallbacks(sampler)
        playerView.requestFocus()
    }

    /** Any interaction: stay up 5 s more (paused, the strip stays until dismissed). */
    fun touch() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 5_000)
    }

    /**
     * Remote keys, called before the activity's own handling. Hidden: OK / ↑ / ↓ / Menu bring the
     * strip up (focus on play), media keys work. Shown: Back closes the panel, then the strip.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && swallowUp == code) { swallowUp = null; return true }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                val p = player ?: return false
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> p.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
                    else -> togglePlay()
                }
                show(focus = null)
                swallowUp = code
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 10_000 else -10_000)
                show(focus = null)
                swallowUp = code
                return true
            }
        }
        if (!visible) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU
            ) {
                show()
                swallowUp = code
                return true
            }
            return false
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (panelOpen) { if (!panelBack()) closePanel() } else hide()
            swallowUp = code
            return true
        }
        touch()
        return false
    }

    // ---------------------------------------------------------------- playback

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)) p.pause() else p.play()
        refresh()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val end = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, end))
        refresh()
    }

    private fun refresh() {
        val p = player ?: return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        play.setImageResource(if (playing) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play)
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        timeBar.setDuration(d)
        timeBar.setBufferedPosition(p.bufferedPosition)
        if (!scrubbing) {
            timeBar.setPosition(p.currentPosition)
            position.text = PlayerActivity.formatTime(p.currentPosition)
        }
        duration.text = if (d > 0) PlayerActivity.formatTime(d) else "--:--"
    }

    // ---------------------------------------------------------------- settings panel

    /** The submenu being shown, or null for the main list. */
    private var submenu: String? = null

    internal fun openPanel() {
        submenu = null
        buildMain()
        panelScroll.visibility = View.VISIBLE
        main.removeCallbacks(hideRunnable)
        focusSelected()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelScroll.visibility = View.GONE
        if (visible) settings.requestFocus()
        touch()
    }

    /** Back inside the panel: submenu → main list. False when already on the main list. */
    private fun panelBack(): Boolean {
        val from = submenu ?: return false
        submenu = null
        buildMain()
        panel.findViewWithTag<View>(from)?.requestFocus() ?: panel.getChildAt(0)?.requestFocus()
        return true
    }

    private fun buildMain() {
        panel.removeAllViews()
        val p = player ?: return
        panel.addView(header("Settings"))
        panel.addView(row("Subtitles", current(p, C.TRACK_TYPE_TEXT) ?: "Off", tag = "sub") { showTracks(C.TRACK_TYPE_TEXT) })
        if (trackOptions(p, C.TRACK_TYPE_AUDIO).size > 1) {
            panel.addView(row("Audio", current(p, C.TRACK_TYPE_AUDIO) ?: "Default", tag = "audio") { showTracks(C.TRACK_TYPE_AUDIO) })
        }
        panel.addView(row("Speed", speedLabel(p.playbackParameters.speed), tag = "speed") { showSpeeds() })
        for ((label, action) in extraSettings()) panel.addView(row(label, null) { hide(); action() })
    }

    private fun showTracks(type: Int) {
        val p = player ?: return
        submenu = if (type == C.TRACK_TYPE_TEXT) "sub" else "audio"
        panel.removeAllViews()
        panel.addView(header(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio"))
        val options = trackOptions(p, type)
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || options.none { it.selected }
            panel.addView(choice("Off", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                panelBack()
            })
        }
        for (o in options) {
            panel.addView(choice(o.label, o.selected) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                    .build()
                panelBack()
            })
        }
        if (options.isEmpty() && type == C.TRACK_TYPE_TEXT) panel.addView(note("No subtitles for this video"))
        focusSelected()
    }

    private fun showSpeeds() {
        val p = player ?: return
        submenu = "speed"
        panel.removeAllViews()
        panel.addView(header("Speed"))
        val now = p.playbackParameters.speed
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            panel.addView(choice(speedLabel(s), kotlin.math.abs(s - now) < 0.01f) {
                p.setPlaybackSpeed(s)
                panelBack()
            })
        }
        focusSelected()
    }

    private fun focusSelected() {
        (0 until panel.childCount).map { panel.getChildAt(it) }.firstOrNull { it.tag == SELECTED }?.requestFocus()
            ?: (0 until panel.childCount).map { panel.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
    }

    private class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    private fun trackOptions(p: Player, type: Int): List<TrackOption> {
        val out = mutableListOf<TrackOption>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(g, i, trackLabel(g.getTrackFormat(i), out.size + 1), g.isTrackSelected(i))
            }
        }
        // Same label twice (two English tracks): number them so they can be told apart.
        val dupes = out.groupBy { it.label }.filter { it.value.size > 1 }.keys
        return out.map { if (it.label in dupes) TrackOption(it.group, it.index, "${it.label} (${out.indexOf(it) + 1})", it.selected) else it }
    }

    private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).takeIf { d -> d.isNotBlank() } ?: it }
        val channels = if (f.channelCount == 6) " 5.1" else if (f.channelCount == 8) " 7.1" else ""
        return listOfNotNull(f.label?.takeIf { it.isNotBlank() }, lang?.takeIf { f.label?.contains(it, true) != true })
            .joinToString(" · ").ifBlank { "Track $n" } + channels
    }

    private fun current(p: Player, type: Int): String? {
        if (type == C.TRACK_TYPE_TEXT && p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
        return trackOptions(p, type).firstOrNull { it.selected }?.label
    }

    private fun speedLabel(s: Float) = if (s == 1f) "Normal" else (if (s % 1f == 0f) "${s.toInt()}×" else "${s}×")

    // ---------------------------------------------------------------- adaptive contrast

    /**
     * Samples the picture under the strip. Parts of the strip outside the video (letterbox) are
     * black. White and near-black are scored by the contrast they keep against the worst 10 % of
     * the sampled pixels; the strip only switches when the other colour is clearly better.
     */
    private fun sample() {
        val sv = playerView.videoSurfaceView as? SurfaceView
        if (sv == null || sv.holder.surface?.isValid != true || bar.width == 0 || sv.width == 0) { decide(floatArrayOf(0f)); return }
        val barLoc = IntArray(2).also { bar.getLocationOnScreen(it) }
        val svLoc = IntArray(2).also { sv.getLocationOnScreen(it) }
        val barRect = Rect(barLoc[0], barLoc[1], barLoc[0] + bar.width, barLoc[1] + bar.height)
        val svRect = Rect(svLoc[0], svLoc[1], svLoc[0] + sv.width, svLoc[1] + sv.height)
        val inter = Rect()
        if (!inter.setIntersect(barRect, svRect)) { decide(floatArrayOf(0f)); return }
        val outside = 1f - (inter.width().toFloat() * inter.height()) / (barRect.width().toFloat() * barRect.height())
        inter.offset(-svLoc[0], -svLoc[1])
        val bmp = Bitmap.createBitmap(64, 12, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(sv, inter, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val black = (px.size * outside / (1f - outside).coerceAtLeast(0.05f)).toInt().coerceAtMost(px.size * 4)
                    val lum = FloatArray(px.size + black)
                    for (i in px.indices) lum[i] = luminance(px[i])
                    decide(lum)
                }
                // DRM / tunnelled video can't be copied: keep the current colour.
                bmp.recycle()
            }, main)
        } catch (_: IllegalArgumentException) {
            bmp.recycle()
        }
    }

    internal fun decide(lum: FloatArray) {
        lum.sort()
        fun worst(fgLum: Float): Float {
            // Contrast against every pixel; the 10th percentile is what the busiest part of the picture leaves.
            val c = FloatArray(lum.size) { contrast(fgLum, lum[it]) }
            c.sort()
            return c[(c.size * 0.1f).toInt().coerceIn(0, c.size - 1)]
        }
        val white = worst(1f)
        val dark = worst(luminance(DARK))
        val currentIsWhite = target == Color.WHITE
        val next = when {
            currentIsWhite && dark > white * 1.3f -> DARK
            !currentIsWhite && white > dark * 1.3f -> Color.WHITE
            else -> target
        }
        if (next != target) animateTo(next)
    }

    /** The colour the strip is (or is animating to). */
    internal var target = Color.WHITE
        private set

    private fun animateTo(color: Int) {
        target = color
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), fg, color).apply {
            duration = 220
            addUpdateListener { paint(it.animatedValue as Int) }
            start()
        }
    }

    internal fun applyColor(c: Int) {
        target = c
        colorAnim?.cancel()
        paint(c)
    }

    private fun paint(c: Int) {
        fg = c
        val shadow = if (luminance(c) > 0.5f) 0x80000000.toInt() else 0x66FFFFFF
        for (t in listOf(titleView, position, duration)) {
            t.setTextColor(c)
            t.setShadowLayer(dp(6).toFloat(), 0f, 0f, shadow)
        }
        for (b in listOf(rew, play, ffwd, settings)) {
            b.imageTintList = ColorStateList.valueOf(c)
            b.background = focusRing(c)
        }
        timeBar.setPlayedColor(c)
        timeBar.setScrubberColor(c)
        timeBar.setBufferedColor(withAlpha(c, 0x8C))
        timeBar.setUnplayedColor(withAlpha(c, 0x40))
    }

    private fun focusRing(c: Int) = StateListDrawable().apply {
        val on = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(c, 0x2E))
            setStroke(dp(2), c)
        }
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(android.R.attr.state_pressed), on)
        addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
    }

    // ---------------------------------------------------------------- views

    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(Color.WHITE)
    }

    private fun icon(res: Int, label: String) = ImageButton(context).apply {
        setImageResource(res)
        contentDescription = label
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = true
    }

    private fun header(label: String) = text(12f, medium = true).apply {
        text = label.uppercase()
        letterSpacing = 0.08f
        setTextColor(0x99FFFFFF.toInt())
        setPadding(dp(12), dp(6), dp(12), dp(8))
    }

    private fun note(label: String) = text(13f).apply {
        text = label
        setTextColor(0x99FFFFFF.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun row(label: String, value: String?, tag: String? = null, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isFocusable = true
        this.tag = tag
        background = rowBackground()
        addView(text(14f, medium = true).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(text(13f).apply {
            text = "$value  ›"
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(150)
        })
        setOnClickListener { onClick() }
    }

    private fun choice(label: String, selected: Boolean, onClick: () -> Unit) = row((if (selected) "✓  " else "     ") + label, null) { onClick() }
        .apply { if (selected) tag = SELECTED }

    private fun rowBackground() = StateListDrawable().apply {
        val on = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.WHITE) }
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(android.R.attr.state_pressed), on)
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val SELECTED = "selected"
        /** Near-black: within 5 % of black's contrast, a little softer on the eye. */
        val DARK = 0xFF0B0B0C.toInt()

        private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

        /** WCAG relative luminance of an sRGB colour. */
        fun luminance(argb: Int): Float {
            fun ch(v: Int): Float {
                val s = v / 255f
                return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            }
            return 0.2126f * ch(Color.red(argb)) + 0.7152f * ch(Color.green(argb)) + 0.0722f * ch(Color.blue(argb))
        }

        fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
