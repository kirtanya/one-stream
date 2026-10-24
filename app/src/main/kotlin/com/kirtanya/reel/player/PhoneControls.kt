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
import com.kirtanya.reel.core.player.PhoneLayout
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
 * Edge layout (Settings panel → Layout, landscape only; [layoutMode]): the same controls moved to
 * the screen's edges around the camera hole, see [EdgeFrame]. Portrait always shows the band.
 *
 * Gestures on the picture: tap shows / hides; double-tap the left or right third skips one step (taps
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
    /** "Send to TV" (continue on the paired TV at this position); null hides the button. */
    private val onSendToTv: (() -> Unit)? = null,
    /** Whether this playback can be sent (a TMDB title); checked on every refresh. */
    private val canSendToTv: () -> Boolean = { false },
) : FrameLayout(activity) {
    private val ctx: Context = activity
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null

    private val controls = FrameLayout(ctx)
    /** Classic: the bottom band (with its shade); Edge: [edge]. Only one shows. */
    private val classic = FrameLayout(ctx)
    private val edge = EdgeFrame()
    private val info = LinearLayout(ctx)
    private lateinit var row: BandRow
    private val times = LinearLayout(ctx)
    private val band = LinearLayout(ctx)
    private lateinit var seekBandParams: LinearLayout.LayoutParams
    /** The bottom band (its height decides how far captions move up while it shows). */
    private var bandView: View? = null
    private val titleView = text(CAPTION_SP, medium = true)
    private val metaView = text(CAPTION_SP)
    private val back = RoundButton(ctx, R.drawable.ic_pl_back, "Back", BUTTON_DP, ICON_DP)
    private val rew = SkipButton(ctx, forward = false, seconds = 10, sizeDp = BUTTON_DP, iconDp = SKIP_ICON_DP, font = font(true))
    private val play = RoundButton(ctx, R.drawable.ic_pl_pause, "Play or pause", BUTTON_DP, ICON_DP)
    private val ffwd = SkipButton(ctx, forward = true, seconds = 10, sizeDp = BUTTON_DP, iconDp = SKIP_ICON_DP, font = font(true))
    private val next = RoundButton(ctx, R.drawable.ic_pl_next, "Next episode", BUTTON_DP, ICON_DP).apply { visibility = GONE }
    private val toTv = RoundButton(ctx, R.drawable.ic_pl_tv, "Send to TV", BUTTON_DP, ICON_DP).apply { visibility = GONE }
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

    /** The panel's picture rows (Picture, Crop bars, Cinema mode). */
    fun setToggles(list: List<PanelRow>) { settingsPanel.toggles = list }

    /** Crop bars' adjust bar (the panel hands over to it so the picture stays in view). */
    private val cropHud = CropHud(ctx, font) { closeCrop() }.apply { visibility = GONE }
    val cropOpen get() = cropHud.visibility == VISIBLE

    /** The panel's section rows (subtitle delay…, night mode…, sleep timer) and its choice callback. */
    fun setRows(subtitle: List<PanelRow>, audio: List<PanelRow>, playback: List<PanelRow>, onChoice: (String) -> Unit) {
        settingsPanel.subtitleRows = subtitle
        settingsPanel.audioRows = audio
        settingsPanel.playbackRows = playback
        settingsPanel.onChoice = onChoice
    }

    fun refreshPanel() = settingsPanel.refresh()

    /** One skip (Settings → Playback → Skip by): the ± buttons, their numbers and a double-tap. */
    var seekStepMs = 10_000L
        set(v) {
            field = v
            rew.setSeconds((v / 1000).toInt())
            ffwd.setSeconds((v / 1000).toInt())
        }

    private var locked = false
    private var zoomed = false
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val visible get() = controls.visibility == VISIBLE
    val panelOpen get() = settingsPanel.open

    init {
        clipChildren = false
        controls.clipChildren = false
        // Like the TV strip: white marks with a small tight shadow, no scrim over the picture. Only a
        // low shade from the bottom edge up to the band's top (clear there), because small white
        // text on a bright picture fails the contrast rule without it (PlayerColors.SHADE_BAND).
        val shade = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(PlayerColors.SHADE_BAND, PlayerColors.SHADE_BAND_MID, PlayerColors.SHADE_CLEAR))
        }
        classic.clipChildren = false
        classic.addView(shade, LayoutParams(-1, 0, Gravity.BOTTOM))
        val safe = FrameLayout(ctx).apply { clipChildren = false }
        classic.addView(safe, LayoutParams(-1, -1))
        controls.addView(classic, LayoutParams(-1, -1))
        edge.visibility = GONE
        controls.addView(edge, LayoutParams(-1, -1))

        // The title block: title, then "Ends at", both caption size; the row hides it where it doesn't fit.
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER_VERTICAL
        titleView.text = title
        for (t in listOf(titleView, metaView)) {
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        row = BandRow(ctx, left = listOf(back, rew, play, ffwd), middle = info, right = rightButtons)

        // Elapsed above the seek bar's start, remaining above its end (as on the TV).
        times.orientation = LinearLayout.HORIZONTAL
        times.gravity = Gravity.BOTTOM
        times.addView(elapsed, LinearLayout.LayoutParams(-2, -2))
        times.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(-2, -2))

        // The band: row, times, seek bar. The seek bar's 48 dp touch area reaches up behind the times
        // (they take no touches) so its line sits just under them; the band's top margin keeps that
        // touch area off the buttons above.
        band.orientation = LinearLayout.VERTICAL
        band.clipChildren = false
        bandView = band
        band.addView(row, LinearLayout.LayoutParams(-1, -2).apply { marginStart = ctx.dp(ROW_EDGE_DP); marginEnd = ctx.dp(ROW_EDGE_DP) })
        band.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4); marginStart = ctx.dp(EDGE_DP); marginEnd = ctx.dp(EDGE_DP) })
        seek.line = true
        seekBandParams = LinearLayout.LayoutParams(-1, ctx.dp(BUTTON_DP)).apply {
            topMargin = -ctx.dp(SEEK_UNDER_TIMES_DP)
            marginStart = ctx.dp(EDGE_DP); marginEnd = ctx.dp(EDGE_DP)
        }
        band.addView(seek, seekBandParams)
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
        addView(cropHud, LayoutParams(ctx.dp(CROP_DP), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) })
        settingsPanel.onCrop = { openCrop(it) }

        // Keep the band clear of the notch / gesture bars.
        ViewCompat.setOnApplyWindowInsetsListener(safe) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        // Edge: the same safe area, plus which short side the camera hole is on.
        ViewCompat.setOnApplyWindowInsetsListener(edge) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (cutoutOverride == null) {
                val side = PhoneLayout.cutoutSide(cut.left, cut.right)
                // The camera hole itself (window coordinates): the one on that short side.
                val rects = insets.displayCutout?.boundingRects.orEmpty()
                val w = edge.rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val hole = rects.firstOrNull { if (side == PhoneLayout.Side.LEFT) it.centerX() < w / 2 else it.centerX() >= w / 2 }
                    ?.takeIf { (if (side == PhoneLayout.Side.LEFT) cut.left else cut.right) > 0 }
                edge.setSafe(i.left, i.top, i.right, i.bottom, side, hole)
            }
            insets
        }

        back.setOnClickListener { onBack() }
        rew.setOnClickListener { seekBy(-seekStepMs); touch() }
        ffwd.setOnClickListener { seekBy(seekStepMs); touch() }
        play.setOnClickListener { togglePlay(); touch() }
        subs.setOnClickListener { openPanel("sub") }
        tune.setOnClickListener { openPanel(null) }
        lock.setOnClickListener { setLocked(true) }
        unlock.setOnClickListener { setLocked(false) }
        next.setOnClickListener { onNext() }
        toTv.setOnClickListener { onSendToTv?.invoke() }
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
        val cp = cropHud.layoutParams as LayoutParams
        cp.width = minOf(ctx.dp(CROP_DP), MeasureSpec.getSize(widthMeasureSpec) - cp.leftMargin - cp.rightMargin)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ---------------------------------------------------------------- layout (Classic / Edge)

    /** The user's pick (Settings panel → Layout); Edge shows only while the screen is landscape. */
    var layoutMode: PhoneLayout = PhoneLayout.CLASSIC
        set(v) { field = v; applyLayout() }

    /** What's laid out now: init builds Classic; [applyLayout] moves everything to the edges when asked. */
    private var mounted = PhoneLayout.CLASSIC

    /** Renders only: a cutout [px] deep on [side] with the camera [hole] in it (Robolectric has no display cutout). */
    private var cutoutOverride: Pair<PhoneLayout.Side, Int>? = null

    internal fun previewCutout(side: PhoneLayout.Side, px: Int, hole: android.graphics.Rect) {
        cutoutOverride = side to px
        edge.setSafe(if (side == PhoneLayout.Side.LEFT) px else 0, 0, if (side == PhoneLayout.Side.RIGHT) px else 0, 0, side, hole)
    }

    private val rightButtons get() = listOf(next, toTv, lock, subs, tune)

    private fun landscape() =
        if (width > 0 && height > 0) width > height
        else ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Turning the phone can swap Classic ↔ Edge; re-parent after this layout pass, not inside it.
        post { applyLayout() }
    }

    /**
     * Moves the one set of buttons, captions and seek bar into the band (Classic) or the edge frame
     * (Edge). One set, so listeners, icons and refresh never need to know which layout shows.
     */
    private fun applyLayout() {
        val want = PhoneLayout.effective(layoutMode, landscape())
        if (want == mounted) return
        mounted = want
        val moving = listOf(back, rew, play, ffwd, info, elapsed, remaining, seek) + rightButtons
        for (v in moving) (v.parent as? ViewGroup)?.removeView(v)
        if (want == PhoneLayout.EDGE) {
            seek.line = false
            seek.edge = true
            edge.mount()
            classic.visibility = GONE
            edge.visibility = VISIBLE
        } else {
            seek.edge = false
            seek.line = true
            row.mount()
            times.addView(elapsed, 0, LinearLayout.LayoutParams(-2, -2))
            times.addView(remaining, LinearLayout.LayoutParams(-2, -2))
            band.addView(seek, seekBandParams)
            edge.visibility = GONE
            classic.visibility = VISIBLE
        }
        val centred = want == PhoneLayout.EDGE
        for (t in listOf(titleView, metaView)) t.gravity = if (centred) Gravity.CENTER_HORIZONTAL else Gravity.START
        placeBubbles()
        if (visible) post { onShown?.invoke(true) }
    }

    /** The double-tap bubbles sit clear of the edge columns (Edge) or 56 dp in (Classic). */
    private fun placeBubbles() {
        val inset = if (mounted == PhoneLayout.EDGE) edge.columnReach() else 0
        (leftBubble.layoutParams as? LayoutParams)?.let { it.marginStart = maxOf(ctx.dp(56), inset); leftBubble.layoutParams = it }
        (rightBubble.layoutParams as? LayoutParams)?.let { it.marginEnd = maxOf(ctx.dp(56), inset); rightBubble.layoutParams = it }
    }

    /** White text and icons, each with the TV strip's small tight shadow (never a halo). */
    private fun applyLook() {
        val c = PlayerColors.FOREGROUND
        for (t in listOf(titleView, metaView, elapsed, remaining)) {
            t.setTextColor(if (t === metaView) PlayerColors.SECONDARY else c)
            t.setShadowLayer(ctx.dpf(1.5f), 0f, ctx.dpf(1f), PlayerColors.CONTROL_SHADOW)
        }
        for (b in listOf(back, rew, play, ffwd, next, toTv, lock, subs, tune)) {
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
        if (mounted == PhoneLayout.EDGE) return if (visible) edge.coveredBottom else 0
        val b = bandView ?: return 0
        if (!visible || b.height == 0) return 0
        val loc = IntArray(2).also { b.getLocationInWindow(it) }
        val me = IntArray(2).also { getLocationInWindow(it) }
        return (me[1] + height) - loc[1]
    }

    fun show() {
        if (locked) { flashUnlock(); return }
        if (cropOpen) return
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
        cropHud.visibility = GONE
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
    }

    fun touch() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 3_500)
    }

    /** System Back: closes the settings panel first. True when it did. */
    fun handleBack(): Boolean {
        if (cropOpen) { closeCrop(); return true }
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

    /** Crop bars: the panel and the band step aside for the adjust bar; the picture changes live above it. */
    private fun openCrop(r: PanelCrop) {
        settingsPanel.hide()
        controls.animate().cancel()
        controls.visibility = GONE
        onShown?.invoke(false)
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
        cropHud.show(r)
    }

    private fun closeCrop() {
        if (!cropOpen) return
        cropHud.visibility = GONE
        show()
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
        toTv.visibility = if (onSendToTv != null && canSendToTv()) VISIBLE else GONE
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
            if (cropOpen) { closeCrop(); return true }
            // A tap while the skip bubble is up adds another step on that side.
            val side = when { e.x < width / 3f -> -1; e.x > width * 2 / 3f -> 1; else -> 0 }
            if (tapSeekSide != 0 && side == tapSeekSide) { tapSeek(side); return true }
            if (visible) hide() else show()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked || cropOpen) return true
            val third = width / 3f
            when {
                e.x < third -> tapSeek(-1)
                e.x > width - third -> tapSeek(1)
                else -> { togglePlay(); if (visible) touch() }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked || cropOpen || drag != Drag.NONE) return
            val p = player ?: return
            // While playing or waiting for data (not when the user paused).
            if (!p.playWhenReady) return
            fastHeld = true
            speedBeforeFast = p.playbackParameters.speed
            p.setPlaybackSpeed(2f)
            showHud(R.drawable.ic_pl_fast, "2× speed")
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (locked || cropOpen || fastHeld || e1 == null) return false
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
        tapSeekTotal += (seekStepMs / 1000).toInt()
        seekBy(side * seekStepMs)
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
            mount()
        }

        /** (Back) into the row, after the Edge layout had its views. */
        fun mount() { for (v in left + middle + right) if (v.parent == null) addView(v) }

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

    /**
     * Edge layout (landscape), built around the camera hole on one short side:
     *  - that side, in the cutout strip itself (the status bar in portrait): one line through the
     *    camera hole — back at the top, the hole, then back 10, play / pause, forward 10 under it
     *    (PhoneLayout.holeColumn). Too short: back 10 above the hole, forward 10 below, play beside it
     *    (flankHole). No cutout: the same line on the left edge, the transport centred;
     *  - the other side: every other button in one column, centred too;
     *  - the title and "Ends at" centred at the top, between back and that column, where they fit;
     *  - the seek bar a hairline along the very bottom edge, end to end (like the TV), with a 48 dp
     *    touch area above it; elapsed / remaining just above its ends.
     * Low shades (the band's tokens) only along the edges that carry text or buttons.
     */
    private inner class EdgeFrame : ViewGroup(ctx) {
        private val safe = android.graphics.Rect()
        private var side = PhoneLayout.Side.LEFT
        /** The camera hole's bounding rect in window coordinates (null: no cutout on the side). */
        private var hole: android.graphics.Rect? = null
        private val at = IntArray(2)
        private fun shade(o: GradientDrawable.Orientation) = View(ctx).apply {
            background = GradientDrawable(o, intArrayOf(PlayerColors.SHADE_BAND, PlayerColors.SHADE_BAND_MID, PlayerColors.SHADE_CLEAR))
        }
        private val topShade = shade(GradientDrawable.Orientation.TOP_BOTTOM)
        private val bottomShade = shade(GradientDrawable.Orientation.BOTTOM_TOP)
        private val leftShade = shade(GradientDrawable.Orientation.LEFT_RIGHT)
        private val rightShade = shade(GradientDrawable.Orientation.RIGHT_LEFT)
        private val btn = ctx.dp(BUTTON_DP)
        private var titleWidth = 0

        /** How much of the bottom the seek bar and times cover (px), for the captions. */
        var coveredBottom = 0; private set

        init {
            clipChildren = false
            for (s in listOf(topShade, bottomShade, leftShade, rightShade)) addView(s)
        }

        fun setSafe(left: Int, top: Int, right: Int, bottom: Int, cutoutSide: PhoneLayout.Side, cameraHole: android.graphics.Rect?) {
            safe.set(left, top, right, bottom)
            side = cutoutSide
            hole = cameraHole?.let { android.graphics.Rect(it) }
            requestLayout()
            placeBubbles()
        }

        /** The seek bar first, so the times (no touches) draw over its touch area, not under it. */
        fun mount() {
            for (v in listOf(seek, back, rew, play, ffwd, info, elapsed, remaining) + rightButtons) if (v.parent == null) addView(v)
        }

        /** How far in from either short edge the columns reach (px), for the double-tap bubbles. */
        fun columnReach() = maxOf(safe.left, safe.right) + ctx.dp(ROW_EDGE_DP) + btn + ctx.dp(8)

        private fun stripLeft() = side == PhoneLayout.Side.LEFT

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val exact = MeasureSpec.makeMeasureSpec(btn, MeasureSpec.EXACTLY)
            for (v in listOf(back, rew, play, ffwd) + rightButtons) if (v.parent === this && v.visibility != GONE) v.measure(exact, exact)
            if (seek.parent === this) seek.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), exact)
            val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (t in listOf(elapsed, remaining)) if (t.parent === this) t.measure(wrap, wrap)
            // The title: centred between back and the far column, never under either.
            val free = w - 2 * (maxOf(safe.left, safe.right) + ctx.dp(ROW_EDGE_DP) + btn + ctx.dp(TITLE_GAP_DP))
            titleWidth = minOf(free, ctx.dp(EDGE_TITLE_MAX_DP)).takeIf { it >= ctx.dp(TITLE_MIN_DP) } ?: 0
            if (info.parent === this) info.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(btn, MeasureSpec.AT_MOST))
            // Shades are measured in onLayout (their sizes follow the controls' places).
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val gap = ctx.dp(8)
            val edgeIn = ctx.dp(ROW_EDGE_DP)
            val stripX = if (stripLeft()) safe.left + edgeIn else w - safe.right - edgeIn - btn
            val farX = if (stripLeft()) w - safe.right - edgeIn - btn else safe.left + edgeIn
            val top = safe.top + gap
            val bottom = h - safe.bottom

            // Bottom edge: the seek bar's touch area, its line on the very edge; times just above.
            val seekTop = bottom - btn
            seek.layout(0, seekTop, w, bottom)
            val timesBottom = bottom - ctx.dp(EDGE_TIMES_LIFT_DP)
            val timesTop = timesBottom - maxOf(elapsed.measuredHeight, remaining.measuredHeight)
            getLocationInWindow(at)
            val hw = hole?.let { android.graphics.Rect(it.left - at[0], it.top - at[1], it.right - at[0], it.bottom - at[1]) }
            // The camera line (below) reaches the bottom: the time on that side starts past it.
            val lineX = hw?.let { (it.centerX() - btn / 2).coerceIn(0, maxOf(0, w - btn)) }
            var ex = safe.left + ctx.dp(EDGE_DP)
            var rx = w - safe.right - ctx.dp(EDGE_DP)
            if (lineX != null) { if (stripLeft()) ex = maxOf(ex, lineX + btn + gap) else rx = minOf(rx, lineX - gap) }
            elapsed.layout(ex, timesBottom - elapsed.measuredHeight, ex + elapsed.measuredWidth, timesBottom)
            remaining.layout(rx - remaining.measuredWidth, timesBottom - remaining.measuredHeight, rx, timesBottom)
            coveredBottom = h - timesTop

            // Neither column may reach into the seek bar's touch area or the times.
            val limit = minOf(seekTop, timesTop) - ctx.dp(4)
            val centre = h / 2
            val transport = listOf(rew, play, ffwd)
            val tops = PhoneLayout.stack(transport.size, btn, gap, top + btn + gap, limit, centre)
            // 1. One line through the hole: back, the hole, back 10 / play / forward 10. The line may
            //    run down over the seek bar's touch area (buttons on top take their own 48 dp, the bar
            //    keeps the rest), stopping short of its hairline; the time beside it moved over above.
            val line = hw?.let {
                PhoneLayout.holeColumn(it.left, it.top, it.right, it.bottom, transport.size, btn, gap,
                    minX = 0, maxX = w, top = top, bottom = bottom - ctx.dp(EDGE_LINE_CLEAR_DP))
            }
            // 2. Fallback (short screen): back 10 above the hole, forward 10 below, play beside it.
            val flank = if (line != null) null else hw?.let {
                PhoneLayout.flankHole(it.left, it.top, it.right, it.bottom, btn, gap,
                    minX = 0, maxX = w, playTop = tops[1], playBottom = tops[1] + btn, top = top + btn + gap, bottom = limit)
            }
            when {
                line != null -> {
                    back.layout(line.x, line.backTop, line.x + btn, line.backTop + btn)
                    transport.forEachIndexed { i, v -> v.layout(line.x, line.tops[i], line.x + btn, line.tops[i] + btn) }
                }
                flank != null -> {
                    back.layout(stripX, top, stripX + btn, top + btn)
                    play.layout(stripX, tops[1], stripX + btn, tops[1] + btn)
                    rew.layout(flank.x, flank.aboveTop, flank.x + btn, flank.aboveTop + btn)
                    ffwd.layout(flank.x, flank.belowTop, flank.x + btn, flank.belowTop + btn)
                }
                // 3. No hole (no cutout): the same single line on the left edge, the transport centred.
                else -> {
                    back.layout(stripX, top, stripX + btn, top + btn)
                    transport.forEachIndexed { i, v -> v.layout(stripX, tops[i], stripX + btn, tops[i] + btn) }
                }
            }
            val far = rightButtons.filter { it.visibility != GONE }
            val farTops = PhoneLayout.stack(far.size, btn, gap, top, limit, centre)
            far.forEachIndexed { i, v -> v.layout(farX, farTops[i], farX + btn, farTops[i] + btn) }

            val tx = (w - titleWidth) / 2
            val ty = top + (btn - info.measuredHeight) / 2
            info.layout(tx, ty, tx + titleWidth, ty + info.measuredHeight)

            // Shades: a low one behind each edge's controls, clear where the picture starts.
            fun put(v: View, x0: Int, y0: Int, x1: Int, y1: Int) {
                v.measure(MeasureSpec.makeMeasureSpec(x1 - x0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(y1 - y0, MeasureSpec.EXACTLY))
                v.layout(x0, y0, x1, y1)
            }
            put(topShade, 0, 0, w, top + btn + ctx.dp(SHADE_REACH_DP))
            put(bottomShade, 0, timesTop - ctx.dp(SHADE_REACH_DP), w, h)
            val reachL = (safe.left + edgeIn + btn) * 2
            val reachR = (safe.right + edgeIn + btn) * 2
            put(leftShade, 0, 0, reachL, h)
            put(rightShade, w - reachR, 0, w, h)
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
        /** Edge: the centred title's widest. */
        const val EDGE_TITLE_MAX_DP = 360
        /** Edge: the times' baseline area sits this far above the bottom edge (clear of the thumb's halo). */
        const val EDGE_TIMES_LIFT_DP = 10
        /** Edge: the camera line's last button stops this far above the seek bar's hairline. */
        const val EDGE_LINE_CLEAR_DP = 8
        /** Edge: each shade runs this far past the controls it sits behind before it's clear. */
        const val SHADE_REACH_DP = 16
        const val PANEL_DP = 340
        /** Crop bars' adjust bar: wide enough for a fine slider, narrower than the landscape screen so the picture shows beside it. */
        const val CROP_DP = 420
    }
}
