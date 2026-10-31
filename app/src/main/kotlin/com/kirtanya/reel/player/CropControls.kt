package com.kirtanya.reel.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.core.playback.PictureMode
import com.kirtanya.reel.core.player.CropBars

/*
 * Crop bars (black bars baked into the picture): the layout that cuts them off, the slider, the
 * phone's adjust bar, and the frame copies Auto looks at.
 */

/** How the picture sits on the screen: picture mode + crop. The player and the renders both use it. */
internal object PictureLayout {
    /**
     * [mode] picks how the content frame fills the screen; the frame takes the shape of what's left
     * after the crop (the file's own shape [videoAspect], or the mode's forced one, made wider), and
     * the video inside it grows vertically by 1 / (1 − 2 crop) so only its middle fills the frame.
     * The frame clips the rest. A SurfaceView isn't clipped by its parent on every Android version,
     * but its surface sits under the window, which is opaque outside the frame (the hole the
     * SurfaceView punches is clipped), so the cut-off rows stay hidden either way. [extra]: renders'
     * stand-in pictures, laid out like the video.
     */
    fun apply(playerView: PlayerView, mode: PictureMode, videoAspect: Float?, cropPercent: Float, extra: List<View> = emptyList()) {
        playerView.resizeMode = when (mode) {
            PictureMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PictureMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val frame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        frame.clipChildren = true
        (mode.ratio ?: videoAspect)?.let { frame.setAspectRatio(CropBars.aspect(it, cropPercent)) }
        val scale = CropBars.scaleY(cropPercent)
        for (v in listOfNotNull(playerView.videoSurfaceView) + extra) v.scaleY = scale
    }
}

/**
 * The crop slider: 0 … 20 % with the part cropped filled in. Remote: ←/→ one [step]. Touch: drag
 * anywhere on its 48 dp (0.5 % steps). Focused, the track and thumb grow (TV focus rule).
 */
@SuppressLint("ViewConstructor")
internal class CropSlider(context: Context, initial: Float, private val step: Float, private val onChange: (Float) -> Unit) : View(context) {
    var value = CropBars.clamp(initial); set(v) { field = CropBars.clamp(v); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var active = 0f

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Crop bars"
        setOnFocusChangeListener { _, f -> active = if (f) 1f else 0f; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dpf(14f)
        val w = width - 2 * pad
        val cy = height / 2f
        val h = context.dpf(4f) + context.dpf(2f) * active
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        paint.color = withAlpha(PlayerColors.FOREGROUND, if (active > 0f) 0x66 else 0x3D)
        canvas.drawRoundRect(rect, h, h, paint)
        val x = pad + w * (value / CropBars.MAX_PERCENT)
        rect.set(pad, cy - h / 2, x, cy + h / 2)
        paint.color = PlayerColors.FOREGROUND
        canvas.drawRoundRect(rect, h, h, paint)
        if (active > 0f) {
            paint.color = withAlpha(PlayerColors.FOREGROUND, 0x40)
            canvas.drawCircle(x, cy, context.dpf(14f), paint)
        }
        paint.color = PlayerColors.FOREGROUND
        canvas.drawCircle(x, cy, context.dpf(7f) + context.dpf(2f) * active, paint)
    }

    fun set(v: Float) {
        val next = CropBars.clamp(v)
        if (next == value) return
        value = next
        onChange(next)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { set(CropBars.step(value, -1, step)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { set(CropBars.step(value, 1, step)); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = context.dpf(14f)
        fun at(x: Float) = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * CropBars.MAX_PERCENT
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); set(at(e.x)) }
            MotionEvent.ACTION_MOVE -> set(at(e.x))
        }
        return true
    }
}

/**
 * Phone: Crop bars as a small bar at the bottom instead of the settings panel, so the picture
 * being cropped stays in view while the slider moves (the user judges the edges live). Title and
 * value; − slider +; Auto, Reset, Done. Every touch area 48 dp.
 */
internal class CropHud(context: Context, private val font: (Boolean) -> Typeface?, private val onDone: () -> Unit) : LinearLayout(context) {
    private var row: PanelCrop? = null
    private val value = text(15f, medium = true)
    private val status = text(13f).apply { setTextColor(PlayerColors.PANEL_NOTE); visibility = GONE }
    private val slider = CropSlider(context, 0f, CropBars.PHONE_STEP) { p -> apply(p) }

    init {
        orientation = VERTICAL
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        background = GradientDrawable().apply {
            setColor(PlayerColors.PANEL)
            cornerRadius = context.dpf(20f)
            setStroke(context.dp(1), PlayerColors.GLASS_STROKE)
        }
        isClickable = true // touches on the bar stay off the picture's gestures
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(context.dp(10), context.dp(6), context.dp(10), 0) }
        head.addView(text(15f, medium = true).apply { text = "Crop bars" }, LayoutParams(0, -2, 1f))
        head.addView(value)
        addView(head, LayoutParams(-1, -2))
        addView(status.apply { setPadding(context.dp(10), context.dp(2), context.dp(10), 0) }, LayoutParams(-1, -2))
        val adjust = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        adjust.addView(button("−", "Less crop", 22f) { apply(CropBars.step(slider.value, -1, CropBars.PHONE_STEP)) }, LayoutParams(context.dp(48), context.dp(48)))
        adjust.addView(slider, LayoutParams(0, context.dp(48), 1f))
        adjust.addView(button("+", "More crop", 22f) { apply(CropBars.step(slider.value, 1, CropBars.PHONE_STEP)) }, LayoutParams(context.dp(48), context.dp(48)))
        addView(adjust, LayoutParams(-1, -2))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("Auto", "Find the bars by itself", 15f) { auto() }, LayoutParams(-2, context.dp(48)))
        actions.addView(button("Reset", "No crop", 15f) { apply(0f); status.visibility = GONE }, LayoutParams(-2, context.dp(48)))
        actions.addView(View(context), LayoutParams(0, 1, 1f))
        actions.addView(button("Done", "Done", 15f) { onDone() }, LayoutParams(-2, context.dp(48)))
        addView(actions, LayoutParams(-1, -2))
    }

    fun show(r: PanelCrop) {
        row = r
        slider.value = r.current()
        value.text = CropBars.label(slider.value)
        status.visibility = GONE
        visibility = VISIBLE
        alpha = 0f
        translationY = context.dpf(12f)
        animate().alpha(1f).translationY(0f).setDuration(160).start()
    }

    private fun apply(p: Float) {
        slider.value = p
        value.text = CropBars.label(slider.value)
        row?.set(slider.value)
    }

    private fun auto() {
        val r = row ?: return
        r.auto { message ->
            status.text = message
            status.visibility = VISIBLE
            slider.value = r.current()
            value.text = CropBars.label(slider.value)
        }
    }

    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(PlayerColors.FOREGROUND)
        fontFeatureSettings = "tnum"
    }

    private fun button(label: String, description: String, sp: Float, onClick: () -> Unit) = text(sp, medium = true).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        minWidth = context.dp(48)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        isFocusable = true
        tag = "crop:$label"
        background = StateListDrawable().apply {
            // Focus is an outline (no fill, no scale, no glow); a finger fills it softly.
            val touched = GradientDrawable().apply { setColor(PlayerColors.ROW_HIGHLIGHT); cornerRadius = context.dpf(12f) }
            val ring = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = context.dpf(12f); setStroke(context.dp(2), PlayerColors.FOREGROUND) }
            addState(intArrayOf(android.R.attr.state_pressed), touched)
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        setOnClickListener { onClick() }
    }
}

/**
 * Auto's eyes: [count] small copies of the video frame, [gapMs] apart (a moment of film, so one dark
 * shot can't decide), as luma. PixelCopy of a SurfaceView copies the decoded frame itself — the
 * whole picture, bars included, whatever the crop or picture mode — scaled into the bitmap. Fewer
 * frames when a copy fails (DRM, surface gone); none = an empty list.
 */
internal object FrameGrab {
    const val W = 128
    const val H = 200

    fun luma(sv: SurfaceView, count: Int, gapMs: Long, done: (List<CropBars.Frame>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val frames = ArrayList<CropBars.Frame>()
        fun next(left: Int) {
            if (left == 0 || sv.holder.surface?.isValid != true) { done(frames); return }
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(sv, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val px = IntArray(W * H)
                        bmp.getPixels(px, 0, W, 0, 0, W, H)
                        frames += CropBars.Frame.ofArgb(px, W, H)
                    }
                    bmp.recycle()
                    if (left > 1) main.postDelayed({ next(left - 1) }, gapMs) else done(frames)
                }, main)
            } catch (_: IllegalArgumentException) {
                bmp.recycle()
                done(frames)
            }
        }
        next(count)
    }
}
