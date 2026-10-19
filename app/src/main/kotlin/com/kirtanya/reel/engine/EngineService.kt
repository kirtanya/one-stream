package com.kirtanya.reel.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
        val engine = ReelApp.get(this).engine
        engine.start()
        lifecycleScope.launch {
            engine.torrents.collectLatest { list ->
                val streaming = list.filter { engine.isStreaming(it.infoHash) }
                val active = list.count { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA }
                val down = list.sumOf { it.downBps.toLong() }
                val up = list.sumOf { it.upBps.toLong() }
                val rates = "↓ ${formatBytes(down)}/s · ↑ ${formatBytes(up)}/s"
                val text = when {
                    streaming.isNotEmpty() -> "Streaming ${streaming.first().name} · $rates"
                    active > 0 -> "$active downloading · $rates"
                    else -> "Idle"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_QUIT) {
            ReelApp.get(this).engine.shutdown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reel)
            .setContentTitle("Reel")
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
        }
    }
}
