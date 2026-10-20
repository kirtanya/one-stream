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
 * (res/drawable/ic_pl_*), a round play button, skip buttons with the seconds inside the arrow, a
 * seek bar with a time bubble, and the settings panel.
 */

internal fun Context.dpf(v: Float) = v * resources.displayMetrics.density
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
internal fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

/** A round icon button: transparent, a soft disc + ring when focused, grows a little on focus. */
@SuppressLint("ViewConstructor")
internal open class RoundButton(context: Context, icon: Int, label: String, private val sizeDp: Int, iconDp: Int) : FrameLayout(context) {
    val image = ImageView(context).apply { setImageResource(icon) }
    private var color = Color.WHITE

    init {
        contentDescription = label
        isFocusable = true
        isClickable = true
        addView(image, LayoutParams(context.dp(iconDp), context.dp(iconDp), Gravity.CENTER))
        setOnFocusChangeListener { _, f -> animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(140).start() }
        tint(Color.WHITE)
    }

    open fun tint(c: Int) {
        color = c
        image.imageTintList = ColorStateList.valueOf(c)
        background = StateListDrawable().apply {
            val on = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(c, 0x2E)); setStroke(context.dp(2), c) }
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(c, 0x24)) })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
        }
    }

    fun setIcon(res: Int) = image.setImageResource(res)
}

/** The main button: a filled disc in the foreground colour with the icon cut out of it. */
@SuppressLint("ViewConstructor")
internal class PlayButton(context: Context, sizeDp: Int) : RoundButton(context, R.drawable.ic_pl_pause, "Play or pause", sizeDp, (sizeDp * 0.46f).toInt()) {
    override fun tint(c: Int) {
        val inverse = if (TvControls.luminance(c) > 0.5f) TvControls.DARK else Color.WHITE
        image.imageTintList = ColorStateList.valueOf(inverse)
        background = StateListDrawable().apply {
            val on = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(context.dp(3), withAlpha(inverse, 0x99)) }
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(c, 0xF2)) })
        }
    }
}

/** Back / forward N seconds: the circular arrow with the number inside it. */
@SuppressLint("ViewConstructor")
internal class SkipButton(context: Context, forward: Boolean, seconds: Int, sizeDp: Int, iconDp: Int, font: Typeface?) :
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
        tint(Color.WHITE)
    }

    override fun tint(c: Int) {
        super.tint(c)
        @Suppress("SENSELESS_COMPARISON") if (number != null) number.setTextColor(c)
    }
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

    var scrubbing = false; private set
    private var scrubPos = 0L
    private var color = Color.WHITE
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
                text.color = if (TvControls.luminance(color) > 0.5f) TvControls.DARK else Color.WHITE
                canvas.drawText(label, bx, rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
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
        val pad = context.dpf(9f)
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
 * Settings panel (subtitles, audio, speed, extras), two levels: a list of settings, each opening its
 * choices. Built into [panel]; the caller places and shows it.
 */
internal class SettingsPanel(
    private val context: Context,
    private val font: (Boolean) -> Typeface?,
    private val player: () -> Player?,
    private val extras: () -> List<Pair<String, () -> Unit>>,
    /** An extra row was chosen (the caller closes the panel). */
    private val onExtra: () -> Unit,
) {
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
    }
    val panel = ScrollView(context).apply {
        addView(list)
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(0xEB141418.toInt())
            cornerRadius = context.dpf(20f)
            setStroke(context.dp(1), 0x26FFFFFF)
        }
        visibility = View.GONE
    }
    private var submenu: String? = null
    val open get() = panel.visibility == View.VISIBLE

    fun show(section: String? = null) {
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationY = context.dpf(12f)
        panel.animate().alpha(1f).translationY(0f).setDuration(160).start()
        when (section) {
            "sub" -> showTracks(C.TRACK_TYPE_TEXT)
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

    private fun buildMain() {
        list.removeAllViews()
        val p = player() ?: return
        list.addView(header("Settings"))
        list.addView(row(R.drawable.ic_pl_subtitles, "Subtitles", current(p, C.TRACK_TYPE_TEXT) ?: "Off", "sub") { showTracks(C.TRACK_TYPE_TEXT) })
        if (trackOptions(p, C.TRACK_TYPE_AUDIO).size > 1) {
            list.addView(row(R.drawable.ic_pl_volume, "Audio", current(p, C.TRACK_TYPE_AUDIO) ?: "Default", "audio") { showTracks(C.TRACK_TYPE_AUDIO) })
        }
        list.addView(row(R.drawable.ic_pl_fast, "Speed", speedLabel(p.playbackParameters.speed), "speed") { showSpeeds() })
        for ((label, action) in extras()) list.addView(row(R.drawable.ic_pl_pip, label, null, null) { onExtra(); action() })
    }

    private fun showTracks(type: Int) {
        val p = player() ?: return
        submenu = if (type == C.TRACK_TYPE_TEXT) "sub" else "audio"
        list.removeAllViews()
        list.addView(header(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio"))
        val options = trackOptions(p, type)
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || options.none { it.selected }
            list.addView(choice("Off", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                back()
            })
        }
        for (o in options) {
            list.addView(choice(o.label, o.selected) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                    .build()
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
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            list.addView(choice(speedLabel(s), kotlin.math.abs(s - now) < 0.01f) { p.setPlaybackSpeed(s); back() })
        }
        focusSelected()
    }

    private fun focusSelected() {
        val kids = (0 until list.childCount).map { list.getChildAt(it) }
        (kids.firstOrNull { it.tag == SELECTED } ?: kids.firstOrNull { it.isFocusable })?.requestFocus()
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

    private fun speedLabel(s: Float) = if (s == 1f) "Normal" else (if (s % 1f == 0f) "${s.toInt()}×" else "${s}×")

    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(Color.WHITE)
    }

    private fun header(label: String) = text(12f, medium = true).apply {
        text = label.uppercase()
        letterSpacing = 0.1f
        setTextColor(0x8CFFFFFF.toInt())
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(8))
    }

    private fun note(label: String) = text(13f).apply {
        text = label
        setTextColor(0x99FFFFFF.toInt())
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
            imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(14) })
        addView(text(15f, medium = true).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(text(14f).apply {
            text = "$value  ›"
            setTextColor(0xA6FFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = context.dp(150)
        })
        setOnClickListener { onClick() }
    }

    private fun choice(label: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(44)
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        isFocusable = true
        isClickable = true
        background = rowBackground()
        if (selected) tag = SELECTED
        addView(text(15f, medium = selected).apply { text = label; alpha = if (selected) 1f else 0.85f }, LinearLayout.LayoutParams(0, -2, 1f))
        // A dot marks the current choice (quieter than a tick, readable from the sofa).
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(context.dp(8), context.dp(8)))
        setOnClickListener { onClick() }
    }

    private fun rowBackground() = StateListDrawable().apply {
        val on = GradientDrawable().apply { setColor(0x29FFFFFF); cornerRadius = context.dpf(12f) }
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(android.R.attr.state_pressed), on)
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    companion object {
        private const val SELECTED = "selected"
    }
}
