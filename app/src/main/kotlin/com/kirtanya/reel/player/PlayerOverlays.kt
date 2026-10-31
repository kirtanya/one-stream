package com.kirtanya.reel.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.kirtanya.reel.ui.theme.Tokens

/**
 * The player's small overlays, built here so the renders can show them without a real playback:
 * every status chip and card is small and sits at the top right, side by side — Skip intro, Still
 * watching?, Up next, Start over, the sleep timer, then the buffering chip at the corner. On the phone
 * the Skip chip sits above the control band instead (bottom right, where the thumb is).
 */
internal class PlayerOverlays(private val context: Context, private val font: (Boolean) -> Typeface?, private val tv: Boolean) {
    private val accent get() = Tokens.Night.accent.toArgb()
    private val glassColor get() = Tokens.Night.glass.toArgb()
    private val hairline get() = Tokens.Night.hairline.toArgb()

    val chips = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    /** "Skip intro" / "Skip recap" / "Skip credits": primary, so it reads at a glance. */
    val skipChip: Button = smallButton("Skip intro", primary = true) {}.apply { visibility = View.GONE }

    /** TV: "Press Back again to exit", bottom centre, after Back (one Back never leaves the player there). */
    val exitChip: TextView = TextView(context).apply {
        text = "Press Back again to exit"
        setTextColor(PlayerColors.FOREGROUND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = font(true)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = glass(999)
        visibility = View.GONE
    }

    lateinit var stillText: TextView; private set
    lateinit var stillContinue: Button; private set
    lateinit var stillStop: Button; private set
    /** "Still watching?" with Continue / Stop. */
    val stillCard: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(4), dp(4))
        background = glass(radius = 999)
        visibility = View.GONE
        stillText = text(12f, PlayerColors.FOREGROUND, medium = true).apply { text = "Still watching?"; maxLines = 1; setPadding(0, 0, dp(8), 0) }
        addView(stillText)
        stillContinue = smallButton("Continue", primary = true) {}
        addView(stillContinue)
        stillStop = smallButton("Stop") {}
        addView(stillStop, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
    }

    lateinit var nextTitle: TextView; private set
    lateinit var nextCountdown: TextView; private set
    lateinit var nextPlay: Button; private set
    lateinit var nextCancel: Button; private set
    val nextCard: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(4), dp(4))
        background = glass(radius = 999)
        visibility = View.GONE
        nextTitle = text(12f, PlayerColors.FOREGROUND, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(220) }
        nextCountdown = text(12f, Tokens.Night.textSecondary.toArgb()).apply { setPadding(dp(8), 0, dp(8), 0); maxLines = 1 }
        addView(nextTitle)
        addView(nextCountdown)
        nextPlay = smallButton("Play now", primary = true) {}
        addView(nextPlay)
        nextCancel = smallButton("Cancel") {}
        addView(nextCancel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
    }

    val resumeChip: Button = smallButton("") {}.apply { visibility = View.GONE }

    /** Sleep timer under a minute: "Sleep in 0:42". */
    val sleepChip: TextView = text(12f, PlayerColors.FOREGROUND, medium = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = glass(radius = 999)
        maxLines = 1
        fontFeatureSettings = "tnum"
        visibility = View.GONE
    }

    /**
     * A debrid source played a placeholder and another took over: "Debridio is still fetching that one — playing
     * Comet 1080p instead". Below the chip row, two lines at most.
     */
    val noticeChip: TextView = text(12f, PlayerColors.FOREGROUND, medium = true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = glass(radius = 16)
        maxLines = 2
        maxWidth = dp(300)
        visibility = View.GONE
    }

    lateinit var notReadyTitle: TextView; private set
    lateinit var notReadyText: TextView; private set
    lateinit var notReadyButtons: LinearLayout; private set
    lateinit var notReadyRetry: Button; private set
    lateinit var notReadySources: Button; private set
    lateinit var notReadyBack: Button; private set

    /**
     * "Not ready yet": the debrid service is still downloading this and no other source could take over. A card on
     * a dark scrim (the placeholder's last frame stays hidden) with Try again / Other sources / Back. Side by side
     * where there's room (phone landscape, TV), stacked full width on a portrait phone.
     */
    val notReady: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Tokens.Night.canvas.copy(alpha = 0.94f).toArgb())
        // Takes every touch, so the controls under it never open.
        isClickable = true
        visibility = View.GONE
        val wide = context.resources.configuration.screenWidthDp >= 600
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = glass(radius = 24)
            notReadyTitle = text(20f, PlayerColors.FOREGROUND, medium = true).apply { text = "Not ready yet"; gravity = Gravity.CENTER }
            addView(notReadyTitle, LinearLayout.LayoutParams(-1, -2))
            notReadyText = text(14f, Tokens.Night.textSecondary.toArgb()).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(20))
            }
            addView(notReadyText, LinearLayout.LayoutParams(-1, -2))
            notReadyButtons = LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                notReadyRetry = cardButton("Try again", primary = true)
                notReadySources = cardButton("Other sources")
                notReadyBack = cardButton("Back")
                listOf(notReadyRetry, notReadySources, notReadyBack).forEachIndexed { i, b ->
                    val lp = if (wide) LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) }
                    else LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) }
                    addView(b, lp)
                }
            }
            addView(notReadyButtons, LinearLayout.LayoutParams(if (wide) -2 else -1, -2))
        }
        val width = minOf(context.resources.configuration.screenWidthDp - 32, 480)
        addView(card, FrameLayout.LayoutParams(dp(width), -2, Gravity.CENTER))
    }

    /** The card's buttons: 48 dp tall on the phone (touch area), the usual 40 on the TV. */
    private fun cardButton(label: String, primary: Boolean = false) = button(label, primary) {}.apply {
        if (!tv) {
            minHeight = dp(48)
            minimumHeight = dp(48)
        }
    }

    private val statusChip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(12), dp(6))
        background = glass(radius = 999)
        visibility = View.GONE
        addView(android.widget.ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(PlayerColors.FOREGROUND)
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
    }

    /** The buffering / status text; the chip shows and hides with it (every caller sets the text's visibility). */
    val status: TextView = object : TextView(context) {
        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            statusChip.visibility = visibility
        }
    }.apply {
        setTextColor(PlayerColors.FOREGROUND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = font(true)
        maxLines = 1
        maxWidth = dp(260)
    }

    init {
        statusChip.addView(status)
        status.visibility = View.GONE
        fun gap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        if (tv) chips.addView(skipChip, gap())
        chips.addView(stillCard, gap())
        chips.addView(nextCard, gap())
        chips.addView(resumeChip, gap())
        chips.addView(sleepChip, gap())
        chips.addView(statusChip, gap())
    }

    /** Adds the chip row (and on the phone the Skip chip, bottom right) to [root]. */
    fun addTo(root: FrameLayout, topMarginDp: Int = 20) {
        root.addView(chips, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(24), dp(topMarginDp), dp(24), 0) })
        if (!tv) root.addView(skipChip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(PHONE_SKIP_BOTTOM_DP)) })
        // Under the chip row (whose pills are up to 48 dp tall on the phone).
        root.addView(noticeChip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(24), dp(topMarginDp + if (tv) 44 else 56), dp(24), 0) })
        root.addView(notReady, FrameLayout.LayoutParams(-1, -1))
        // Over everything (the loading screen included): it answers the remote's Back wherever you are.
        if (tv) root.addView(exitChip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
    }

    /** Phone: keep the Skip chip just above the control band ([coveredPx] = what the band covers, 0 when hidden). */
    fun placeSkipChip(coveredPx: Int) {
        if (tv) return
        val lp = skipChip.layoutParams as? FrameLayout.LayoutParams ?: return
        val bottom = if (coveredPx > 0) coveredPx + dp(4) else dp(PHONE_SKIP_BOTTOM_DP)
        if (lp.bottomMargin != bottom) skipChip.layoutParams = lp.apply { bottomMargin = bottom }
    }

    // ---------------------------------------------------------------- views (Afterglow, docs/DESIGN_STYLE.md)

    fun smallButton(label: String, primary: Boolean = false, onClick: () -> Unit) = button(label, primary, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minHeight = dp(30)
        minimumHeight = dp(30)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(4), dp(12), dp(4))
        if (!tv) {
            // Phone: a 48 dp touch area around the same 30 dp pill (transparent bands above and below).
            background = android.graphics.drawable.InsetDrawable(background, 0, dp(9), 0, dp(9))
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(13), dp(12), dp(13))
        }
    }

    /** Pill button: filled primary (Material) or glass; a bright ring when the remote focuses it. */
    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        typeface = font(true)
        setTextColor(if (primary) Tokens.Night.onAccent.toArgb() else PlayerColors.FOREGROUND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        stateListAnimator = null
        fun shape(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(if (primary) accent else glassColor)
            setStroke(dp(if (focused) 2 else 1), if (focused) PlayerColors.FOREGROUND else if (primary) Color.TRANSPARENT else hairline)
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
        setOnClickListener { onClick() }
    }

    fun text(sizeSp: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font(medium)
    }

    fun glass(radius: Int) = GradientDrawable().apply {
        setColor(glassColor)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), hairline)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        /** Phone, controls hidden: the Skip chip this far up from the bottom edge (clear of the gesture bar). */
        const val PHONE_SKIP_BOTTOM_DP = 28
    }
}
