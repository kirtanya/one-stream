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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.kirtanya.reel.core.player.BufferInfo
import com.kirtanya.reel.ui.theme.Tokens

/** The switch countdown inside the start block: "Trying another source in 5 s", why, and where it goes next. */
internal class BufferCountdown(val secondsLeft: Int, val reason: String, val next: String?)

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


    // ---------------------------------------------------------------- start / buffering block

    /** The title's backdrop, set by the player once it has loaded (the first-frame wait looks like the film, not a void). */
    val bufferBackdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val bufferScrim = View(context)

    /**
     * The picture behind the start block: the backdrop under one dark scrim. Added under the controls so the strip and
     * the phone band still show over it; mid-film (a rebuffer) it stays empty and the film's last frame shows.
     */
    val bufferBack: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        addView(bufferBackdrop, FrameLayout.LayoutParams(-1, -1))
        addView(bufferScrim, FrameLayout.LayoutParams(-1, -1))
    }

    private lateinit var bufferHeadline: TextView
    private lateinit var bufferFacts: TextView
    private lateinit var bufferNote: TextView
    private lateinit var bufferBar: LinearLayout
    private lateinit var bufferFill: View
    private lateinit var bufferRest: View
    private lateinit var bufferProgress: TextView
    private val statValues = ArrayList<TextView>()
    private val statLabels = ArrayList<TextView>()
    private lateinit var statRow: LinearLayout
    lateinit var bufferKeep: Button; private set
    lateinit var bufferSwitch: Button; private set
    private lateinit var bufferButtons: LinearLayout

    /** The buttons are up (the countdown shows): the remote belongs to them. */
    val bufferActionsShown: Boolean get() = buffer.visibility == View.VISIBLE && bufferButtons.visibility == View.VISIBLE

    /**
     * What is happening while the film isn't playing yet: the stage in plain words, which file this is, download
     * speed, peers and seeders, and how much of what the first frame needs has arrived. One calm card, centred, updated
     * once a second, nothing animating (TV rule). With the countdown it becomes the "trying another source" question.
     */
    val buffer: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = glass(radius = 24)
            bufferHeadline = text(20f, PlayerColors.FOREGROUND, medium = true)
            addView(bufferHeadline, LinearLayout.LayoutParams(-1, -2))
            bufferFacts = text(14f, Tokens.Night.textSecondary.toArgb()).apply { setPadding(0, dp(4), 0, 0) }
            addView(bufferFacts, LinearLayout.LayoutParams(-1, -2))
            bufferNote = text(14f, Tokens.Night.textSecondary.toArgb()).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            }
            addView(bufferNote, LinearLayout.LayoutParams(-1, -2))
            statRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // The download speed is the widest value ("729 KB/s", "12.4 MB/s"): its cell gets more room.
                repeat(3) { i ->
                    val cell = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val value = text(16f, PlayerColors.FOREGROUND, medium = true).apply { maxLines = 1; fontFeatureSettings = "tnum" }
                        val label = text(12f, Tokens.Night.textSecondary.toArgb()).apply { maxLines = 1 }
                        statValues.add(value)
                        statLabels.add(label)
                        addView(value)
                        addView(label)
                    }
                    addView(cell, LinearLayout.LayoutParams(0, -2, if (i == 0) 1.4f else 1f))
                }
            }
            addView(statRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            bufferBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(hairline); cornerRadius = dp(2).toFloat() }
                bufferFill = View(context).apply { background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() } }
                bufferRest = View(context)
                addView(bufferFill, LinearLayout.LayoutParams(0, -1, 0f))
                addView(bufferRest, LinearLayout.LayoutParams(0, -1, 1f))
            }
            addView(bufferBar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(16) })
            bufferProgress = text(12f, Tokens.Night.textSecondary.toArgb()).apply { setPadding(0, dp(8), 0, 0); fontFeatureSettings = "tnum" }
            addView(bufferProgress, LinearLayout.LayoutParams(-1, -2))
            bufferButtons = LinearLayout(context).apply {
                // Side by side where there's room (phone landscape, TV), stacked full width on a portrait phone (like Not ready).
                val wide = context.resources.configuration.screenWidthDp >= 600
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                bufferKeep = cardButton("Keep waiting", primary = true)
                bufferSwitch = cardButton("Switch now")
                if (wide) {
                    addView(bufferKeep, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(bufferSwitch, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                } else {
                    addView(bufferKeep, LinearLayout.LayoutParams(-1, -2))
                    addView(bufferSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
            }
            addView(bufferButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        val width = minOf(context.resources.configuration.screenWidthDp - 32, 440)
        addView(card, FrameLayout.LayoutParams(dp(width), -2, Gravity.CENTER))
    }

    /**
     * Shows [info], or the switch question when [countdown] is set. [overVideo]: mid-film, so no backdrop and no
     * scrim, only the card over the frozen picture.
     */
    fun bindBuffer(info: BufferInfo, countdown: BufferCountdown? = null, overVideo: Boolean = false) {
        bufferHeadline.text = countdown?.let { com.kirtanya.reel.core.player.SourcePatience.countdownText(it.secondsLeft) } ?: info.stage.label
        bufferFacts.text = info.facts
        bufferFacts.visibility = if (info.facts.isBlank()) View.GONE else View.VISIBLE
        bufferNote.text = countdown?.let { c -> listOfNotNull(c.reason, c.next?.let { "Next: $it" }).joinToString("\n") }.orEmpty()
        bufferNote.visibility = if (countdown == null) View.GONE else View.VISIBLE
        statValues.indices.forEach { i ->
            val stat = info.stats.getOrNull(i)
            statValues[i].text = stat?.second.orEmpty()
            statLabels[i].text = stat?.first.orEmpty()
        }
        statRow.visibility = if (info.stats.isEmpty()) View.GONE else View.VISIBLE
        val p = info.progress
        bufferBar.visibility = if (p == null) View.GONE else View.VISIBLE
        bufferProgress.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) {
            val f = p.coerceIn(0f, 1f)
            (bufferFill.layoutParams as LinearLayout.LayoutParams).weight = f
            (bufferRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
            bufferBar.requestLayout()
            bufferProgress.text = info.progressText.orEmpty()
        }
        bufferButtons.visibility = if (countdown == null) View.GONE else View.VISIBLE
        bufferBackdrop.visibility = if (overVideo) View.GONE else View.VISIBLE
        bufferScrim.setBackgroundColor(if (overVideo) Color.TRANSPARENT else Tokens.Night.canvas.copy(alpha = BUFFER_SCRIM).toArgb())
        bufferBack.visibility = View.VISIBLE
        buffer.visibility = View.VISIBLE
    }

    fun hideBuffer() {
        buffer.visibility = View.GONE
        bufferBack.visibility = View.GONE
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
    fun addTo(root: FrameLayout, topMarginDp: Int = 20, backIndex: Int = -1) {
        // The start block's picture goes under the controls (backIndex: just above the video); its card over them.
        root.addView(bufferBack, backIndex, FrameLayout.LayoutParams(-1, -1))
        root.addView(buffer, FrameLayout.LayoutParams(-1, -1))
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        stateListAnimator = null
        setPrimary(this, primary)
        setOnClickListener { onClick() }
    }

    /** Fill [b] with the accent ([primary]) or the glass look; the Not ready card moves its main action between buttons. */
    fun setPrimary(b: Button, primary: Boolean) {
        b.setTextColor(if (primary) Tokens.Night.onAccent.toArgb() else PlayerColors.FOREGROUND)
        fun shape(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(if (primary) accent else glassColor)
            setStroke(dp(if (focused) 2 else 1), if (focused) PlayerColors.FOREGROUND else if (primary) Color.TRANSPARENT else hairline)
        }
        b.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
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
        /** The backdrop's dark scrim: enough that the card's 12 sp text reads on any picture. */
        const val BUFFER_SCRIM = 0.82f
    }
}
