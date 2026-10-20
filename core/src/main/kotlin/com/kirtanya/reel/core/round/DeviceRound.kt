package com.kirtanya.reel.core.round

import com.kirtanya.reel.core.stream.StreamStats
import kotlinx.serialization.Serializable

/**
 * Pure logic of the automated device round (docs/MACHINE_FIRST_PLAN.md step 4, `tool/device_round.sh`):
 * is a device idle enough to drive, what the round measured, and how it compares with the last round.
 * The shell script only drives adb; everything that decides something lives here so it is tested.
 */

/** What one round measured on one device, from its `ReelStats` lines and the crash / ANR logs. */
@Serializable
data class RoundMetrics(
    /** Tap → first frame; null = playback never started (a failed round). */
    val ttffMs: Long? = null,
    /** `cold` = nothing of the sample on the device; `cached` = an earlier round left pieces behind. */
    val warm: String? = null,
    /** Pieces already on the device when the stream was prepared. */
    val haveAtStart: Int? = null,
    val seeks: Int = 0,
    val seekP50Ms: Long = 0,
    val seekP90Ms: Long = 0,
    /** From the `ev=end` line (only written once the player closed); null when it never came. */
    val rebuffers: Int? = null,
    val rebufferMs: Long? = null,
    val waits: Int = 0,
    val waitMaxMs: Long = 0,
    /** libtorrent OUTSTANDING_*_LIMIT_REACHED performance warnings (peers or disk asked to slow down). */
    val outstanding: Int = 0,
    val crashes: Int = 0,
    val anrs: Int = 0,
)

object RoundLog {
    const val APP = "com.kirtanya.reel"

    /** `key=value` fields of one ReelStats line (any logcat format), or null when it isn't one. */
    fun fields(line: String): Map<String, String>? {
        val at = line.indexOf("ev=")
        if (at < 0) return null
        val out = LinkedHashMap<String, String>()
        for (token in line.substring(at).trim().split(' ')) {
            val eq = token.indexOf('=')
            if (eq > 0) out.putIfAbsent(token.substring(0, eq), token.substring(eq + 1))
        }
        return out
    }

    /**
     * [stats]: the round's `logcat -s ReelStats` lines. [sampleKey]: the first 12 hex digits of the
     * sample's info-hash, so a stream of something else (never expected during a round) is not counted.
     * [crashLog]: crash-buffer + ANR lines; only our app's entries count.
     */
    fun parse(stats: List<String>, crashLog: List<String>, sampleKey: String? = null): RoundMetrics {
        var ttff: Long? = null
        var warm: String? = null
        var have: Int? = null
        val seeks = ArrayList<Long>()
        val waits = ArrayList<Long>()
        var rebuf: Pair<Int, Long>? = null
        var outstanding = 0
        var end: Map<String, String>? = null
        for (line in stats) {
            val f = fields(line) ?: continue
            val key = f["key"]
            if (sampleKey != null && key != null && !sampleKey.startsWith(key) && !key.startsWith(sampleKey)) continue
            when (f["ev"]) {
                "start" -> if (warm == null) warm = f["warm"]
                "prepared" -> if (have == null) have = f["have"]?.toIntOrNull()
                "ttff" -> if (ttff == null) ttff = f["t"]?.toLongOrNull()
                "seek" -> f["ms"]?.toLongOrNull()?.let { seeks += it }
                "wait" -> f["ms"]?.toLongOrNull()?.let { waits += it }
                "perf" -> if (f["code"]?.uppercase()?.startsWith("OUTSTANDING") == true) outstanding++
                "end" -> {
                    // rebuf=<count>/<total ms>/<max ms>
                    val parts = f["rebuf"]?.split('/')
                    val n = parts?.getOrNull(0)?.toIntOrNull()
                    val ms = parts?.getOrNull(1)?.toLongOrNull()
                    if (n != null && ms != null) rebuf = n to ms
                    end = f
                }
            }
        }
        // The ev=end summary covers the whole stream: it wins when single events were lost (a busy
        // phone's shared log buffer can drop the start of a round).
        val endSeeks = end?.get("seeks")?.toIntOrNull() ?: 0
        val useEndSeeks = endSeeks > seeks.size
        val endWaits = end?.get("waits")?.split('/')
        val endWaitCount = endWaits?.getOrNull(0)?.toIntOrNull() ?: 0
        val useEndWaits = endWaitCount > waits.size
        return RoundMetrics(
            ttffMs = ttff ?: end?.get("ttff")?.toLongOrNull(),
            warm = warm,
            haveAtStart = have,
            seeks = if (useEndSeeks) endSeeks else seeks.size,
            seekP50Ms = if (useEndSeeks) end?.get("p50")?.toLongOrNull() ?: 0 else StreamStats.percentile(seeks, 50),
            seekP90Ms = if (useEndSeeks) end?.get("p90")?.toLongOrNull() ?: 0 else StreamStats.percentile(seeks, 90),
            rebuffers = rebuf?.first,
            rebufferMs = rebuf?.second,
            waits = if (useEndWaits) endWaitCount else waits.size,
            waitMaxMs = if (useEndWaits) endWaits?.getOrNull(1)?.toLongOrNull() ?: 0 else waits.maxOrNull() ?: 0,
            outstanding = outstanding,
            crashes = crashes(crashLog),
            anrs = anrs(crashLog),
        )
    }

    // Java crash: "Process: com.kirtanya.reel, PID: 123"; native crash tombstone: ">>> com.kirtanya.reel <<<".
    private val javaCrash = Regex("""Process: ${Regex.escape(APP)}(?:[,:\s]|$)""")
    private val nativeCrash = Regex(""">>> ${Regex.escape(APP)}(?:[:\s]|<)""")
    private val anrLine = Regex("""ANR in ${Regex.escape(APP)}(?:[:\s(]|$)""")
    private val anrEvent = Regex("""am_anr.*\b${Regex.escape(APP)}\b""")

    fun crashes(log: List<String>): Int = log.count { javaCrash.containsMatchIn(it) || nativeCrash.containsMatchIn(it) }

    /** Android logs one ANR twice (system "ANR in …" and the am_anr event): the larger count wins. */
    fun anrs(log: List<String>): Int = maxOf(log.count { anrLine.containsMatchIn(it) }, log.count { anrEvent.containsMatchIn(it) })
}

/** Safety gate: drive a device only when nobody is using it (CLAUDE.md "Never disturb playback"). */
object IdleGate {
    data class Dumps(
        /** `dumpsys media_session` */
        val mediaSession: String,
        /** `dumpsys activity activities | grep topResumedActivity` */
        val topResumed: String,
        /** `dumpsys window` (keyguard + dream lines are enough) */
        val window: String,
        /** `dumpsys power` (the mWakefulness line is enough) */
        val power: String,
        /** The HOME activity's package (`cmd package resolve-activity … HOME`). */
        val homePackage: String?,
    )

    sealed interface Result {
        data object Idle : Result
        data class Busy(val reason: String) : Result
    }

    // PlaybackState.toString(): "state=PLAYING(3)" on newer Android, "state=3" on older. Playing, fast
    // forwarding, rewinding and buffering all mean someone is watching or about to.
    private val playing = Regex("""\{state=(?:PLAYING|FAST_FORWARDING|REWINDING|BUFFERING|3|4|5|6)\b""")
    private val activity = Regex("""ActivityRecord\{\S+ \S+ ([\w.]+)/([\w.$]+)""")

    /** Package and activity class (short names expanded) on top, or null when nothing is resumed. */
    fun top(topResumed: String): Pair<String, String>? {
        val m = activity.find(topResumed) ?: return null
        val pkg = m.groupValues[1]
        val cls = m.groupValues[2].let { if (it.startsWith(".")) pkg + it else it }
        return pkg to cls
    }

    /** Quiet this long (no key / touch / wake) before a device counts as idle. */
    const val QUIET_MS = 90_000L

    /**
     * Ms since the last key, touch or wake-up, from `dumpsys power`: `mLastWakeTime=<t> (<n> ms ago)`
     * gives "now" on the same clock as `mLastUserActivityTime…=<t>`. Null when the lines are missing.
     */
    fun msSinceUserActivity(power: String): Long? {
        val wake = Regex("""mLastWakeTime=(\d+) \((\d+) ms ago\)""").find(power) ?: return null
        val now = wake.groupValues[1].toLong() + wake.groupValues[2].toLong()
        val last = Regex("""mLastUserActivityTime(?:\(excludingAttention\))?=(\d+)""").find(power)?.groupValues?.get(1)?.toLong()
        return now - maxOf(last ?: 0L, wake.groupValues[1].toLong())
    }

    fun check(d: Dumps): Result {
        val wake = Regex("""mWakefulness=(\w+)""").find(d.power)?.groupValues?.get(1)
        if (wake != null && wake != "Awake") return Result.Busy("screen is off ($wake); not waking it")
        // Someone browsing (even in our own app) or who just woke the device is using it.
        msSinceUserActivity(d.power)?.let { if (it < QUIET_MS) return Result.Busy("used ${it / 1000} s ago; waiting for ${QUIET_MS / 1000} s of quiet") }
        if (Regex("""isKeyguardShowing=true""").containsMatchIn(d.window)) return Result.Busy("locked; never unlocking it")
        if (Regex("""mShowingDream=true""").containsMatchIn(d.window)) return Result.Busy("screensaver is on")
        if (playing.containsMatchIn(d.mediaSession)) return Result.Busy("something is playing")
        val (pkg, cls) = top(d.topResumed) ?: return Result.Busy("can't tell which app is open")
        // Our player on top (even paused) is someone mid-film; only our home screens count as idle.
        if (pkg == RoundLog.APP) {
            return if (cls == "${RoundLog.APP}.MainActivity") Result.Idle
            else Result.Busy("One Stream is open on ${cls.substringAfterLast('.')}")
        }
        if (d.homePackage != null && pkg == d.homePackage) return Result.Idle
        return Result.Busy("another app is open ($pkg)")
    }
}

/** One line of the before / now table. */
@Serializable
data class RoundRow(val metric: String, val before: String, val now: String, val verdict: String)

/**
 * Compares two rounds with thresholds that tolerate swarm noise: a value is only worse when it is
 * both [ratio] × the old one AND [minDelta] more (and the mirror for better). One swarm is fast, the
 * next slow; a small round has 1–3 seeks, so only big swings mean anything.
 */
object RoundCompare {
    const val BETTER = "better"
    const val SAME = "same"
    const val WORSE = "worse"
    const val NEW = "new"
    const val INFO = "info"
    const val FAIL = "FAIL"
    const val NOT_COMPARABLE = "not comparable"

    private class Rule(
        val metric: String,
        val ratio: Double,
        val minDelta: Long,
        val ms: Boolean,
        val get: (RoundMetrics) -> Long?,
    )

    private val rules = listOf(
        Rule("ttff", 1.5, 5_000, ms = true) { it.ttffMs },
        Rule("seek p50", 1.5, 2_000, ms = true) { m -> m.seekP50Ms.takeIf { m.seeks > 0 } },
        Rule("seek p90", 1.5, 3_000, ms = true) { m -> m.seekP90Ms.takeIf { m.seeks > 0 } },
        Rule("rebuffers", 1.0, 1, ms = false) { it.rebuffers?.toLong() },
        Rule("rebuffer time", 1.5, 5_000, ms = true) { it.rebufferMs },
        Rule("waits", 2.0, 5, ms = false) { it.waits.toLong() },
        Rule("longest wait", 1.5, 3_000, ms = true) { it.waitMaxMs },
        Rule("outstanding warnings", 2.0, 3, ms = false) { it.outstanding.toLong() },
    )

    fun verdict(before: Long?, now: Long?, ratio: Double, minDelta: Long): String = when {
        now == null -> INFO
        before == null -> NEW
        now > before * ratio && now - before > minDelta -> WORSE
        before > now * ratio && before - now > minDelta -> BETTER
        else -> SAME
    }

    fun rows(before: RoundMetrics?, now: RoundMetrics): List<RoundRow> {
        val out = ArrayList<RoundRow>()
        out += RoundRow("start", before?.warm ?: "–", now.warm ?: "–", if (before?.warm != null && before.warm != now.warm) NOT_COMPARABLE else INFO)
        for (r in rules) {
            val b = before?.let(r.get)
            val n = r.get(now)
            var v = verdict(b, n, r.ratio, r.minDelta)
            // A cached start skips the metadata + first pieces: its ttff says nothing next to a cold one.
            if (r.metric == "ttff" && before != null && before.warm != null && now.warm != null && before.warm != now.warm) v = NOT_COMPARABLE
            if (r.metric == "ttff" && now.ttffMs == null) v = FAIL
            out += RoundRow(r.metric, fmt(b, r.ms), fmt(n, r.ms), v)
            if (r.metric == "seek p90") {
                out.add(out.size - 2, RoundRow("seeks", before?.seeks?.toString() ?: "–", now.seeks.toString(), INFO))
            }
        }
        out += RoundRow("crashes", before?.crashes?.toString() ?: "–", now.crashes.toString(), if (now.crashes > 0) FAIL else if (before == null) NEW else SAME)
        out += RoundRow("ANRs", before?.anrs?.toString() ?: "–", now.anrs.toString(), if (now.anrs > 0) FAIL else if (before == null) NEW else SAME)
        return out
    }

    /** Why the round failed (empty = passed). Slow swarms never fail a round; only these do. */
    fun failures(now: RoundMetrics): List<String> = buildList {
        if (now.ttffMs == null) add("playback did not start (no first frame)")
        if (now.crashes > 0) add("${now.crashes} crash(es)")
        if (now.anrs > 0) add("${now.anrs} ANR(s)")
    }

    fun fmt(v: Long?, ms: Boolean): String = when {
        v == null -> "–"
        !ms -> v.toString()
        v < 1_000 -> "$v ms"
        else -> "%.1f s".format(java.util.Locale.ROOT, v / 1000.0)
    }

    /** The table as fixed-width text for the terminal. */
    fun table(rows: List<RoundRow>): String {
        val header = RoundRow("metric", "before", "now", "verdict")
        val all = listOf(header) + rows
        val w = IntArray(3)
        for (r in all) {
            w[0] = maxOf(w[0], r.metric.length); w[1] = maxOf(w[1], r.before.length); w[2] = maxOf(w[2], r.now.length)
        }
        return all.joinToString("\n") { r ->
            "  " + r.metric.padEnd(w[0]) + "  " + r.before.padStart(w[1]) + "  " + r.now.padStart(w[2]) + "  " + r.verdict
        }
    }
}

/**
 * Reads player state from a TV screenshot. On the R4 the video plane never shows in `screencap` (it is
 * black), so anything bright in the strip or panel area is our UI. uiautomator can't help there: it
 * never sees the UI as idle while the strip refreshes twice a second.
 */
object ScreenProbe {
    /** Share of pixels in [x0, x1) × [y0, y1) brighter than mid-grey (0–1). Out-of-range parts are skipped. */
    fun brightShare(img: java.awt.image.BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var bright = 0
        var total = 0
        for (y in maxOf(0, y0) until minOf(img.height, y1)) for (x in maxOf(0, x0) until minOf(img.width, x1)) {
            val p = img.getRGB(x, y)
            val luma = (0.2126 * (p shr 16 and 0xFF) + 0.7152 * (p shr 8 and 0xFF) + 0.0722 * (p and 0xFF))
            total++
            if (luma > 128) bright++
        }
        return if (total == 0) 0.0 else bright.toDouble() / total
    }

    // TvControls in screenshot pixels (960 × 540 dp at 2×): the button row sits 24 dp + 40 dp in from
    // the bottom left; the settings panel is 340 dp wide, 40 dp from the right, 150 dp from the bottom.
    /** The strip's buttons are on screen. */
    fun tvStripUp(img: java.awt.image.BufferedImage): Boolean {
        val sx = img.width / 1920.0
        val sy = img.height / 1080.0
        return brightShare(img, (80 * sx).toInt(), (920 * sy).toInt(), (640 * sx).toInt(), (1032 * sy).toInt()) > 0.02
    }

    /** The settings panel is open. */
    fun tvPanelOpen(img: java.awt.image.BufferedImage): Boolean {
        val sx = img.width / 1920.0
        val sy = img.height / 1080.0
        return brightShare(img, (1180 * sx).toInt(), (300 * sy).toInt(), (1820 * sx).toInt(), (770 * sy).toInt()) > 0.01
    }
}
