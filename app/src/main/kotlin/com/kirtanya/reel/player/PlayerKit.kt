package com.kirtanya.reel.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.kirtanya.reel.R
import java.util.Locale

/*
 * The One Stream player kit, shared by the TV strip and the phone controls: rounded filled icons
 * (res/drawable/ic_pl_*), round buttons, skip buttons with the seconds inside the arrow, a
 * seek bar with a time bubble, and the settings panel.
 */

internal fun Context.dpf(v: Float) = v * resources.displayMetrics.density
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
internal fun withAlpha(c: Int, a: Int) = PlayerColors.withAlpha(c, a)

/**
 * A round icon button: transparent, a ring when focused (outline only: no fill, no scale, no glow).
 * [sizeDp] is the drawn disc; [touchDp] the view the caller lays out (48 dp on the phone, CLAUDE.md
 * "Design rules"): a larger touch area keeps the disc at [sizeDp] in its centre.
 */
@SuppressLint("ViewConstructor")
internal open class RoundButton(context: Context, icon: Int, label: String, private val sizeDp: Int, iconDp: Int, touchDp: Int = sizeDp) : FrameLayout(context) {
    val image = ImageView(context).apply { setImageResource(icon) }
    private var color = PlayerColors.FOREGROUND
    private val inset = context.dp((touchDp - sizeDp).coerceAtLeast(0)) / 2

    init {
        contentDescription = label
        isFocusable = true
        isClickable = true
        addView(image, LayoutParams(context.dp(iconDp), context.dp(iconDp), Gravity.CENTER))
        tint(PlayerColors.FOREGROUND)
    }

    open fun tint(c: Int) {
        color = c
        image.imageTintList = ColorStateList.valueOf(c)
        val states = StateListDrawable().apply {
            val on = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(context.dp(2), c) }
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(c, 0x24)) })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
        }
        background = if (inset > 0) android.graphics.drawable.InsetDrawable(states, inset) else states
    }

    fun setIcon(res: Int) {
        image.setImageResource(res)
        shadow?.setImageResource(res)
    }

    private var shadow: ImageView? = null

    /**
     * A small shadow just under the icon (TV strip on video): the same icon in [color], [dyDp] lower,
     * blurred by [blurDp] — tight to the icon's shape, no halo around the button.
     */
    fun addShadow(color: Int, blurDp: Float = 1.5f, dyDp: Float = 1f) {
        if (shadow != null) return
        val lp = image.layoutParams as LayoutParams
        shadow = ImageView(context).apply {
            setImageDrawable(image.drawable?.constantState?.newDrawable()?.mutate())
            imageTintList = ColorStateList.valueOf(color)
            translationY = context.dpf(dyDp)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(context.dpf(blurDp), context.dpf(blurDp), android.graphics.Shader.TileMode.DECAL))
            }
        }
        addView(shadow, 0, LayoutParams(lp.width, lp.height, lp.gravity))
    }
}

/**
 * "Next · S1 E4": a very small icon and very small text just above the video title, for an episode with a
 * next one. Phone: the view is a full 48 dp tall (touch rule, CLAUDE.md design rule 1) with the marks sitting
 * at the bottom of it, so they read as directly above the title and the box grows upward into free space.
 * TV: the marks on a small dark glass pill, with a ring when the remote is on it (outline only).
 */
@SuppressLint("ViewConstructor")
internal class NextChip(context: Context, font: Typeface?, private val phone: Boolean) : LinearLayout(context) {
    private val icon = ImageView(context).apply { setImageResource(R.drawable.ic_pl_next) }
    private val label = TextView(context).apply {
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (phone) 11f else 12f)
        maxLines = 1
        includeFontPadding = false
        setTextColor(PlayerColors.FOREGROUND)
        setShadowLayer(context.dpf(1.5f), 0f, context.dpf(1f), PlayerColors.CONTROL_SHADOW)
    }

    init {
        orientation = HORIZONTAL
        gravity = if (phone) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        val size = context.dp(if (phone) 12 else 14)
        addView(icon, LayoutParams(size, size).apply { if (phone) bottomMargin = context.dp(1) })
        icon.imageTintList = ColorStateList.valueOf(PlayerColors.FOREGROUND)
        addView(label, LayoutParams(-2, -2).apply { marginStart = context.dp(4) })
        if (phone) {
            minimumHeight = context.dp(48)
            setPadding(0, 0, context.dp(12), context.dp(2))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(PlayerColors.ROW_HIGHLIGHT); cornerRadius = context.dpf(8f) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
        } else {
            setPadding(context.dp(8), context.dp(3), context.dp(10), context.dp(3))
            // The TV strip has no shade behind it, and 12 sp white text on a bright picture fails the contrast rule
            // (the title and times above it are baselined for that): a dark glass pill keeps this one readable.
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(PlayerColors.CHIP_SCRIM); cornerRadius = context.dpf(999f); setStroke(context.dp(2), PlayerColors.FOREGROUND)
                })
                addState(intArrayOf(), GradientDrawable().apply { setColor(PlayerColors.CHIP_SCRIM); cornerRadius = context.dpf(999f) })
            }
        }
    }

    /** "Next · S1 E4", or null to hide. */
    fun set(text: String?) {
        visibility = if (text != null) VISIBLE else GONE
        if (text != null) {
            label.text = text
            contentDescription = if (text.startsWith("Next · ")) "Next episode, " + text.removePrefix("Next · ") else text
        }
    }
}

/** Back / forward N seconds: the circular arrow with the number inside it. */
@SuppressLint("ViewConstructor")
internal class SkipButton(context: Context, private val forward: Boolean, seconds: Int, sizeDp: Int, iconDp: Int, font: Typeface?) :
    RoundButton(context, if (forward) R.drawable.ic_pl_forward else R.drawable.ic_pl_replay, if (forward) "Forward $seconds seconds" else "Back $seconds seconds", sizeDp, iconDp) {
    private val number = TextView(context).apply {
        text = seconds.toString()
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.dpf(iconDp * 0.30f))
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    init {
        addView(number, LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = context.dp((iconDp * 0.1f).toInt()) })
        tint(PlayerColors.FOREGROUND)
    }

    /** The skip step changed (Settings → Playback): the number inside the arrow says it. */
    fun setSeconds(seconds: Int) {
        number.text = seconds.toString()
        contentDescription = (if (forward) "Forward" else "Back") + " $seconds seconds"
    }

    override fun tint(c: Int) {
        super.tint(c)
        @Suppress("SENSELESS_COMPARISON") if (number != null) number.setTextColor(c)
    }

    /** The number inside the arrow gets the same small shadow as the icon. */
    fun shadowNumber(color: Int) = number.setShadowLayer(context.dpf(1.5f), 0f, context.dpf(1f), color)
}

/**
 * Seek bar: rounded track (thicker while focused or dragged), buffered + played, a thumb, and a
 * time bubble above the thumb while scrubbing. Remote: ←/→ scrub with acceleration (10 s, then
 * 30 s, then 60 s steps the longer the key is held), committed 700 ms after the last press or on
 * OK. Touch: drag anywhere on it. The bubble draws outside the view (parents don't clip).
 */
internal class SeekStrip(context: Context, private val font: Typeface?) : View(context) {
    var duration = 0L; set(v) { field = v; invalidate() }
    var position = 0L; set(v) { if (!scrubbing) { field = v; invalidate() } }
    var buffered = 0L; set(v) { field = v; invalidate() }
    var onScrubStart: (() -> Unit)? = null
    var onScrubMove: ((Long) -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    /**
     * TV: a hairline along the very bottom edge, end to end — no track, only the played part (like
     * a progress border). Focused, it thickens and shows a thumb on the current spot; scrubbing
     * moves it with the time bubble above.
     */
    var edge = false; set(v) { field = v; invalidate() }

    /**
     * Phone: the same hairline as [edge], but across the middle of its (48 dp) touch area, so a
     * finger anywhere on that area grabs it; thumb, halo and track show while it's touched.
     */
    var line = false; set(v) { field = v; invalidate() }

    var scrubbing = false; private set
    private var scrubPos = 0L
    private var color = PlayerColors.FOREGROUND
    private var active = 0f
    private var activeAnim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = context.dpf(13f); textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val commit = Runnable { finish(cancel = false) }
    private var keySteps = 0

    init {
        isFocusable = true
        setOnFocusChangeListener { _, f -> setActive(f || scrubbing); if (!f && scrubbing) finish(cancel = false) }
    }

    fun tint(c: Int) { color = c; invalidate() }

    private fun setActive(on: Boolean) {
        activeAnim?.cancel()
        activeAnim = ValueAnimator.ofFloat(active, if (on) 1f else 0f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { active = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun shown() = if (scrubbing) scrubPos else position

    override fun onDraw(canvas: Canvas) {
        if (edge || line) { drawEdge(canvas); return }
        val pad = context.dpf(9f)
        val w = width - 2 * pad
        val cy = height / 2f
        val h = context.dpf(4f) + context.dpf(3f) * active
        val d = duration.coerceAtLeast(1)
        fun x(ms: Long) = pad + w * (ms.coerceIn(0, d).toFloat() / d)
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        paint.color = withAlpha(color, 0x3D); canvas.drawRoundRect(rect, h, h, paint)
        if (duration > 0) {
            rect.right = x(buffered); paint.color = withAlpha(color, 0x73); canvas.drawRoundRect(rect, h, h, paint)
            rect.right = x(shown()); paint.color = color; canvas.drawRoundRect(rect, h, h, paint)
            val r = context.dpf(5f) + context.dpf(4f) * active
            paint.color = color
            canvas.drawCircle(x(shown()), cy, r, paint)
            if (scrubbing) {
                // Time bubble above the thumb, in the foreground colour with the text cut out.
                val label = PlayerActivity.formatTime(scrubPos)
                val tw = text.measureText(label) + context.dpf(20f)
                val bh = context.dpf(26f)
                val bx = x(scrubPos).coerceIn(tw / 2, width - tw / 2)
                rect.set(bx - tw / 2, cy - r - context.dpf(10f) - bh, bx + tw / 2, cy - r - context.dpf(10f))
                canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
                text.color = if (TvControls.luminance(color) > 0.5f) TvControls.DARK else PlayerColors.FOREGROUND
                canvas.drawText(label, bx, rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    private fun drawEdge(canvas: Canvas) {
        if (duration <= 0) return
        val h = context.dpf(3f) + context.dpf(3f) * active
        // Edge: on the bottom edge, growing upwards. Line: centred, growing both ways.
        val bottom = if (line) height / 2f + h / 2 else height.toFloat()
        val cy = bottom - h / 2
        fun x(ms: Long) = width * (ms.coerceIn(0, duration).toFloat() / duration)
        // The rest of the line stays clear; while focused, a faint track and the buffered part show where it can go.
        if (active > 0f) {
            paint.color = withAlpha(color, (0x33 * active).toInt())
            rect.set(0f, bottom - h, width.toFloat(), bottom); canvas.drawRect(rect, paint)
            paint.color = withAlpha(color, (0x55 * active).toInt())
            rect.right = x(buffered); canvas.drawRect(rect, paint)
        }
        paint.color = color
        rect.set(0f, bottom - h, x(shown()), bottom)
        canvas.drawRect(rect, paint)
        if (active > 0f) {
            // The current spot: a soft halo and a thumb.
            val r = context.dpf(7f) * active
            paint.color = withAlpha(color, (0x40 * active).toInt())
            canvas.drawCircle(x(shown()), cy, r * 1.9f, paint)
            paint.color = color
            canvas.drawCircle(x(shown()), cy, r, paint)
        }
        if (scrubbing) {
            val label = PlayerActivity.formatTime(scrubPos)
            val tw = text.measureText(label) + context.dpf(20f)
            val bh = context.dpf(26f)
            val bx = x(scrubPos).coerceIn(tw / 2, width - tw / 2)
            val top = cy - context.dpf(20f) - bh
            rect.set(bx - tw / 2, top, bx + tw / 2, top + bh)
            paint.color = color
            canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
            text.color = if (TvControls.luminance(color) > 0.5f) TvControls.DARK else PlayerColors.FOREGROUND
            canvas.drawText(label, bx, rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun begin() {
        if (scrubbing) return
        scrubbing = true
        scrubPos = position
        keySteps = 0
        setActive(true)
        onScrubStart?.invoke()
    }

    private fun move(to: Long) {
        scrubPos = to.coerceIn(0, duration.coerceAtLeast(0))
        onScrubMove?.invoke(scrubPos)
        invalidate()
    }

    private fun finish(cancel: Boolean) {
        removeCallbacks(commit)
        if (!scrubbing) return
        scrubbing = false
        if (!cancel) { position = scrubPos; onSeek?.invoke(scrubPos) }
        setActive(isFocused)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (duration <= 0) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                begin()
                keySteps++
                val step = when { keySteps > 12 -> 60_000L; keySteps > 4 -> 30_000L; else -> 10_000L }
                move(scrubPos + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                removeCallbacks(commit)
                postDelayed(commit, 700)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (scrubbing) { finish(cancel = false); return true }
            KeyEvent.KEYCODE_BACK -> if (scrubbing) { finish(cancel = true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (duration <= 0) return false
        val pad = if (edge || line) 0f else context.dpf(9f)
        fun at(x: Float) = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f).let { (it * duration).toLong() }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); begin(); move(at(e.x)) }
            MotionEvent.ACTION_MOVE -> move(at(e.x))
            MotionEvent.ACTION_UP -> finish(cancel = false)
            MotionEvent.ACTION_CANCEL -> finish(cancel = true)
        }
        return true
    }
}

/**
 * A settings-panel row over [values] (index [current]): each OK / tap steps to the next, or with
 * [asList] it opens the values as a list to pick from (for more than a few).
 */
internal class PanelToggle(
    val icon: Int,
    val label: String,
    val values: List<String>,
    val current: () -> Int,
    val asList: Boolean = false,
    /** What the row says as its value when that isn't one of [values] (a custom subtitle style); null = the current value. */
    val display: (() -> String)? = null,
    val set: (Int) -> Unit,
) : PanelRow

/**
 * Crop bars (black bars baked into the picture): the share cut off the top and the bottom, a slider
 * with Auto (looks at the picture: [auto] reports a line to show, again with the result) and Reset.
 */
internal class PanelCrop(
    val icon: Int,
    val label: String,
    val current: () -> Float,
    val set: (Float) -> Unit,
    val auto: (report: (String) -> Unit) -> Unit,
) : PanelRow

/** A row of the settings panel: a toggle / list ([PanelToggle]), an action ([PanelExtra]), a delay slider ([PanelSlider]) or Crop bars ([PanelCrop]). */
internal sealed interface PanelRow

/** A delay row: opens a slider over [delay]'s range (the remote's ←/→ or a finger), with Reset. */
internal class PanelSlider(
    val icon: Int,
    val label: String,
    val delay: com.kirtanya.reel.core.player.Delay,
    val current: () -> Int,
    val set: (Int) -> Unit,
) : PanelRow

/**
 * An extra row at the end of the settings panel. [action] null = shown but off, with [note] under the
 * label saying why (e.g. Change source for a file without TMDB info).
 */
internal class PanelExtra(val label: String, val note: String? = null, val icon: Int? = null, val action: (() -> Unit)?) : PanelRow

/** The settings panel's "Change source" row: the Sources page, the pick plays from here. */
internal const val CHANGE_SOURCE_ROW = "Change source"

/** The settings panel's "Report a problem" row (docs/MACHINE_FIRST_PLAN.md step 1). */
internal const val REPORT_ROW = "Report a problem"

/** The settings panel's Crop bars row (next to Picture). */
internal const val CROP_ROW = "Crop bars"

/** The phone settings panel's picture-in-picture row (the button left the controls row). */
internal const val PIP_ROW = "Picture in picture"

/**
 * Settings panel (subtitles, audio, speed, extras), two levels: a list of settings, each opening its
 * choices. Built into [panel]; the caller places and shows it.
 */
internal class SettingsPanel(
    private val context: Context,
    private val font: (Boolean) -> Typeface?,
    private val player: () -> Player?,
    private val extras: () -> List<PanelExtra>,
    /** An extra row was chosen (the caller closes the panel). */
    private val onExtra: () -> Unit,
    /** Shortest row: 48 dp on the phone (touch areas), 44 on the TV (remote only). */
    private val minRowDp: Int = 48,
    /** One ←/→ / step of Crop bars: 1 % on the TV (fewer presses), 0.5 % on the phone. */
    private val cropStep: Float = com.kirtanya.reel.core.player.CropBars.PHONE_STEP,
) {
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
    }
    val panel = object : ScrollView(context) {
        /** Also a plain rectangle clip: software canvases (the renders and design checks) ignore the rounded outline clip below. */
        override fun dispatchDraw(canvas: Canvas) {
            canvas.save()
            canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }.apply {
        addView(list)
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(PlayerColors.PANEL)
            cornerRadius = context.dpf(20f)
            setStroke(context.dp(1), PlayerColors.GLASS_STROKE)
        }
        // Its parents let children draw past their bounds (shadows), so a long list scrolled in the
        // panel would show above and below it: clip to the panel's rounded shape.
        clipToOutline = true
        visibility = View.GONE
    }
    private var submenu: String? = null
    val open get() = panel.visibility == View.VISIBLE

    /** The Picture group: Picture, Crop bars, Cinema mode (phone: Layout). */
    var toggles: List<PanelRow> = emptyList()

    /**
     * Phone: Crop bars opens a small bar over the picture instead (the panel would cover what's
     * being cropped); the caller closes the panel. Null = its page in the panel (TV).
     */
    var onCrop: ((PanelCrop) -> Unit)? = null

    /** Rows under Subtitle track (style, delay, search again), Audio track (delay, night mode, voice boost) and Speed (auto-play next, sleep timer). */
    var subtitleRows: List<PanelRow> = emptyList()
    var audioRows: List<PanelRow> = emptyList()
    var playbackRows: List<PanelRow> = emptyList()

    /** The user picked a subtitle / audio track ("sub" / "audio") or a speed ("speed"), after the player has it. */
    var onChoice: ((String) -> Unit)? = null

    /** Rebuilds the list in place (a value changed elsewhere), keeping the focused row. */
    fun refresh() {
        if (!open || submenu != null) return
        val focused = list.findFocus()?.tag as? String
        buildMain()
        (focused?.let { list.findViewWithTag<View>(it) } ?: return).requestFocus()
    }

    fun show(section: String? = null) {
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationY = context.dpf(12f)
        panel.animate().alpha(1f).translationY(0f).setDuration(160).start()
        when (section) {
            "sub" -> showTracks(C.TRACK_TYPE_TEXT)
            "audio" -> showTracks(C.TRACK_TYPE_AUDIO)
            else -> { submenu = null; buildMain() }
        }
        focusSelected()
    }

    fun hide() { panel.visibility = View.GONE }

    /** Back inside the panel: choices → list. False when already on the list. */
    fun back(): Boolean {
        val from = submenu ?: return false
        submenu = null
        buildMain()
        list.findViewWithTag<View>(from)?.requestFocus() ?: focusSelected()
        return true
    }

    /**
     * Four groups in the order people reach for them: Playback (speed, auto-play next, sleep timer), Audio
     * (track, delay, night mode, voice boost), Subtitles (track, style, delay, search again), Picture
     * (mode, crop, cinema, layout); then the extra rows under More. Every row says its current value.
     */
    private fun buildMain() {
        list.removeAllViews()
        val p = player() ?: return
        list.addView(header("Playback"))
        list.addView(row(R.drawable.ic_pl_fast, "Speed", speedLabel(p.playbackParameters.speed), "speed") { showSpeeds() })
        addRows(playbackRows)
        list.addView(header("Audio"))
        val audioTracks = trackOptions(p, C.TRACK_TYPE_AUDIO)
        if (audioTracks.size > 1) list.addView(row(R.drawable.ic_pl_audio, "Audio track", current(p, C.TRACK_TYPE_AUDIO) ?: "Default", "audio") { showTracks(C.TRACK_TYPE_AUDIO) })
        else list.addView(offRow(R.drawable.ic_pl_audio, "Audio track", audioTracks.firstOrNull()?.label ?: "Only one track"))
        addRows(audioRows)
        list.addView(header("Subtitles"))
        list.addView(row(R.drawable.ic_pl_subtitles, "Subtitle track", current(p, C.TRACK_TYPE_TEXT) ?: "Off", "sub") { showTracks(C.TRACK_TYPE_TEXT) })
        addRows(subtitleRows)
        if (toggles.isNotEmpty()) {
            list.addView(header("Picture"))
            addRows(toggles)
        }
        val more = extras()
        if (more.isNotEmpty()) {
            list.addView(header("More"))
            addRows(more)
        }
    }

    private fun addRows(rows: List<PanelRow>) {
        for (r in rows) when (r) {
            is PanelToggle -> list.addView(row(r.icon, r.label, (r.display?.invoke() ?: r.values[r.current()]), r.label) {
                if (r.asList) {
                    showList(r)
                } else {
                    r.set((r.current() + 1) % r.values.size)
                    buildMain()
                    list.findViewWithTag<View>(r.label)?.requestFocus()
                }
            })
            is PanelSlider -> list.addView(row(r.icon, r.label, r.delay.label(r.current()), r.label) { showSlider(r) })
            is PanelCrop -> list.addView(row(r.icon, r.label, com.kirtanya.reel.core.player.CropBars.label(r.current()), r.label) {
                onCrop?.invoke(r) ?: showCrop(r)
            }.also { cropKeys(it, r) })
            is PanelExtra -> {
                val icon = r.icon ?: when (r.label) {
                    REPORT_ROW -> R.drawable.ic_pl_report
                    PIP_ROW -> R.drawable.ic_pl_pip
                    CHANGE_SOURCE_ROW -> R.drawable.ic_pl_source
                    else -> R.drawable.ic_pl_external
                }
                val action = r.action
                list.addView(if (action != null) row(icon, r.label, null, r.label) { onExtra(); action() } else offRow(icon, r.label, r.note))
            }
        }
    }

    /** A delay: the value, a slider (←/→ on the remote, drag on the phone) and Reset. */
    private fun showSlider(s: PanelSlider) {
        submenu = s.label
        list.removeAllViews()
        list.addView(header(s.label))
        val value = text(22f, medium = true).apply {
            text = s.delay.label(s.current())
            gravity = Gravity.CENTER
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        list.addView(value, LinearLayout.LayoutParams(-1, -2))
        val slider = DelaySlider(context, s.delay, s.current()) { ms ->
            s.set(ms)
            value.text = s.delay.label(ms)
        }
        slider.tag = SELECTED
        list.addView(slider, LinearLayout.LayoutParams(-1, context.dp(48)).apply { setMargins(context.dp(6), 0, context.dp(6), 0) })
        val ends = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(context.dp(14), 0, context.dp(14), context.dp(4)) }
        val hint = if (s.delay == com.kirtanya.reel.core.player.Delay.AUDIO) "Sound" else "Subtitles"
        ends.addView(text(12f).apply { text = "$hint earlier"; setTextColor(PlayerColors.PANEL_VALUE) }, LinearLayout.LayoutParams(0, -2, 1f))
        ends.addView(text(12f).apply { text = "$hint later"; setTextColor(PlayerColors.PANEL_VALUE) })
        list.addView(ends)
        list.addView(choice("Reset", false) {
            s.set(0)
            slider.value = 0
            value.text = s.delay.label(0)
        })
        slider.requestFocus()
    }

    /**
     * The remote's ←/→ on the Crop bars row change it right there (the value updates in the row), so
     * the bars can be trimmed without opening its page.
     */
    private fun cropKeys(rowView: LinearLayout, r: PanelCrop) {
        rowView.setOnKeyListener { _, code, event ->
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (dir == 0) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val next = com.kirtanya.reel.core.player.CropBars.step(r.current(), dir, cropStep)
                r.set(next)
                (rowView.getChildAt(rowView.childCount - 1) as? TextView)?.text = com.kirtanya.reel.core.player.CropBars.label(next) + "  ›"
            }
            true
        }
    }

    /** Crop bars' page: the value, the slider (←/→ on the remote), Auto and Reset. The video changes live behind. */
    private fun showCrop(r: PanelCrop) {
        submenu = r.label
        list.removeAllViews()
        list.addView(header(r.label))
        val value = text(22f, medium = true).apply {
            text = com.kirtanya.reel.core.player.CropBars.label(r.current())
            gravity = Gravity.CENTER
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        list.addView(value, LinearLayout.LayoutParams(-1, -2))
        val slider = CropSlider(context, r.current(), cropStep) { p ->
            r.set(p)
            value.text = com.kirtanya.reel.core.player.CropBars.label(p)
        }
        slider.tag = SELECTED
        list.addView(slider, LinearLayout.LayoutParams(-1, context.dp(48)).apply { setMargins(context.dp(6), 0, context.dp(6), 0) })
        val ends = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(context.dp(14), 0, context.dp(14), context.dp(4)) }
        ends.addView(text(12f).apply { text = "Off"; setTextColor(PlayerColors.PANEL_VALUE) }, LinearLayout.LayoutParams(0, -2, 1f))
        ends.addView(text(12f).apply { text = "20 % top and bottom"; setTextColor(PlayerColors.PANEL_VALUE) })
        list.addView(ends)
        val status = note("").apply { visibility = View.GONE }
        fun show(p: Float) {
            slider.value = p
            value.text = com.kirtanya.reel.core.player.CropBars.label(p)
        }
        list.addView(choice("Auto", false) {
            r.auto { message ->
                status.text = message
                status.visibility = View.VISIBLE
                show(r.current())
            }
        })
        list.addView(choice("Reset", false) {
            r.set(0f)
            show(0f)
            status.visibility = View.GONE
        })
        list.addView(status)
        slider.requestFocus()
    }

    private fun showTracks(type: Int) {
        val p = player() ?: return
        submenu = if (type == C.TRACK_TYPE_TEXT) "sub" else "audio"
        list.removeAllViews()
        list.addView(header(if (type == C.TRACK_TYPE_TEXT) "Subtitle track" else "Audio track"))
        val options = trackOptions(p, type)
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || options.none { it.selected }
            list.addView(choice("Off", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                onChoice?.invoke("sub")
                back()
            })
        }
        for (o in options) {
            list.addView(choice(o.label, o.selected) {
                val group = o.group
                if (group == null) { back(); return@choice }
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, o.index))
                    .build()
                onChoice?.invoke(if (type == C.TRACK_TYPE_TEXT) "sub" else "audio")
                back()
            })
        }
        if (options.isEmpty() && type == C.TRACK_TYPE_TEXT) list.addView(note("No subtitles for this video"))
        focusSelected()
    }

    private fun showSpeeds() {
        val p = player() ?: return
        submenu = "speed"
        list.removeAllViews()
        list.addView(header("Speed"))
        val now = p.playbackParameters.speed
        for (s in com.kirtanya.reel.core.player.Speeds.ALL) {
            list.addView(choice(speedLabel(s), kotlin.math.abs(s - now) < 0.01f) { p.setPlaybackSpeed(s); onChoice?.invoke("speed"); back() })
        }
        focusSelected()
    }

    private fun showList(t: PanelToggle) {
        submenu = t.label
        list.removeAllViews()
        list.addView(header(t.label))
        val now = t.current()
        for ((i, v) in t.values.withIndex()) list.addView(choice(v, i == now) { t.set(i); back() })
        focusSelected()
    }

    private fun focusSelected() {
        val kids = (0 until list.childCount).map { list.getChildAt(it) }
        (kids.firstOrNull { it.tag == SELECTED } ?: kids.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private class TrackOption(val group: Tracks.Group?, val index: Int, val label: String, val selected: Boolean)

    /** Renders only: tracks to list per type (label → selected) without a real media file. */
    var previewTracks: Map<Int, List<Pair<String, Boolean>>> = emptyMap()

    private fun trackOptions(p: Player, type: Int): List<TrackOption> {
        previewTracks[type]?.let { fake -> return fake.mapIndexed { i, (label, on) -> TrackOption(null, i, label, on) } }
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
        return out.mapIndexed { n, o -> if (o.label in dupes) TrackOption(o.group, o.index, "${o.label} (${n + 1})", o.selected) else o }
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

    private fun speedLabel(s: Float) = com.kirtanya.reel.core.player.Speeds.label(s)

    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(PlayerColors.FOREGROUND)
    }

    private fun header(label: String) = text(12f, medium = true).apply {
        text = label.uppercase()
        letterSpacing = 0.1f
        setTextColor(PlayerColors.PANEL_HEADER)
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(8))
    }

    private fun note(label: String) = text(13f).apply {
        text = label
        setTextColor(PlayerColors.PANEL_NOTE)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
    }

    private fun row(icon: Int?, label: String, value: String?, tag: String?, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(48)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        isFocusable = true
        isClickable = true
        this.tag = tag
        background = rowBackground()
        if (icon != null) addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(PlayerColors.SECONDARY)
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(14) })
        addView(text(15f, medium = true).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(text(14f).apply {
            text = "$value  ›"
            setTextColor(PlayerColors.PANEL_VALUE)
            // Long values (an audio track's name) wrap to a second line before they are cut.
            maxLines = 2
            gravity = Gravity.END
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = context.dp(150)
        })
        setOnClickListener { onClick() }
    }

    /**
     * A row that can't be used here: same place and icon, label and its reason quieter, and it takes
     * no touch or focus (the remote moves past it).
     */
    private fun offRow(icon: Int, label: String, note: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(48)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        isEnabled = false
        isFocusable = false
        tag = "$label (off)"
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(PlayerColors.PANEL_NOTE)
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(15f, medium = true).apply { text = label; setTextColor(PlayerColors.PANEL_VALUE) })
            if (note != null) addView(text(13f).apply { text = note; setTextColor(PlayerColors.PANEL_NOTE) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun choice(label: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(minRowDp)
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        isFocusable = true
        isClickable = true
        background = rowBackground()
        if (selected) tag = SELECTED
        addView(text(15f, medium = selected).apply { text = label; alpha = if (selected) 1f else 0.85f }, LinearLayout.LayoutParams(0, -2, 1f))
        // A dot marks the current choice (quieter than a tick, readable from the sofa).
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PlayerColors.FOREGROUND) }
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(context.dp(8), context.dp(8)))
        setOnClickListener { onClick() }
    }

    /** Focus is an outline (no fill, no scale, no glow); a finger on a row fills it softly. */
    private fun rowBackground() = StateListDrawable().apply {
        val ring = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = context.dpf(12f); setStroke(context.dp(2), PlayerColors.FOREGROUND) }
        val touched = GradientDrawable().apply { setColor(PlayerColors.ROW_HIGHLIGHT); cornerRadius = context.dpf(12f) }
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(android.R.attr.state_pressed), touched)
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    companion object {
        private const val SELECTED = "selected"
    }
}

/**
 * The delay slider: a track with a tick at 0 and a thumb at [value]. Remote: ←/→ one step (held keys
 * repeat). Touch: drag anywhere on its 48 dp. Focused, the track and thumb grow (TV focus rule).
 */
@SuppressLint("ViewConstructor")
internal class DelaySlider(
    context: Context,
    private val delay: com.kirtanya.reel.core.player.Delay,
    initial: Int,
    private val onChange: (Int) -> Unit,
) : View(context) {
    var value = delay.clamp(initial); set(v) { field = delay.clamp(v); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var active = 0f

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Delay"
        setOnFocusChangeListener { _, f -> active = if (f) 1f else 0f; invalidate() }
    }

    private fun fraction(v: Int) = (v - delay.minMs).toFloat() / (delay.maxMs - delay.minMs)

    override fun onDraw(canvas: Canvas) {
        val pad = context.dpf(14f)
        val w = width - 2 * pad
        val cy = height / 2f
        val h = context.dpf(4f) + context.dpf(2f) * active
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        paint.color = withAlpha(PlayerColors.FOREGROUND, if (active > 0f) 0x66 else 0x3D)
        canvas.drawRoundRect(rect, h, h, paint)
        // From 0 to the value: the part that's shifted.
        val zero = pad + w * fraction(0)
        val x = pad + w * fraction(value)
        rect.set(minOf(zero, x), cy - h / 2, maxOf(zero, x), cy + h / 2)
        paint.color = PlayerColors.FOREGROUND
        canvas.drawRect(rect, paint)
        // The 0 tick.
        rect.set(zero - context.dpf(1f), cy - context.dpf(8f), zero + context.dpf(1f), cy + context.dpf(8f))
        paint.color = withAlpha(PlayerColors.FOREGROUND, 0x99)
        canvas.drawRect(rect, paint)
        if (active > 0f) {
            paint.color = withAlpha(PlayerColors.FOREGROUND, 0x40)
            canvas.drawCircle(x, cy, context.dpf(14f), paint)
        }
        paint.color = PlayerColors.FOREGROUND
        canvas.drawCircle(x, cy, context.dpf(7f) + context.dpf(2f) * active, paint)
    }

    private fun set(v: Int) {
        val next = delay.clamp(v)
        if (next == value) return
        value = next
        onChange(next)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { set(delay.step(value, -1)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { set(delay.step(value, 1)); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = context.dpf(14f)
        fun at(x: Float) = delay.minMs + (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * (delay.maxMs - delay.minMs)).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); set(at(e.x)) }
            MotionEvent.ACTION_MOVE -> set(at(e.x))
        }
        return true
    }
}

/** How many audio tracks the video offers (the Audio button shows from two). */
internal fun Player.audioChoices(): Int =
    currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }
