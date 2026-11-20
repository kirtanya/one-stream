package com.kirtanya.reel.engine

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kirtanya.reel.MainActivity
import com.kirtanya.reel.R
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.ui.formatBytes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Keeps the process (and the torrent session) alive while Reel works in the background. */
class EngineService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // Off the main thread: building the engine loads libtorrent and start() reads every saved download's resume
        // data into it (byte by byte over JNI), 0.5–1 s on the TV's CPU — the app's first frames froze behind it.
        lifecycleScope.launch {
            val engine = withContext(Dispatchers.Default) { ReelApp.get(this@EngineService).engine.also { it.start() } }
            kotlinx.coroutines.flow.combine(engine.torrents, ReelApp.get(this@EngineService).httpDownloads.snapshots) { torrents, direct -> torrents to direct }.collectLatest { (list, direct) ->
                val streaming = list.filter { engine.isStreaming(it.infoHash) }
                // Direct downloads (HOME_DISK §25) share the line: "2 downloading".
                val active = list.count { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA } +
                    direct.count { it.phase == TorrentPhase.DOWNLOADING }
                val down = list.sumOf { it.downBps.toLong() } + direct.sumOf { it.downBps.toLong() }
                val up = list.sumOf { it.upBps.toLong() }
                val rates = "↓ ${formatBytes(down)}/s · ↑ ${formatBytes(up)}/s"
                // Downloads held by "Download only overnight" (STREAMING_V2 §8.3 A): say why nothing moves.
                val night = list.filter { it.waitingForNight }
                val text = when {
                    streaming.isNotEmpty() -> "Streaming ${streaming.first().name} · $rates"
                    active > 0 -> "$active downloading · $rates"
                    night.isNotEmpty() -> com.kirtanya.reel.ui.plural(night.size, "download") + " " +
                        (night.first().notice?.replaceFirstChar { it.lowercase() } ?: "waiting for night")
                    else -> "Idle"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, streaming = streaming.isNotEmpty()))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_QUIT) {
            quit()
            return START_NOT_STICKY
        }
        // The overnight window's edge alarm (NightAlarm): flip held downloads now, even after Doze.
        if (intent?.action == ACTION_RULES) ReelApp.get(this).engineIfCreated?.evaluateRules()
        if (intent?.action == ACTION_CAST_DOWNLOAD) {
            // Re-parsed from the link text: the request crosses into the service as plain data.
            val request = (com.kirtanya.reel.core.launcher.LauncherLink.parse(intent.dataString) as? com.kirtanya.reel.core.launcher.LauncherLink.Cast)?.request
            if (request != null) lifecycleScope.launch { com.kirtanya.reel.data.CastDownloads.run(this@EngineService, request) }
        }
        return START_STICKY
    }

    /**
     * Closes the app's screens at once, then saves resume data off the main thread (it waits ~1.5 s)
     * and ends the process, so the next launch starts a fresh engine instead of reviving a stopped one.
     */
    private fun quit() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(ActivityManager::class.java).appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        val app = ReelApp.get(this)
        val engine = app.engine
        Thread({
            // Direct downloads save their positions first: they carry on from there at the next start.
            runCatching { app.httpDownloads.shutdown() }
            runCatching { engine.shutdown() }
            ContextCompat.getMainExecutor(this).execute {
                stopSelf()
                Process.killProcess(Process.myPid())
            }
        }, "reel-quit").start()
    }

    private fun notification(text: String, streaming: Boolean = false): Notification {
        // Tap: while streaming, back to the app as it was (the player); otherwise the Downloads tab.
        val open = PendingIntent.getActivity(
            this,
            if (streaming) 2 else 0,
            if (streaming) MainActivity.resume(this) else MainActivity.openDownloads(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Quit", quit)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads & streaming", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_QUIT = "com.kirtanya.reel.QUIT"
        private const val ACTION_CAST_DOWNLOAD = "com.kirtanya.reel.CAST_DOWNLOAD"
        internal const val ACTION_RULES = "com.kirtanya.reel.DOWNLOAD_RULES"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
        }

        /** "Download on TV" (onestream://cast/download): search + download in the background (data/CastDownloads). */
        fun castDownload(context: Context, request: com.kirtanya.reel.core.launcher.CastRequest) {
            val link = com.kirtanya.reel.core.launcher.CastRequest.link(true, request.ref, request.season, request.episode, request.hint)
            ContextCompat.startForegroundService(
                context,
                Intent(context, EngineService::class.java).setAction(ACTION_CAST_DOWNLOAD).setData(android.net.Uri.parse(link)),
            )
        }
    }
}
