package com.kirtanya.reel.core.report

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One saved problem report, as the policy sees it (the app reads these from the reports folder). */
data class ReportEntry(
    /** Folder name, `<yyyyMMdd-HHmmss>-<kind>`. */
    val name: String,
    val sizeBytes: Long,
    /** Epoch ms. */
    val createdAt: Long,
    val kind: String,
    /** What the report is about (a stream's key, a screen); null when nothing more specific. */
    val key: String? = null,
)

/**
 * Which reports to keep and when an automatic one is worth saving (docs/MACHINE_FIRST_PLAN.md step 1).
 * Reports live on the device until pulled to the Mac, so they must never fill it up, and one bad
 * stream must not write a report every few seconds.
 */
object ReportPolicy {
    const val MAX_COUNT = 40
    const val MAX_BYTES = 80L * 1024 * 1024
    /** The TV has ~1.7 GB free and screenshots there are full-HD: keep less. */
    const val TV_MAX_COUNT = 20
    const val TV_MAX_BYTES = 20L * 1024 * 1024
    const val AUTO_WINDOW_MS = 10 * 60_000L
    const val CRASH = "crash"

    /**
     * Folders to delete, oldest first: keep the newest [maxCount] that fit in [maxBytes] together.
     * The newest report always stays (it was just saved), even when it alone is too big.
     */
    fun toDelete(entries: List<ReportEntry>, maxCount: Int = MAX_COUNT, maxBytes: Long = MAX_BYTES): List<ReportEntry> {
        val newestFirst = entries.sortedWith(compareByDescending<ReportEntry> { it.createdAt }.thenByDescending { it.name })
        var used = 0L
        val drop = ArrayList<ReportEntry>()
        newestFirst.forEachIndexed { i, e ->
            val fits = i < maxCount && used + e.sizeBytes <= maxBytes
            if (i == 0 || (fits && drop.isEmpty())) used += e.sizeBytes else drop += e
        }
        return drop.reversed()
    }

    /**
     * Should an automatic report of [kind] about [key] be saved at [now]? At most one per kind + key
     * every [windowMs]; crashes are always saved (each one matters, and they end the process anyway).
     * [recent] holds both saved reports and ones still being written.
     */
    fun shouldAutoSave(recent: List<ReportEntry>, kind: String, key: String?, now: Long, windowMs: Long = AUTO_WINDOW_MS): Boolean {
        if (kind == CRASH) return true
        return recent.none { it.kind == kind && it.key == key && now - it.createdAt in 0 until windowMs }
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `20261020-140312-stall`; [kind] is cut down to safe file-name characters. */
    fun folderName(time: LocalDateTime, kind: String): String =
        stamp.format(time) + "-" + safeKind(kind)

    fun safeKind(kind: String): String =
        kind.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "report" }.take(32)

    /** The kind back from a folder name (null when it isn't one of ours). */
    fun kindOf(name: String): String? =
        Regex("""^\d{8}-\d{6}-([a-z0-9-]+?)(?:-\d+)?$""").matchEntire(name)?.groupValues?.get(1)
}
