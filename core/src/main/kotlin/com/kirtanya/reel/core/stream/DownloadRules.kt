package com.kirtanya.reel.core.stream

import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

/** "Download only overnight": downloads run from [startHour]:00 to [endHour]:00 local time (docs/STREAMING_V2.md §8.3 A). */
@Serializable
data class NightWindow(val enabled: Boolean = false, val startHour: Int = 1, val endHour: Int = 7)

/** How much other downloads may fetch while something is being watched (§8.3 B). Null = no fixed value. */
@Serializable
enum class WatchCap(val totalBytesPerSec: Long?) {
    OFF(null),
    K500(500 * KiB),
    M1(1 * MiB),
    M2(2 * MiB),
    /** 25 % of the measured link. */
    AUTO(null),
}

/** The engine's per-device download rules (Settings → Storage). */
@Serializable
data class DownloadRules(val night: NightWindow = NightWindow(), val watchCap: WatchCap = WatchCap.AUTO)

object NightRule {
    /** True when downloads may run at [minuteOfDay] (0..1439, local). Off, or start == end, means always. */
    fun inWindow(w: NightWindow, minuteOfDay: Int): Boolean {
        if (!w.enabled) return true
        val s = w.startHour.mod(24) * 60
        val e = w.endHour.mod(24) * 60
        return when {
            s == e -> true
            s < e -> minuteOfDay in s until e
            // Crosses midnight (22:00–06:00).
            else -> minuteOfDay >= s || minuteOfDay < e
        }
    }

    /**
     * Does the window keep this torrent waiting now? Streams, "Start now" downloads, finished ones (seeding is not
     * downloading) and downloads a player is reading are never held.
     */
    fun holds(w: NightWindow, minuteOfDay: Int, download: Boolean, startNow: Boolean, finished: Boolean, claimed: Boolean): Boolean =
        download && !startNow && !finished && !claimed && !inWindow(w, minuteOfDay)

    /** The next time the window opens or closes, strictly after [now]; null when it never flips. */
    fun nextEdge(w: NightWindow, now: ZonedDateTime): ZonedDateTime? {
        if (!w.enabled || w.startHour.mod(24) == w.endHour.mod(24)) return null
        val today = now.toLocalDate()
        return listOf(0L, 1L).flatMap { d ->
            listOf(w.startHour.mod(24), w.endHour.mod(24)).map { h -> today.plusDays(d).atTime(h, 0).atZone(now.zone) }
        }.filter { it.isAfter(now) }.minOrNull()
    }

    fun hourLabel(hour: Int): String = hour.mod(24).toString().padStart(2, '0') + ":00"

    /** The badge / notice on a held download. */
    fun waitingLabel(w: NightWindow): String = "Waiting for night (${hourLabel(w.startHour)})"
}

/** What the governor wants applied to the other (unclaimed) downloads right now. */
data class GovernorDecision(
    val capping: Boolean,
    /** Total download cap for the capped torrents together (bytes/s). */
    val totalDown: Long = 0,
    /** Per fetching torrent: total split evenly, never below [WatchGovernor.MIN_PER_TORRENT]. */
    val perTorrentDown: Long = 0,
    val perTorrentUp: Long = 0,
    /** Highest session download rate seen in the window (bytes/s), for telemetry and AUTO. */
    val linkPeak: Long = 0,
)

/**
 * Speed cap while watching (docs/STREAMING_V2.md §8.3 B): while a stream is being watched, other downloads share a
 * small cap so the stream gets the bandwidth; the cap lifts [liftAfterMs] after watching stops. Not thread-safe:
 * one instance, ticked from the engine thread every 2 s.
 */
class WatchGovernor(private val peakWindowMs: Long = 60_000, private val liftAfterMs: Long = 5_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastWatchingAt: Long? = null

    /**
     * [sessionDownBps] = all torrents' payload download rate together, [fetching] = how many capped torrents are
     * downloading (the cap is split between them), [targets] = how many torrents the cap would apply to at all.
     */
    fun tick(nowMs: Long, watching: Boolean, sessionDownBps: Long, choice: WatchCap, fetching: Int, targets: Int): GovernorDecision {
        samples.addLast(nowMs to sessionDownBps.coerceAtLeast(0))
        while (samples.isNotEmpty() && nowMs - samples.first().first > peakWindowMs) samples.removeFirst()
        val peak = samples.maxOf { it.second }
        if (watching) lastWatchingAt = nowMs
        val recent = lastWatchingAt?.let { nowMs - it < liftAfterMs } == true
        if (choice == WatchCap.OFF || !recent || targets == 0) return GovernorDecision(false, linkPeak = peak)
        val total = choice.totalBytesPerSec ?: maxOf(MIN_AUTO_TOTAL, peak * 25 / 100)
        val per = maxOf(MIN_PER_TORRENT, total / maxOf(1, fetching))
        return GovernorDecision(true, total, per, UPLOAD_CAP, peak)
    }

    companion object {
        const val MIN_AUTO_TOTAL = 64 * KiB
        const val MIN_PER_TORRENT = 16 * KiB
        /** Enough to keep tit-for-tat alive (the same value as the metered cap in §8.1). */
        const val UPLOAD_CAP = 32 * KiB

        /** Rewrite a per-torrent limit only when it moves by more than 25 % (libtorrent calls are not free). */
        fun changedEnough(old: Long, new: Long): Boolean = old <= 0 || new <= 0 || kotlin.math.abs(new - old) * 4 > old
    }
}
