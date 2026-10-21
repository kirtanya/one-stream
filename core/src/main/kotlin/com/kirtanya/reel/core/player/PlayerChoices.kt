package com.kirtanya.reel.core.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Player settings that apply to everything (Settings → Playback, and the audio switches in the
 * player's panel, which the user wants kept between films).
 */
data class PlayerOptions(
    /** Up next counts down this long before the next episode plays (AutoNext.COUNTDOWNS). */
    val countdownS: Int = AutoNext.DEFAULT_COUNTDOWN_S,
    /** One skip on the remote / the ± buttons / a double-tap (SeekStep.STEPS). */
    val seekStepS: Int = SeekStep.DEFAULT_S,
    /** Night mode: loud scenes quieter, quiet speech louder (dynamic range compression). */
    val nightMode: Boolean = false,
    /** Voice boost: dialogue frequencies (about 1–4 kHz) lifted. */
    val voiceBoost: Boolean = false,
) {
    /** Values from an older / newer version that aren't offered fall back to the defaults. */
    fun valid() = copy(countdownS = AutoNext.countdown(countdownS), seekStepS = SeekStep.of(seekStepS))
}

/** Auto-play next: the countdown, and "Still watching?" after a run of untouched episodes. */
object AutoNext {
    val COUNTDOWNS = listOf(5, 10, 20)
    const val DEFAULT_COUNTDOWN_S = 10

    fun countdown(seconds: Int) = if (seconds in COUNTDOWNS) seconds else DEFAULT_COUNTDOWN_S

    /** Ask "Still watching?" instead of auto-playing once this many episodes in a row went by untouched. */
    const val ASK_AFTER_EPISODES = 3

    /** Nobody answers the question for this long: stop (someone fell asleep). */
    const val ASK_TIMEOUT_MS = 60_000L

    /** Episodes in a row without a key press or touch, counting the one that just ended. */
    fun untouchedAfter(untouchedBefore: Int, interacted: Boolean) = if (interacted) 0 else untouchedBefore + 1

    /** At the end of an episode, with [untouched] = untouchedAfter(...): ask instead of auto-playing? */
    fun askStillWatching(untouched: Int) = untouched >= ASK_AFTER_EPISODES
}

/** How far one skip goes: the remote's ←/→, the ±N buttons and a double-tap. */
object SeekStep {
    val STEPS = listOf(10, 15, 30)
    const val DEFAULT_S = 10
    /** Holding ←/→ on the remote for this long switches to the long step. */
    const val HOLD_MS = 2_000L
    const val HELD_S = 30

    fun of(seconds: Int) = if (seconds in STEPS) seconds else DEFAULT_S

    /** One remote press, [heldMs] into holding the key: the step, or 30 s (never less than the step) once held. */
    fun remoteStepMs(stepS: Int, heldMs: Long): Long = 1_000L * (if (heldMs > HOLD_MS) maxOf(HELD_S, stepS) else stepS)
}

/** Playback speeds offered in the player (0.75–2×). */
object Speeds {
    val ALL = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** A remembered / odd speed snaps to the nearest offered one. */
    fun nearest(speed: Float): Float = ALL.minBy { abs(it - speed) }

    fun label(speed: Float): String = when {
        speed == 1f -> "Normal"
        speed % 1f == 0f -> "${speed.toInt()}×"
        else -> "${speed}×"
    }
}

/** Audio and subtitle delay: the panel's sliders, their range, steps and labels. */
enum class Delay(val minMs: Int, val maxMs: Int, val stepMs: Int) {
    /** + = sound later than the picture. */
    AUDIO(-500, 500, 50),
    /** + = subtitles later than the speech. */
    SUBTITLES(-5_000, 5_000, 250),
    ;

    fun clamp(ms: Int): Int = ((ms.coerceIn(minMs, maxMs).toFloat() / stepMs).roundToInt() * stepMs).coerceIn(minMs, maxMs)

    /** One step later (+1) or earlier (−1). */
    fun step(ms: Int, direction: Int): Int = clamp(ms + direction * stepMs)

    /** "0 ms", "+150 ms", "−1.25 s": milliseconds under a second, seconds above. */
    fun label(ms: Int): String {
        if (ms == 0) return if (this == AUDIO) "0 ms" else "0 s"
        val sign = if (ms > 0) "+" else "−"
        val a = abs(ms)
        return if (a < 1_000) "$sign$a ms" else sign + ("%.2f".format(java.util.Locale.ROOT, a / 1000f).trimEnd('0').trimEnd('.')) + " s"
    }
}

/** Sleep timer choices (the player's panel). */
enum class SleepChoice(val label: String, val minutes: Int?) {
    OFF("Off", null),
    MIN_15("15 min", 15),
    MIN_30("30 min", 30),
    MIN_60("60 min", 60),
    END_OF_EPISODE("End of episode", null),
}

/**
 * The sleep timer: the sound fades out over the last 10 s, then playback pauses; under a minute a
 * small chip shows what's left.
 */
object SleepTimer {
    const val FADE_MS = 10_000L
    const val CHIP_MS = 60_000L

    data class State(val remainingMs: Long, val volume: Float, val showChip: Boolean, val done: Boolean)

    /**
     * Time left: minutes run on the clock from when the timer was set; End of episode is what's left of
     * the video at the current speed. Null when the timer is off (or the length isn't known yet).
     */
    fun remainingMs(choice: SleepChoice, setAtMs: Long, nowMs: Long, positionMs: Long, durationMs: Long?, speed: Float): Long? = when {
        choice.minutes != null -> choice.minutes * 60_000L - (nowMs - setAtMs)
        choice == SleepChoice.END_OF_EPISODE && durationMs != null && durationMs > 0 ->
            ((durationMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong()
        else -> null
    }.let { it?.coerceAtLeast(0) }

    fun state(remainingMs: Long): State = State(
        remainingMs = remainingMs,
        volume = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f),
        showChip = remainingMs < CHIP_MS,
        done = remainingMs <= 0,
    )
}

/**
 * How a title starts from its remembered choices (core/playback/TitlePrefs, the shared store): they
 * beat the app-wide settings; anything never chosen for the title falls back to them.
 */
object TitleStart {
    /** Subtitles: off, or the language to prefer (null = the app-wide rules: Settings language / Android captions). */
    data class Subtitles(val off: Boolean, val language: String?)

    fun subtitles(saved: com.kirtanya.reel.core.playback.TitlePrefs?): Subtitles? = when (val lang = saved?.subtitleLanguage) {
        null -> null
        com.kirtanya.reel.core.playback.TitlePrefs.SUBTITLES_OFF -> Subtitles(off = true, language = null)
        else -> Subtitles(off = false, language = lang)
    }

    /** The remembered speed, snapped to one the player offers; 1× when never chosen. */
    fun speed(saved: com.kirtanya.reel.core.playback.TitlePrefs?): Float = saved?.speed?.let(Speeds::nearest) ?: 1f

    fun picture(saved: com.kirtanya.reel.core.playback.TitlePrefs?, global: com.kirtanya.reel.core.playback.PictureMode) =
        saved?.picture?.let { name -> com.kirtanya.reel.core.playback.PictureMode.entries.firstOrNull { it.name == name } } ?: global
}
