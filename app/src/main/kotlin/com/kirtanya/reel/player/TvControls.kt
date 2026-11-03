package com.kirtanya.reel.player

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kirtanya.reel.R
import kotlin.math.pow

/**
 * TV player controls, nothing in the middle of the picture and no dimming:
 *  - the seek bar is a hairline along the very bottom edge, end to end (only the played part
 *    shows); elapsed time above its start, remaining time above its end;
 *  - above that, the title's logo on the left (fixed height; the title as text when there's no logo or it
 *    fails), and every control on the right, with "Next · S1 E4" just above them for an episode
 *    that has a next one — Change source, Audio
 *    (only with more than one audio track) first, then back N s, play / pause, forward N s (N = Settings →
 *    Playback → Skip by), subtitles, settings — all the same size: no button stands out.
 * Settings opens a panel above the controls. Everything is white with a small, tight shadow under it
 * (the user's choice: it never switches to black, and the shadow never spreads into a halo).
 */
internal class TvControls(
    private val playerView: PlayerView,
    title: String,
    private val font: (medium: Boolean) -> Typeface?,
    /** Extra rows at the end of the Settings panel, e.g. "Change source", "Open in another player". */
    extraSettings: () -> List<PanelExtra>,
    /** "Next · S1 E4" while the episode has a next one (the chip above the title), else null; and how to play it. */
    private val nextLabel: () -> String? = { null },
    private val onNext: () -> Unit = {},
    /** Change source (the Sources page for this title) and whether there is one to change to. */
    private val onChangeSource: (() -> Unit)? = null,
    private val canChangeSource: () -> Boolean = { false },
) {
    private val context = playerView.context
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null

    /** Added over the PlayerView. */
    val view = FrameLayout(context)
    private val bar = LinearLayout(context)
    private val titleView = text(20f, medium = true)
    /** The title's logo artwork; [titleView] shows instead until (unless) it loads. */
    private val logoView = android.widget.ImageView(context).apply {
        visibility = View.GONE
        adjustViewBounds = true
        maxWidth = context.dp(LOGO_MAX_W)
        scaleType = android.widget.ImageView.ScaleType.FIT_END
    }
    // One size for every control (no filled play button).
    private val play = RoundButton(context, R.drawable.ic_pl_pause, "Play or pause", BUTTON, 26)
    private val rew = SkipButton(context, forward = false, seconds = 10, sizeDp = BUTTON, iconDp = 28, font = font(true))
    private val ffwd = SkipButton(context, forward = true, seconds = 10, sizeDp = BUTTON, iconDp = 28, font = font(true))
    private val nextChip = NextChip(context, font(true), phone = false).apply { visibility = View.GONE }
    private val source = RoundButton(context, R.drawable.ic_pl_source, "Change source", BUTTON, 24)
    private val audioBtn = RoundButton(context, R.drawable.ic_pl_audio, "Audio track", BUTTON, 24)
    private val subs = RoundButton(context, R.drawable.ic_pl_subtitles, "Subtitles", BUTTON, 22)
    private val settings = RoundButton(context, R.drawable.ic_pl_tune, "Settings", BUTTON, 22)
    private val elapsed = text(14f, medium = true)
    private val remaining = text(14f, medium = true)
    private val seek = SeekStrip(context, font(true))
    private val settingsPanel = SettingsPanel(context, font, { player }, extraSettings, onExtra = { hide() }, minRowDp = 44, cropStep = com.kirtanya.reel.core.player.CropBars.TV_STEP)

    /** The panel's picture rows (Picture, Crop bars, Cinema mode). */
    fun setToggles(list: List<PanelRow>) { settingsPanel.toggles = list }

    /** The panel's section rows (subtitle delay…, night mode…, sleep timer) and its choice callback. */
    fun setRows(subtitle: List<PanelRow>, audio: List<PanelRow>, playback: List<PanelRow>, onChoice: (String) -> Unit) {
        settingsPanel.subtitleRows = subtitle
        settingsPanel.audioRows = audio
        settingsPanel.playbackRows = playback
        settingsPanel.onChoice = onChoice
    }

    fun refreshPanel() = settingsPanel.refresh()

    /** The Change source page of the panel (the source button and the Settings row open it). */
    internal fun setSources(host: SourcePanelHost) { settingsPanel.sources = host }

    /** One skip (Settings → Playback → Skip by): the ± buttons, their numbers and the remote's FF / REW keys. */
    var seekStepMs = 10_000L
        set(v) {
            field = v
            rew.setSeconds((v / 1000).toInt())
            ffwd.setSeconds((v / 1000).toInt())
        }

    private var swallowUp: Int? = null

    val visible get() = view.visibility == View.VISIBLE
    val panelOpen get() = settingsPanel.open

    init {
        view.visibility = View.GONE
        view.clipChildren = false
        bar.clipChildren = false
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.BOTTOM

        // Left: the title's logo, never taller than the controls (LOGO_H); the title as text until it loads.
        val info = FrameLayout(context)
        titleView.text = title
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        info.addView(logoView, FrameLayout.LayoutParams(-2, context.dp(LOGO_H), Gravity.BOTTOM or Gravity.START))
        bar.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(24) })

        // Right: "Next · S1 E4" just above the controls (right-aligned), then the controls, all the same size.
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            clipChildren = false
        }
        // The chip's ring sits 8 dp outside its marks: push it out so the marks line up with the last button.
        right.addView(nextChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = -context.dp(8); bottomMargin = context.dp(6) })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        for ((i, b) in listOf(source, audioBtn, rew, play, ffwd, subs, settings).withIndex()) {
            buttons.addView(b, LinearLayout.LayoutParams(context.dp(BUTTON), context.dp(BUTTON)).apply { if (i > 0) marginStart = context.dp(8) })
        }
        right.addView(buttons, LinearLayout.LayoutParams(-2, -2))
        right.clipToPadding = false
        bar.addView(right, LinearLayout.LayoutParams(-2, -2))

        // Under it: elapsed at the start, remaining at the end.
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(elapsed, LinearLayout.LayoutParams(-2, -2))
        times.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(-2, -2))

        // Title, controls and times are one block, the same distance (EDGE) from the left, right
        // and bottom of the screen; the seek bar runs along the bottom edge below it.
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        block.addView(bar, LinearLayout.LayoutParams(-1, -2))
        block.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        view.addView(block, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(context.dp(EDGE), 0, context.dp(EDGE), context.dp(EDGE)) })

        // The bottom edge itself: the seek bar, end to end.
        seek.edge = true
        view.addView(seek, FrameLayout.LayoutParams(-1, context.dp(28), Gravity.BOTTOM))

        rew.setOnClickListener { seekBy(-seekStepMs); touch() }
        ffwd.setOnClickListener { seekBy(seekStepMs); touch() }
        play.setOnClickListener { togglePlay(); touch() }
        nextChip.setOnClickListener { hide(); onNext() }
        source.setOnClickListener { if (settingsPanel.sources != null) openPanel("source") else { hide(); onChangeSource?.invoke() } }
        audioBtn.setOnClickListener { openPanel("audio") }
        subs.setOnClickListener { openPanel("sub") }
        settings.setOnClickListener { openPanel(null) }
        seek.onScrubStart = { touch() }
        seek.onScrubMove = { elapsed.text = PlayerActivity.formatTime(it); remaining.text = "−" + PlayerActivity.formatTime((seek.duration - it).coerceAtLeast(0)); touch() }
        seek.onSeek = { player?.seekTo(it); touch() }
        // OK on the seek bar (not scrubbing) plays / pauses; ←/→ still skip.
        seek.onOk = { togglePlay(); touch() }

        view.addView(settingsPanel.panel, FrameLayout.LayoutParams(context.dp(340), -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, context.dp(24), context.dp(EDGE), context.dp(EDGE + 148)) // clears the Next chip above the controls
        })
        applyLook()
    }

    /**
     * The title's logo (TMDB artwork URL), loaded once; the text title stays if [url] is null or it fails.
     * [load] is Coil, passed in so this class stays free of image-loading details.
     */
    fun setLogo(url: String?, load: (String, (android.graphics.drawable.Drawable?) -> Unit) -> Unit) {
        if (url == null) return
        load(url) { d ->
            if (d == null) return@load
            logoView.setImageDrawable(d)
            // A dark logo (black wordmark) would vanish over a dark picture: draw it in the strip's white.
            if (isDark(d)) logoView.setColorFilter(PlayerColors.FOREGROUND, android.graphics.PorterDuff.Mode.SRC_IN)
            logoLoaded = true
            showTitle()
        }
    }

    private var logoLoaded = false

    /** Settings → Title: Logo (when one loaded) or the name as text. */
    var logoShown = true
        set(v) { field = v; showTitle() }

    private fun showTitle() {
        val logo = logoShown && logoLoaded
        logoView.visibility = if (logo) View.VISIBLE else View.GONE
        titleView.visibility = if (logo) View.INVISIBLE else View.VISIBLE
    }

    /** Mean luminance of the logo's visible pixels (sampled on a small grid) below 0.35. */
    private fun isDark(d: android.graphics.drawable.Drawable): Boolean {
        val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return false
        val soft = if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bmp
        var sum = 0f
        var n = 0
        for (y in 0 until 24) for (x in 0 until 48) {
            val c = soft.getPixel(x * soft.width / 48, y * soft.height / 24)
            if (Color.alpha(c) < 128) continue
            sum += luminance(c); n++
        }
        return n > 0 && sum / n < 0.35f
    }

    /** Renders only: a logo drawable instead of a network load. */
    internal fun previewLogo(d: android.graphics.drawable.Drawable) = setLogo("preview") { _, done -> done(d) }

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

    private val hideRunnable = Runnable { if (player?.isPlaying == true && !panelOpen && !seek.scrubbing) hide() }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            main.postDelayed(this, 500)
        }
    }
    fun show(focus: View? = play) {
        if (!visible) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(180).start()
            val block = bar.parent as View // title, controls and times slide up together
            block.translationY = context.dpf(16f)
            block.animate().translationY(0f).setDuration(180).start()
            refresh()
            main.removeCallbacks(tick); main.post(tick)
        }
        if (focus != null && !panelOpen && view.findFocus() == null) focus.requestFocus()
        touch()
    }

    fun hide() {
        settingsPanel.hide()
        view.visibility = View.GONE
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tick)
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
                seekBy(if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) seekStepMs else -seekStepMs)
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
            // A scrub in progress takes Back itself (cancels it).
            if (seek.scrubbing) return false
            if (panelOpen) { if (!settingsPanel.back()) closePanel() } else hide()
            swallowUp = code
            return true
        }
        touch()
        return false
    }

    /** Renders only: a mid-film state without a player. */
    internal fun preview(positionMs: Long, durationMs: Long, bufferedMs: Long, playing: Boolean) {
        seek.duration = durationMs
        seek.buffered = bufferedMs
        seek.position = positionMs
        elapsed.text = PlayerActivity.formatTime(positionMs)
        remaining.text = "−" + PlayerActivity.formatTime(durationMs - positionMs)
        play.setIcon(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
        showButtons()
    }

    /** Renders only: show the Audio button as if the video had several audio tracks. */
    internal var previewAudioChoice = false

    /** Renders only: the panel lists these tracks (type → label to selected). */
    internal fun previewTracks(tracks: Map<Int, List<Pair<String, Boolean>>>) { settingsPanel.previewTracks = tracks }

    /** The Next chip, Change source and Audio come and go with the video. */
    private fun showButtons() {
        nextChip.set(nextLabel())
        source.visibility = if (onChangeSource != null && canChangeSource()) View.VISIBLE else View.GONE
        audioBtn.visibility = if (previewAudioChoice || (player?.audioChoices() ?: 0) > 1) View.VISIBLE else View.GONE
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
        play.setIcon(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
        showButtons()
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        seek.duration = d
        seek.buffered = p.bufferedPosition
        seek.position = p.currentPosition
        if (!seek.scrubbing) elapsed.text = PlayerActivity.formatTime(p.currentPosition)
        remaining.text = if (d > 0) "−" + PlayerActivity.formatTime((d - p.currentPosition).coerceAtLeast(0)) else "--:--"
    }

    // ---------------------------------------------------------------- settings panel

    /** The button that opened the panel: focus goes back to it when the panel closes. */
    private var opener: View = settings

    internal fun openPanel(section: String? = null) {
        opener = when (section) { "audio" -> audioBtn; "sub" -> subs; "source" -> source; else -> settings }
        settingsPanel.show(section)
        main.removeCallbacks(hideRunnable)
    }

    private fun closePanel() {
        if (!panelOpen) return
        settingsPanel.hide()
        if (visible) opener.requestFocus()
        touch()
    }

    // ---------------------------------------------------------------- look

    /** White text and icons, each with the same small shadow (CONTROL_SHADOW, 1.5 dp blur, 1 dp down). */
    private fun applyLook() {
        val c = PlayerColors.FOREGROUND
        for (t in listOf(titleView, elapsed, remaining)) {
            t.setTextColor(c)
            t.setShadowLayer(context.dpf(1.5f), 0f, context.dpf(1f), PlayerColors.CONTROL_SHADOW)
        }
        for (b in listOf(play, rew, ffwd, source, audioBtn, subs, settings)) {
            b.tint(c)
            b.addShadow(PlayerColors.CONTROL_SHADOW)
        }
        rew.shadowNumber(PlayerColors.CONTROL_SHADOW)
        ffwd.shadowNumber(PlayerColors.CONTROL_SHADOW)
        seek.tint(c)
    }

    // ---------------------------------------------------------------- views

    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(medium)
        setTextColor(PlayerColors.FOREGROUND)
        fontFeatureSettings = "tnum"
    }

    companion object {
        /** Every control's size (dp). */
        const val BUTTON = 48

        /** The title logo's box (dp): no taller than the Next chip + the controls, so the strip keeps its height. */
        const val LOGO_H = 72
        const val LOGO_MAX_W = 320

        /** The controls' distance from the left, right and bottom of the screen (dp). */
        const val EDGE = 40

        /** Near-black: within 5 % of black's contrast, a little softer on the eye. */
        const val DARK = PlayerColors.STRIP_DARK

        /** WCAG relative luminance of an sRGB colour. */
        fun luminance(argb: Int): Float {
            fun ch(v: Int): Float {
                val s = v / 255f
                return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            }
            return 0.2126f * ch(Color.red(argb)) + 0.7152f * ch(Color.green(argb)) + 0.0722f * ch(Color.blue(argb))
        }

    }
}
