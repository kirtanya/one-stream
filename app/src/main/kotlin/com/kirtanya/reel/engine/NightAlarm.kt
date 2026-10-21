package com.kirtanya.reel.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * The overnight window's two device hooks (docs/STREAMING_V2.md §8.3 A):
 * - an alarm at the next window edge, so the flip happens even when Doze has stopped the engine's own timer
 *   (inexact `setAndAllowWhileIdle`: no exact-alarm permission; a few minutes late is fine for a download);
 * - a partial wake lock while downloads run inside the window, so a phone left on the charger overnight
 *   keeps fetching with the screen off.
 */
internal class NightAlarm(private val context: Context) {
    private val wake by lazy {
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reel:night").apply { setReferenceCounted(false) }
    }
    @Volatile private var scheduledAt: Long? = null

    /** The alarm goes to the running EngineService (an app with a foreground service may start its services). */
    private fun intent() = PendingIntent.getService(
        context, REQUEST, Intent(context, EngineService::class.java).setAction(EngineService.ACTION_RULES),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(atMillis: Long?) {
        if (atMillis == scheduledAt) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (atMillis == null) am.cancel(intent()) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent())
            scheduledAt = atMillis
        }
    }

    /** [on] re-arms a 10-minute timeout each call, so a lost release can't hold the CPU for long. */
    fun hold(on: Boolean) {
        runCatching {
            if (on) wake.acquire(10 * 60_000L) else if (wake.isHeld) wake.release()
        }
    }

    val held: Boolean get() = runCatching { wake.isHeld }.getOrDefault(false)

    private companion object {
        const val REQUEST = 3
    }
}
