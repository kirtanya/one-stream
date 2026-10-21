package com.kirtanya.reel.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.R
import kotlin.math.abs

/**
 * Phone player controls, modelled on the TV strip (the user: the controls must not disturb the
 * picture). Nothing at the top and nothing over the middle: everything lives in one band along the
 * bottom edge, white with a small tight shadow, no scrim.
 *  - One row of controls, every one the same size (48 dp touch, same icon size, no filled play
 *    disc): back, back 10, play / pause, forward 10 on the left; next episode (when ready), lock,
 *    subtitles, settings on the right. Speed, picture mode and picture-in-picture live in the
 *    settings panel (PiP still starts by itself on Home).
 *  - The title (and "Ends at") is a small caption between the two groups, only where it fits
 *    (landscape); in portrait the row has no room and it's left out.
 *  - Under the row, the seek bar: a hairline (played part only), thumb + halo and a time bubble
 *    while touched, a full 48 dp touch area; elapsed / remaining small above its ends.
 *
 * Gestures on the picture: tap shows / hides; double-tap the left or right third skips 10 s (taps
 * in a row add up); drag sideways scrubs; drag up/down on the left sets brightness, on the right
 * volume; press and hold plays at 2× until released; pinch zooms to fill / back to fit. Lock hides
 * everything and ignores gestures until the lock is tapped again.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class PhoneControls(
    private val activity: Activity,
    private val playerView: PlayerView,
    title: String,
    private val font: (medium: Boolean) -> Typeface?,
    extraSettings: () -> List<PanelExtra>,
    private val onBack: () -> Unit,
    /** Picture in picture, as a settings row (null: the phone can't). */
    onPip: (() -> Unit)?,
    /** "Next episode" when one is ready, else null (the button hides). */
    private val nextLabel: () -> String?,
    private val onNext: () -> Unit,
    /** Pinch picked fill (true) or fit (false): the activity applies and remembers it. */
    private val onZoom: (Boolean) -> Unit = {},
) : FrameLayout(activity) {
    private val ctx: Context = activity
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null

    private val controls = FrameLayout(ctx)
    /** The bottom band (its height decides how far captions move up while it shows). */
    private var bandView: View? = null
    private val titleView = text(CAPTION_SP, medium = true)
    private val metaView = text(CAPTION_SP)
    private val back = RoundButton(ctx, R.drawable.ic_pl_back, "Back", BUTTON_DP, ICON_DP)
    private val rew = SkipButton(ctx, forward = false, seconds = 10, sizeDp = BUTTON_DP, iconDp = SKIP_ICON_DP, font = font(true))
    private val play = RoundButton(ctx, R.drawable.ic_pl_pause, "Play or pause", BUTTON_DP, ICON_DP)
    private val ffwd = SkipButton(ctx, forward = true, seconds = 10, sizeDp = BUTTON_DP, iconDp = SKIP_ICON_DP, font = font(true))
    private val next = RoundButton(ctx, R.drawable.ic_pl_next, "Next episode", BUTTON_DP, ICON_DP).apply { visibility = GONE }
    private val lock = RoundButton(ctx, R.drawable.ic_pl_lock_open, "Lock controls", BUTTON_DP, ICON_DP)
    private val subs = RoundButton(ctx, R.drawable.ic_pl_subtitles, "Subtitles", BUTTON_DP, ICON_DP)
    private val tune = RoundButton(ctx, R.drawable.ic_pl_tune, "Settings", BUTTON_DP, ICON_DP)
    private val elapsed = text(CAPTION_SP, medium = true)
    private val remaining = text(CAPTION_SP, medium = true)
    private val seek = SeekStrip(ctx, font(true))
    private val unlock = RoundButton(ctx, R.drawable.ic_pl_lock, "Unlock controls", 52, 24)
    private val hud = chip().apply { visibility = GONE }
    private val leftBubble = bubble()
    private val rightBubble = bubble()
    private val settingsPanel = SettingsPanel(
        ctx, font, { player },
        // Picture in picture moved off the bar into the panel, first of the extra rows.
        { (if (onPip != null) listOf(PanelExtra(PIP_ROW, action = onPip)) else emptyList()) + extraSettings() },
        onExtra = { closePanel() },
    )

    /** On/off rows in the settings panel (Picture, Cinema mode). */
    fun setToggles(list: List<PanelToggle>) { settingsPanel.toggles = list }

    private var locked = false
    private var zoomed = false
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val visible get() = controls.visibility == VISIBLE

    init {
        clipChildren = false
        controls.clipChildren = false
        // Like the TV strip: white marks with a small tight shadow, no scrim over the picture. Only a
        // low shade from the bottom edge up to the band's top (clear there), because small white
        // text on a bright picture fails the contrast rule without it (PlayerColors.SHADE_BAND).
        val shade = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(PlayerColors.SHADE_BAND, PlayerColors.SHADE_BAND_MID, PlayerColors.SHADE_CLEAR))
        }
        controls.addView(shade, LayoutParams(-1, 0, Gravity.BOTTOM))
        val safe = FrameLayout(ctx).apply { clipChildren = false }
        controls.addView(safe, LayoutParams(-1, -1))

        // The title block: title, then "Ends at", both caption size; the row hides it where it doesn't fit.
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titleView.text = title
        for (t in listOf(titleView, metaView)) {
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        val row = BandRow(ctx, left = listOf(back, rew, play, ffwd), middle = info, right = listOf(next, lock, subs, tune))

        // Elapsed above the seek bar's start, remaining above its end (as on the TV).
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        times.addView(elapsed, LinearLayout.LayoutParams(-2, -2))
        times.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(-2, -2))

        // The band: row, times, seek bar. The seek bar's 48 dp touch area reaches up behind the times
        // (they take no touches) so its line sits just under them; the band's top margin keeps that
        // touch area off the buttons above.
        val band = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        bandView = band
        band.addView(row, LinearLayout.LayoutParams(-1, -2).apply { marginStart = ctx.dp(ROW_EDGE_DP); marginEnd = ctx.dp(ROW_EDGE_DP) })
        band.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4); marginStart = ctx.dp(EDGE_DP); marginEnd = ctx.dp(EDGE_DP) })
        seek.line = true
        band.addView(seek, LinearLayout.LayoutParams(-1, ctx.dp(BUTTON_DP)).apply {
            topMargin = -ctx.dp(SEEK_UNDER_TIMES_DP)
            marginStart = ctx.dp(EDGE_DP); marginEnd = ctx.dp(EDGE_DP)
        })
        safe.addView(band, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ctx.dp(4) })
        // The shade reaches exactly the band's top (it grows when the row wraps to two lines).
        band.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            val h = controls.height - top
            if (shade.layoutParams.height != h) shade.post { shade.layoutParams = shade.layoutParams.apply { height = h } }
        }

        addView(leftBubble, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(56) })
        addView(rightBubble, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = ctx.dp(56) })
        addView(controls, LayoutParams(-1, -1))
        addView(hud, LayoutParams(-2, ctx.dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(72) })
        addView(unlock, LayoutParams(ctx.dp(52), ctx.dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = ctx.dp(24) })
        unlock.visibility = GONE
        addView(settingsPanel.panel, LayoutParams(ctx.dp(PANEL_DP), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) })

        // Keep the band clear of the notch / gesture bars.
        ViewCompat.setOnApplyWindowInsetsListener(safe) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        back.setOnClickListener { onBack() }
        rew.setOnClickListener { seekBy(-10_000); touch() }
        ffwd.setOnClickListener { seekBy(10_000); touch() }
        play.setOnClickListener { togglePlay(); touch() }
        subs.setOnClickListener { openPanel("sub") }
        tune.setOnClickListener { openPanel(null) }
        lock.setOnClickListener { setLocked(true) }
        unlock.setOnClickListener { setLocked(false) }
        next.setOnClickListener { onNext() }
        seek.onScrubStart = { touch() }
        // The bubble above the thumb shows the time while scrubbing the bar (no second read-out up top).
        seek.onScrubMove = { elapsed.text = PlayerActivity.formatTime(it); remaining.text = "−" + PlayerActivity.formatTime((seek.duration - it).coerceAtLeast(0)); touch() }
        seek.onSeek = { player?.seekTo(it); touch() }
        applyLook()
        // Alone on the picture: a dark disc keeps the unlock button readable on bright scenes.
        unlock.tint(PlayerColors.FOREGROUND)
        unlock.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PlayerColors.SHADOW) }
        controls.visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Portrait is 360 dp wide: the panel narrows to keep its 16 dp margins.
        val lp = settingsPanel.panel.layoutParams as LayoutParams
        lp.width = minOf(ctx.dp(PANEL_DP), MeasureSpec.getSize(widthMeasureSpec) - lp.leftMargin - lp.rightMargin)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** White text and icons, each with the TV strip's small tight shadow (never a halo). */
    private fun applyLook() {
        val c = PlayerColors.FOREGROUND
        for (t in listOf(titleView, metaView, elapsed, remaining)) {
            t.setTextColor(if (t === metaView) PlayerColors.SECONDARY else c)
            t.setShadowLayer(ctx.dpf(1.5f), 0f, ctx.dpf(1f), PlayerColors.CONTROL_SHADOW)
        }
        for (b in listOf(back, rew, play, ffwd, next, lock, subs, tune)) {
            b.tint(c)
            b.addShadow(PlayerColors.CONTROL_SHADOW)
        }
        rew.shadowNumber(PlayerColors.CONTROL_SHADOW)
        ffwd.shadowNumber(PlayerColors.CONTROL_SHADOW)
        seek.tint(c)
    }

    fun attach(p: Player) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (visible) refresh() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (visible) touch() }
        })
    }

    // ---------------------------------------------------------------- show / hide

    private val hideRunnable = Runnable { if (player?.isPlaying == true && !settingsPanel.open && !seek.scrubbing) hide() }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            main.postDelayed(this, 500)
        }
    }

    /** The controls showing or hiding (captions move up out of the band's way while it shows). */
    var onShown: ((Boolean) -> Unit)? = null

    /** How much of the bottom the band covers right now, in px (0 when hidden). */
    fun coveredBottomPx(): Int {
        val b = bandView ?: return 0
        if (!visible || b.height == 0) return 0
        val loc = IntArray(2).also { b.getLocationInWindow(it) }
        val me = IntArray(2).also { getLocationInWindow(it) }
        return (me[1] + height) - loc[1]
    }

    fun show() {
        if (locked) { flashUnlock(); return }
        if (!visible) {
            controls.visibility = VISIBLE
            // After layout, so the band has its size.
            post { onShown?.invoke(true) }
            controls.alpha = 0f
            controls.animate().alpha(1f).setDuration(160).start()
            refresh()
            main.removeCallbacks(tick); main.post(tick)
        }
        touch()
    }

    fun hide() {
        closePanel()
        controls.animate().alpha(0f).setDuration(160).withEndAction { controls.visibility = GONE; onShown?.invoke(false) }.start()
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
    }

    /** Hide at once (picture-in-picture, the Up next card). */
    fun hideNow() {
        closePanel()
        controls.animate().cancel()
        controls.visibility = GONE
        onShown?.invoke(false)
        unlock.visibility = GONE
        hud.visibility = GONE
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
    }

    fun touch() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 3_500)
    }

    /** System Back: closes the settings panel first. True when it did. */
    fun handleBack(): Boolean {
        if (settingsPanel.open) { if (!settingsPanel.back()) closePanel(); return true }
        return false
    }

    internal fun openPanelForPreview() = openPanel(null)

    private fun openPanel(section: String?) {
        settingsPanel.show(section)
        main.removeCallbacks(hideRunnable)
    }

    private fun closePanel() {
        if (!settingsPanel.open) return
        settingsPanel.hide()
        touch()
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            hideNow()
            flashUnlock()
        } else {
            unlock.visibility = GONE
            show()
        }
    }

    private val hideUnlock = Runnable { unlock.animate().alpha(0f).setDuration(200).withEndAction { unlock.visibility = GONE }.start() }

    private fun flashUnlock() {
        unlock.visibility = VISIBLE
        unlock.alpha = 1f
        main.removeCallbacks(hideUnlock)
        main.postDelayed(hideUnlock, 2_000)
    }

    /** Renders only: a mid-film state without a player. */
    internal fun preview(positionMs: Long, durationMs: Long, bufferedMs: Long, playing: Boolean) {
        seek.duration = durationMs
        seek.buffered = bufferedMs
        seek.position = positionMs
        elapsed.text = PlayerActivity.formatTime(positionMs)
        remaining.text = "−" + PlayerActivity.formatTime(durationMs - positionMs)
        play.setIcon(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
        metaView.text = "Ends at 10:42 PM"
        showNext(nextLabel())
    }

    /** Renders only: a finger on the seek bar at [positionMs] (thumb, halo and time bubble). */
    internal fun previewScrub(positionMs: Long) {
        val x = seek.width * (positionMs.toFloat() / seek.duration.coerceAtLeast(1))
        val t = android.os.SystemClock.uptimeMillis()
        val action = if (seek.scrubbing) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_DOWN
        seek.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, seek.height / 2f, 0))
    }

    private fun showNext(label: String?) {
        next.visibility = if (label != null) VISIBLE else GONE
        next.contentDescription = label ?: "Next episode"
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

    /** Like YouTube: pinch out fills the screen by cropping, pinch in fits it again (also in Settings → Picture). */
    private fun setZoom(on: Boolean) {
        syncZoom(on)
        onZoom(on)
        showHud(R.drawable.ic_pl_fit, if (on) "Zoom to fill" else "Fit to screen")
        hideHudSoon()
    }

    /** The Picture setting changed in the settings panel: the pinch follows it. */
    fun syncZoom(on: Boolean) { zoomed = on }

    private fun refresh() {
        val p = player ?: return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        play.setIcon(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        seek.duration = d
        seek.buffered = p.bufferedPosition
        seek.position = p.currentPosition
        if (!seek.scrubbing) elapsed.text = PlayerActivity.formatTime(p.currentPosition)
        remaining.text = if (d > 0) "−" + PlayerActivity.formatTime((d - p.currentPosition).coerceAtLeast(0)) else "--:--"
        val s = p.playbackParameters.speed
        if (d > 0) {
            val left = ((d - p.currentPosition) / s).toLong().coerceAtLeast(0)
            metaView.text = "Ends at " + android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(System.currentTimeMillis() + left))
        }
        showNext(nextLabel())
    }

    // ---------------------------------------------------------------- gestures

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private enum class Drag { NONE, SEEK, BRIGHTNESS, VOLUME }
    private var drag = Drag.NONE
    private var dragStartPos = 0L
    private var dragStartValue = 0f
    private var fastHeld = false
    private var speedBeforeFast = 1f
    private var tapSeekTotal = 0L
    private var tapSeekSide = 0
    private val endTapSeek = Runnable { tapSeekTotal = 0; tapSeekSide = 0; leftBubble.fade(); rightBubble.fade() }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (locked) { flashUnlock(); return true }
            if (settingsPanel.open) { closePanel(); return true }
            // A tap while the skip bubble is up adds another 10 s on that side.
            val side = when { e.x < width / 3f -> -1; e.x > width * 2 / 3f -> 1; else -> 0 }
            if (tapSeekSide != 0 && side == tapSeekSide) { tapSeek(side); return true }
            if (visible) hide() else show()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            val third = width / 3f
            when {
                e.x < third -> tapSeek(-1)
                e.x > width - third -> tapSeek(1)
                else -> { togglePlay(); if (visible) touch() }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked || drag != Drag.NONE) return
            val p = player ?: return
            // While playing or waiting for data (not when the user paused).
            if (!p.playWhenReady) return
            fastHeld = true
            speedBeforeFast = p.playbackParameters.speed
            p.setPlaybackSpeed(2f)
            showHud(R.drawable.ic_pl_fast, "2× speed")
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (locked || fastHeld || e1 == null) return false
            val p = player ?: return false
            val tx = e2.x - e1.x
            val ty = e2.y - e1.y
            if (drag == Drag.NONE) {
                // Ignore drags that start in the system gesture zones (top/bottom edges).
                if (e1.y < ctx.dp(40) || e1.y > height - ctx.dp(40)) return false
                if (abs(tx) < slop * 2 && abs(ty) < slop * 2) return false
                drag = when {
                    abs(tx) > abs(ty) -> Drag.SEEK
                    e1.x < width / 2f -> Drag.BRIGHTNESS
                    else -> Drag.VOLUME
                }
                dragStartPos = p.currentPosition
                dragStartValue = when (drag) {
                    Drag.BRIGHTNESS -> currentBrightness()
                    Drag.VOLUME -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    else -> 0f
                }
            }
            when (drag) {
                Drag.SEEK -> {
                    // The width of the screen is 2 minutes.
                    val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return true
                    val target = (dragStartPos + (tx / width * 120_000).toLong()).coerceIn(0, d)
                    val delta = (target - dragStartPos) / 1000
                    showHud(null, (if (delta >= 0) "+" else "−") + PlayerActivity.formatTime(abs(delta) * 1000) + "   " + PlayerActivity.formatTime(target))
                    dragTarget = target
                }
                Drag.BRIGHTNESS -> {
                    val v = (dragStartValue - ty / (height * 0.8f)).coerceIn(0.01f, 1f)
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = v }
                    showHud(R.drawable.ic_pl_brightness, "${(v * 100).toInt()}%")
                }
                Drag.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (dragStartValue - ty / (height * 0.8f)).coerceIn(0f, 1f)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                    showHud(R.drawable.ic_pl_volume, "${(v * 100).toInt()}%")
                }
                Drag.NONE -> Unit
            }
            return true
        }
    })

    private var dragTarget = -1L

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var total = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { total = 1f; return !locked }
        override fun onScale(detector: ScaleGestureDetector): Boolean { total *= detector.scaleFactor; return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (total > 1.08f && !zoomed) setZoom(true)
            else if (total < 0.92f && zoomed) setZoom(false)
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) return true
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (drag == Drag.SEEK && dragTarget >= 0) player?.seekTo(dragTarget)
            if (drag != Drag.NONE) hideHudSoon()
            drag = Drag.NONE
            dragTarget = -1
            if (fastHeld) {
                fastHeld = false
                player?.setPlaybackSpeed(speedBeforeFast)
                hideHudSoon(0)
            }
        }
        return true
    }

    private fun tapSeek(side: Int) {
        if (tapSeekSide != side) tapSeekTotal = 0
        tapSeekSide = side
        tapSeekTotal += 10
        seekBy(side * 10_000L)
        val bubble = if (side < 0) leftBubble else rightBubble
        (if (side < 0) rightBubble else leftBubble).fade()
        bubble.text = if (side < 0) "«  $tapSeekTotal s" else "$tapSeekTotal s  »"
        bubble.visibility = VISIBLE
        bubble.alpha = 1f
        bubble.scaleX = 0.92f; bubble.scaleY = 0.92f
        bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        main.removeCallbacks(endTapSeek)
        main.postDelayed(endTapSeek, 1_000)
    }

    private fun currentBrightness(): Float {
        val w = activity.window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    // ---------------------------------------------------------------- HUD

    private val hideHud = Runnable { hud.animate().alpha(0f).setDuration(200).withEndAction { hud.visibility = GONE }.start() }

    private fun showHud(icon: Int?, label: String) {
        main.removeCallbacks(hideHud)
        val iv = hud.getChildAt(0) as ImageView
        iv.visibility = if (icon != null) VISIBLE else GONE
        if (icon != null) iv.setImageResource(icon)
        (hud.getChildAt(1) as TextView).text = label
        hud.animate().cancel()
        hud.alpha = 1f
        hud.visibility = VISIBLE
    }

    private fun hideHudSoon(delayMs: Long = 600) {
        main.removeCallbacks(hideHud)
        main.postDelayed(hideHud, delayMs)
    }

    // ---------------------------------------------------------------- views

    private fun text(sp: Float, medium: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(PlayerColors.FOREGROUND)
        fontFeatureSettings = "tnum"
    }

    /** The HUD: a 40 dp glass pill with an optional icon and a label (brightness, volume, 2×, zoom). */
    private fun chip() = Chip(ctx, 40).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        addView(ImageView(ctx).apply {
            imageTintList = ColorStateList.valueOf(PlayerColors.FOREGROUND)
            visibility = GONE
        }, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginEnd = ctx.dp(6) })
        addView(TextView(ctx).apply {
            typeface = font(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(PlayerColors.FOREGROUND)
        })
    }

    private fun bubble() = TextView(ctx).apply {
        typeface = font(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(PlayerColors.FOREGROUND)
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        background = GradientDrawable().apply { setColor(PlayerColors.BUBBLE); cornerRadius = ctx.dpf(999f) }
        visibility = GONE
    }

    private fun View.fade() {
        if (visibility != VISIBLE) return
        animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
    }

    /** A row that draws a glass pill [pillDp] tall around its content. */
    private class Chip(context: Context, pillDp: Int) : LinearLayout(context) {
        private val pillHeight = context.dp(pillDp)
        private val pill = GradientDrawable().apply {
            setColor(PlayerColors.CHIP_FILL)
            cornerRadius = context.dpf(999f)
            setStroke(context.dp(1), PlayerColors.GLASS_STROKE)
        }

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val h = minOf(pillHeight, height)
            pill.setBounds(0, (height - h) / 2, width, (height + h) / 2)
            pill.draw(canvas)
        }
    }

    /**
     * The controls row: [left] buttons at the start, [right] at the end, [middle] (the title) in
     * between when at least [TITLE_MIN_DP] is left over (landscape), else it gets no room at all.
     * Buttons are 48 dp apart by 8 dp when that fits, touching when not; when even that doesn't
     * fit (portrait with Next), the right group goes up a line, so no touch area ever shrinks.
     */
    private class BandRow(context: Context, private val left: List<View>, private val middle: View, private val right: List<View>) : ViewGroup(context) {
        private val btn = context.dp(BUTTON_DP)
        private var gap = 0
        private var wrapped = false
        private var titleWidth = 0

        init {
            clipChildren = false
            for (v in left + middle + right) addView(v)
        }

        private fun shown(group: List<View>) = group.filter { it.visibility != GONE }

        private fun groupWidth(group: List<View>, g: Int) = shown(group).size.let { n -> if (n == 0) 0 else n * btn + (n - 1) * g }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val roomy = context.dp(8)
            wrapped = groupWidth(left, 0) + groupWidth(right, 0) > w
            gap = if (wrapped || groupWidth(left, roomy) + groupWidth(right, roomy) <= w) roomy else 0
            // The title sits on the line the right group is on; on its own line it has the whole width beside it.
            val free = if (wrapped) w - groupWidth(right, gap) else w - groupWidth(left, gap) - groupWidth(right, gap)
            val side = context.dp(TITLE_GAP_DP)
            titleWidth = (free - 2 * side).takeIf { it >= context.dp(TITLE_MIN_DP) } ?: 0
            val exact = MeasureSpec.makeMeasureSpec(btn, MeasureSpec.EXACTLY)
            for (v in shown(left) + shown(right)) v.measure(exact, exact)
            middle.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(btn, MeasureSpec.AT_MOST))
            setMeasuredDimension(w, if (wrapped) 2 * btn else btn)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val bottomTop = if (wrapped) btn else 0
            val rightTop = 0
            var x = 0
            for (v in shown(left)) { v.layout(x, bottomTop, x + btn, bottomTop + btn); x += btn + gap }
            var end = w
            for (v in shown(right).reversed()) { v.layout(end - btn, rightTop, end, rightTop + btn); end -= btn + gap }
            val side = context.dp(TITLE_GAP_DP)
            val titleLeft = if (wrapped) side else x - gap + side
            val ty = rightTop + (btn - middle.measuredHeight) / 2
            middle.layout(titleLeft, ty, titleLeft + titleWidth, ty + middle.measuredHeight)
        }
    }

    private companion object {
        /** Every control's size: its touch area (docs/DESIGN_STYLE.md §2). */
        const val BUTTON_DP = 48
        /** Every icon's size (the skip arrows a little bigger: their number sits inside). */
        const val ICON_DP = 24
        const val SKIP_ICON_DP = 28
        /** Caption (DESIGN_STYLE §2): the title, "Ends at" and the times. */
        const val CAPTION_SP = 12f
        /** The seek bar and the times start / end this far from the screen edges (the phone gutter). */
        const val EDGE_DP = 16
        /** The row's buttons are 48 dp wide around ~24 dp icons: 4 dp puts the first / last icon near the gutter. */
        const val ROW_EDGE_DP = 4
        /** How far the seek bar's touch area reaches up behind the times (its line sits 4 dp under them). */
        const val SEEK_UNDER_TIMES_DP = 20
        /** The title only where at least this much room is left beside the buttons. */
        const val TITLE_MIN_DP = 120
        const val TITLE_GAP_DP = 16
        const val PANEL_DP = 340
    }
}
