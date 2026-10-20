package com.kirtanya.reel.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import com.kirtanya.reel.ReelApp
import java.io.InputStream
import java.time.Instant

/**
 * Deaths our own handlers can't see (Android 11+ records why each process ended): ANRs the system
 * killed, native crashes, low-memory kills. Read at app start; each record becomes one report
 * `exit-<reason>`, once (the newest record seen is remembered).
 */
@RequiresApi(30)
internal object ExitReasons {
    private const val PREFS = "reports"
    private const val LAST_SEEN = "lastExitSeen"
    private const val MAX_TRACE = 512 * 1024

    fun check(app: ReelApp, reports: ReportCenter) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(LAST_SEEN, 0)
        val fresh = am.getHistoricalProcessExitReasons(app.packageName, 0, 0)
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        prefs.edit().putLong(LAST_SEEN, fresh.maxOf { it.timestamp }).apply()
        for (info in fresh) {
            val kind = kindOf(info.reason) ?: continue
            // A Java crash our own handler already saved (with its stack): Android's record adds nothing.
            if (info.reason == ApplicationExitInfo.REASON_CRASH && reports.hasCrashNear(info.timestamp)) continue
            val trace = runCatching { info.traceInputStream?.use { it.readCapped(MAX_TRACE) } }.getOrNull()
            val stack = trace?.takeIf { info.reason == ApplicationExitInfo.REASON_ANR }?.let { String(it) }
            // Native crashes come as a tombstone (protobuf): kept as is, its strings are readable.
            val files = trace?.takeIf { info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }?.let { mapOf("tombstone.pb" to it) }.orEmpty()
            val extra = mapOf(
                "reason" to kind.removePrefix("exit-"),
                "description" to info.description,
                "exitTime" to Instant.ofEpochMilli(info.timestamp).toString(),
                "status" to info.status,
                "importance" to info.importance,
                "pssKb" to info.pss,
                "rssKb" to info.rss,
                "pid" to info.pid,
                "process" to info.processName,
            )
            Log.i(ReportCenter.TAG, "previous run ended: $kind at ${extra["exitTime"]} (${info.description})")
            reports.saveExit(kind, info.description ?: kind, extra, info.timestamp, stack, files)
        }
    }

    private fun kindOf(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "exit-anr"
        ApplicationExitInfo.REASON_CRASH -> "exit-crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "exit-crash-native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "exit-low-memory"
        else -> null
    }

    private fun InputStream.readCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
