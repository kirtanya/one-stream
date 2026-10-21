package com.kirtanya.reel.core.player

import kotlinx.serialization.Serializable

/** What a Skip chip skips. */
enum class SkipKind(val label: String) { INTRO("Skip intro"), RECAP("Skip recap"), CREDITS("Skip credits") }

/** A stretch of an episode, in ms from its start. */
@Serializable
data class SkipWindow(val startMs: Long, val endMs: Long)

/** The user skipped from [startMs] to [endMs] near the start of [episodeKey] (one per episode, latest wins). */
@Serializable
data class IntroSkip(val episodeKey: String, val startMs: Long, val endMs: Long)

/** The user started the next episode with [remainingMs] of [episodeKey] still to go (one per episode, latest wins). */
@Serializable
data class CreditsJump(val episodeKey: String, val remainingMs: Long)

/** What the player learned about one show from the user's own skips (PlayerMemory.shows, keyed by titleKey). */
@Serializable
data class ShowSkips(
    val intros: List<IntroSkip> = emptyList(),
    val credits: List<CreditsJump> = emptyList(),
)

/**
 * Learns a show's intro and credits from what the user does, no network and no fingerprinting:
 *  - intro: a forward jump of 30–150 s that starts in the first 6 minutes of an episode is the user
 *    skipping the intro. The window is the median start and median end over the episodes seen, so one
 *    odd skip doesn't move it. The chip needs one learned episode, so it shows from the 2nd episode on.
 *  - credits: starting the next episode with 10 s – 10 min left says the credits begin there; the
 *    median remaining time moves Up next (and its countdown) to that point from then on.
 */
object SkipLearning {
    const val INTRO_LATEST_START_MS = 6 * 60_000L
    const val MIN_JUMP_MS = 30_000L
    const val MAX_JUMP_MS = 150_000L
    /** A learned window shorter than this is noise (skips that disagree), not an intro. */
    const val MIN_WINDOW_MS = 15_000L
    const val CREDITS_MIN_REMAINING_MS = 10_000L
    const val CREDITS_MAX_REMAINING_MS = 10 * 60_000L
    /** Observations kept per show: recent episodes count, old seasons fade out. */
    const val KEEP = 8
    /** The chip leaves this long before the window ends (a skip there would gain nothing). */
    const val CHIP_TAIL_MS = 3_000L

    /** Is a jump [fromMs] → [toMs] the user skipping an intro? */
    fun isIntroSkip(fromMs: Long, toMs: Long): Boolean {
        val jump = toMs - fromMs
        return fromMs in 0 until INTRO_LATEST_START_MS && jump in MIN_JUMP_MS..MAX_JUMP_MS
    }

    fun addIntro(show: ShowSkips, skip: IntroSkip): ShowSkips =
        show.copy(intros = (show.intros.filter { it.episodeKey != skip.episodeKey } + skip).takeLast(KEEP))

    /** The learned intro, or null before the first learned episode (or when the skips disagree). */
    fun introWindow(show: ShowSkips?): SkipWindow? {
        val intros = show?.intros.orEmpty()
        if (intros.isEmpty()) return null
        val start = median(intros.map { it.startMs })
        val end = median(intros.map { it.endMs })
        return if (end - start >= MIN_WINDOW_MS) SkipWindow(start, end) else null
    }

    /** Next episode started with [remainingMs] left: a credits observation when it's in range, else no change. */
    fun addCredits(show: ShowSkips, episodeKey: String, remainingMs: Long): ShowSkips {
        if (remainingMs !in CREDITS_MIN_REMAINING_MS..CREDITS_MAX_REMAINING_MS) return show
        return show.copy(credits = (show.credits.filter { it.episodeKey != episodeKey } + CreditsJump(episodeKey, remainingMs)).takeLast(KEEP))
    }

    /** How long before the end the credits start, learned; null until the user first jumped. */
    fun creditsRemainingMs(show: ShowSkips?): Long? = show?.credits?.takeIf { it.isNotEmpty() }?.let { c -> median(c.map { it.remainingMs }) }

    internal fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * Joins the user's seeks that come in a burst into one jump: the remote skips 10 s per press, so
 * skipping an intro is many seeks a moment apart (and maybe one back after overshooting). A burst
 * ends after [quietMs] without a seek; its jump is first "from" → last "to".
 */
class SeekBurst(private val quietMs: Long = 2_500) {
    private var from = -1L
    private var to = -1L
    private var lastAt = 0L

    /** A seek at [nowMs]; returns the burst it closed (an earlier one that had gone quiet), if any. */
    fun seek(fromMs: Long, toMs: Long, nowMs: Long): Pair<Long, Long>? {
        val closed = flush(nowMs)
        if (from < 0) from = fromMs
        to = toMs
        lastAt = nowMs
        return closed
    }

    /** The burst, once quiet for [quietMs] (or at once with [force], e.g. leaving the player). */
    fun flush(nowMs: Long, force: Boolean = false): Pair<Long, Long>? {
        if (from < 0 || (!force && nowMs - lastAt < quietMs)) return null
        val out = from to to
        from = -1
        to = -1
        return out
    }
}

/** A chapter of the file (MKV chapters reach the player as track metadata). */
data class ChapterMark(val startMs: Long, val endMs: Long?, val title: String?)

/**
 * Chapter titles that say what they are ("Intro", "Opening", "Recap", "End credits"…). Files that name
 * their chapters beat what was learned: they're exact for this very episode.
 */
object ChapterSkips {
    private val recap = Regex("""\b(recap|previously)\b""", RegexOption.IGNORE_CASE)
    private val intro = Regex("""\b(intro|opening|title sequence|main titles?|opening credits)\b""", RegexOption.IGNORE_CASE)
    private val credits = Regex("""\b(credits|end credits|ending|outro|end titles?)\b""", RegexOption.IGNORE_CASE)
    /** Anime files name them "OP" / "ED" (maybe numbered): only as the whole title, "Ed" is also a name. */
    private val shortIntro = Regex("""^op\s*\d*$""", RegexOption.IGNORE_CASE)
    private val shortCredits = Regex("""^ed\s*\d*$""", RegexOption.IGNORE_CASE)

    fun kindOf(title: String?): SkipKind? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        return when {
            recap.containsMatchIn(t) -> SkipKind.RECAP
            // "Opening credits" is the intro, not the end.
            intro.containsMatchIn(t) || shortIntro.matches(t) -> SkipKind.INTRO
            credits.containsMatchIn(t) || shortCredits.matches(t) -> SkipKind.CREDITS
            else -> null
        }
    }

    /** Skippable chapters with their ends filled in (a chapter without one ends where the next starts, the last at [durationMs]). */
    fun windows(chapters: List<ChapterMark>, durationMs: Long?): List<Pair<SkipKind, SkipWindow>> {
        val sorted = chapters.sortedBy { it.startMs }
        return sorted.mapIndexedNotNull { i, c ->
            val kind = kindOf(c.title) ?: return@mapIndexedNotNull null
            val end = c.endMs?.takeIf { it > c.startMs } ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs ?: return@mapIndexedNotNull null
            if (end <= c.startMs) null else kind to SkipWindow(c.startMs, end)
        }
    }
}

/** Which Skip chip shows where: chapters first, then the learned intro (shows only). */
object SkipPlan {
    /** The chip for [positionMs], or null. */
    fun chipAt(positionMs: Long, chapters: List<Pair<SkipKind, SkipWindow>>, learnedIntro: SkipWindow?): Pair<SkipKind, SkipWindow>? {
        fun inside(w: SkipWindow) = positionMs >= w.startMs && positionMs < w.endMs - SkipLearning.CHIP_TAIL_MS
        // Credits chapters are Up next's job when there's a next episode; the caller filters them.
        chapters.firstOrNull { inside(it.second) }?.let { return it }
        // A file with its own intro / recap chapters says where they are: no guessing on top.
        if (chapters.any { it.first != SkipKind.CREDITS }) return null
        return learnedIntro?.takeIf { inside(it) }?.let { SkipKind.INTRO to it }
    }

    /**
     * When the credits start (ms into the episode): a credits chapter, else the learned remaining time,
     * else null (Up next keeps its last-30-s default).
     */
    fun creditsStartMs(durationMs: Long, chapters: List<Pair<SkipKind, SkipWindow>>, learnedRemainingMs: Long?): Long? {
        chapters.filter { it.first == SkipKind.CREDITS && it.second.startMs > durationMs / 2 }.minByOrNull { it.second.startMs }?.let { return it.second.startMs }
        val learned = learnedRemainingMs ?: return null
        return (durationMs - learned).takeIf { it > durationMs / 2 }
    }

    /** Up next's default: the last 30 s. */
    const val UP_NEXT_DEFAULT_MS = 30_000L

    /** Where the Up next card appears: the credits start when known, never later than the last 30 s. */
    fun upNextAtMs(durationMs: Long, creditsStartMs: Long?): Long =
        minOf(durationMs - UP_NEXT_DEFAULT_MS, creditsStartMs ?: Long.MAX_VALUE).coerceAtLeast(0)
}
