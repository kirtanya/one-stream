package com.kirtanya.reel.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.R
import kotlin.math.abs

/**
 * Phone player controls. Top: back, title + "ends at", subtitles, settings. Centre: back 10 / play /
 * forward 10. Bottom: times + seek bar, then lock, speed, fit, picture-in-picture and next episode.
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
    extraSettings: () -> List<Pair<String, () -> Unit>>,
    private val onBack: () -> Unit,
    private val onPip: (() -> Unit)?,
    /** "Next episode" when one is ready, else null (the button hides). */
    private val nextLabel: () -> String?,
    private val onNext: () -> Unit,
) : FrameLayout(activity) {
    private val ctx: Context = activity
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null

    private val controls = FrameLayout(ctx)
    private val topShade = View(ctx)
    private val bottomShade = View(ctx)
    private val titleView = text(16f, medium = true)
    private val metaView = text(12f)
    private val play = PlayButton(ctx, 72)
    private val rew = SkipButton(ctx, forward = false, seconds = 10, sizeDp = 56, iconDp = 34, font = font(true))
    private val ffwd = SkipButton(ctx, forward = true, seconds = 10, sizeDp = 56, iconDp = 34, font = font(true))
    private val back = RoundButton(ctx, R.drawable.ic_pl_back, "Back", 48, 26)
    private val subs = RoundButton(ctx, R.drawable.ic_pl_subtitles, "Subtitles", 48, 22)
    private val tune = RoundButton(ctx, R.drawable.ic_pl_tune, "Settings", 48, 22)
    private val elapsed = text(13f, medium = true)
    private val remaining = text(13f, medium = true)
    private val seek = SeekStrip(ctx, font(true))
    private val lock = RoundButton(ctx, R.drawable.ic_pl_lock_open, "Lock controls", 44, 20)
    private val speedChip = chip("1×", null)
    private val fit = RoundButton(ctx, R.drawable.ic_pl_fit, "Zoom to fill", 44, 20)
    private val pip = RoundButton(ctx, R.drawable.ic_pl_pip, "Picture in picture", 44, 20)
    private val next = chip("Next episode", R.drawable.ic_pl_next)
    private val unlock = RoundButton(ctx, R.drawable.ic_pl_lock, "Unlock controls", 52, 24)
    private val hud = chip("", null).apply { visibility = GONE }
    private val leftBubble = bubble()
    private val rightBubble = bubble()
    private val settingsPanel = SettingsPanel(ctx, font, { player }, extraSettings) { closePanel() }

    private var locked = false
    private var zoomed = false
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val visible get() = controls.visibility == VISIBLE

    init {
        clipChildren = false
        controls.clipChildren = false
        // Shades only behind the top and bottom rows (the middle of the picture stays clear).
        topShade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        bottomShade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0))
        controls.addView(topShade, LayoutParams(-1, ctx.dp(120), Gravity.TOP))
        controls.addView(bottomShade, LayoutParams(-1, ctx.dp(150), Gravity.BOTTOM))

        val safe = FrameLayout(ctx).apply { clipChildren = false }
        controls.addView(safe, LayoutParams(-1, -1))

        // Top bar
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(back, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(6), 0, ctx.dp(8), 0) }
        titleView.text = title
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        metaView.setTextColor(0xCCFFFFFF.toInt())
        titles.addView(titleView)
        titles.addView(metaView)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(subs, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        top.addView(tune, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        safe.addView(top, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(ctx.dp(8), ctx.dp(8), ctx.dp(8), 0) })

        // Centre transport
        val centre = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        centre.addView(rew, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
        centre.addView(play, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)).apply { marginStart = ctx.dp(36); marginEnd = ctx.dp(36) })
        centre.addView(ffwd, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
        safe.addView(centre, LayoutParams(-2, -2, Gravity.CENTER))

        // Bottom: seek row, then actions
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val seekRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        seekRow.addView(elapsed, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ctx.dp(36), 1f).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) })
        seekRow.addView(remaining, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(8) })
        bottom.addView(seekRow, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(lock, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        actions.addView(speedChip, LinearLayout.LayoutParams(-2, ctx.dp(34)).apply { marginStart = ctx.dp(4) })
        actions.addView(fit, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { marginStart = ctx.dp(4) })
        if (onPip != null) actions.addView(pip, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { marginStart = ctx.dp(4) })
        actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(next, LinearLayout.LayoutParams(-2, ctx.dp(36)))
        bottom.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        safe.addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ctx.dp(8), 0, ctx.dp(8), ctx.dp(8)) })

        addView(leftBubble, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(56) })
        addView(rightBubble, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = ctx.dp(56) })
        addView(controls, LayoutParams(-1, -1))
        addView(hud, LayoutParams(-2, ctx.dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(72) })
        addView(unlock, LayoutParams(ctx.dp(52), ctx.dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = ctx.dp(24) })
        unlock.visibility = GONE
        addView(settingsPanel.panel, LayoutParams(ctx.dp(340), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) })

        // Keep the rows clear of the notch / gesture bars.
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
        speedChip.setOnClickListener { cycleSpeed(); touch() }
        fit.setOnClickListener { setZoom(!zoomed); touch() }
        pip.setOnClickListener { onPip?.invoke() }
        next.setOnClickListener { onNext() }
        seek.onScrubStart = { touch() }
        seek.onScrubMove = { elapsed.text = PlayerActivity.formatTime(it); showHud(null, PlayerActivity.formatTime(it)); touch() }
        seek.onSeek = { player?.seekTo(it); hideHudSoon(); touch() }
        for (b in listOf(play, rew, ffwd, back, subs, tune, lock, fit, pip, unlock)) b.tint(Color.WHITE)
        seek.tint(Color.WHITE)
        controls.visibility = GONE
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

    fun show() {
        if (locked) { flashUnlock(); return }
        if (!visible) {
            controls.visibility = VISIBLE
            controls.alpha = 0f
            controls.animate().alpha(1f).setDuration(160).start()
            refresh()
            main.removeCallbacks(tick); main.post(tick)
        }
        touch()
    }

    fun hide() {
        closePanel()
        controls.animate().alpha(0f).setDuration(160).withEndAction { controls.visibility = GONE }.start()
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
    }

    /** Hide at once (picture-in-picture, the Up next card). */
    fun hideNow() {
        closePanel()
        controls.animate().cancel()
        controls.visibility = GONE
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
        nextLabel()?.let { next.visibility = VISIBLE; (next.getChildAt(next.childCount - 1) as TextView).text = it }
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

    private val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    private fun cycleSpeed() {
        val p = player ?: return
        val now = p.playbackParameters.speed
        val i = speeds.indexOfFirst { abs(it - now) < 0.01f }
        p.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
        refresh()
    }

    private fun setZoom(on: Boolean) {
        zoomed = on
        playerView.resizeMode = if (on) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        fit.contentDescription = if (on) "Fit to screen" else "Zoom to fill"
        showHud(R.drawable.ic_pl_fit, if (on) "Zoom to fill" else "Fit to screen")
        hideHudSoon()
    }

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
        (speedChip.getChildAt(speedChip.childCount - 1) as TextView).text = if (s % 1f == 0f) "${s.toInt()}×" else "${s}×"
        if (d > 0) {
            val left = ((d - p.currentPosition) / s).toLong().coerceAtLeast(0)
            metaView.text = "Ends at " + android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(System.currentTimeMillis() + left))
        }
        val nl = nextLabel()
        next.visibility = if (nl != null) VISIBLE else GONE
        if (nl != null) (next.getChildAt(next.childCount - 1) as TextView).text = nl
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
            if (!p.isPlaying) return
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
        setTextColor(Color.WHITE)
        fontFeatureSettings = "tnum"
        setShadowLayer(ctx.dpf(4f), 0f, 0f, 0x66000000)
    }

    /** A glass pill: optional icon + label. */
    private fun chip(label: String, icon: Int?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(0x33FFFFFF)
            cornerRadius = ctx.dpf(999f)
            setStroke(ctx.dp(1), 0x26FFFFFF)
        }
        addView(ImageView(ctx).apply {
            if (icon != null) setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = if (icon != null) VISIBLE else GONE
        }, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginEnd = ctx.dp(6) })
        addView(TextView(ctx).apply {
            text = label
            typeface = font(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
        })
    }

    private fun bubble() = TextView(ctx).apply {
        typeface = font(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        background = GradientDrawable().apply { setColor(0x59000000); cornerRadius = ctx.dpf(999f) }
        visibility = GONE
    }

    private fun View.fade() {
        if (visibility != VISIBLE) return
        animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
    }
}
