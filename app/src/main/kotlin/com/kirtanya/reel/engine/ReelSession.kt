package com.kirtanya.reel.engine

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.alert
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.status_flags_t

/**
 * libtorrent4j's session with the alert flood turned off.
 *
 * `SessionManager.start()` overwrites the alert mask with "everything but logs": a JNI object for
 * every 16 KiB block downloaded or uploaded (~1,300/s at 10 MB/s), which costs the TV real CPU and
 * delays the piece-finished alerts streams wait on. We put our narrow mask back right after start,
 * keep the once-a-second torrent update without the per-piece bitfield, and skip session stats.
 */
class ReelSession : SessionManager(false) {
    override fun onAfterStart() {
        super.onAfterStart()
        applySettings(SettingsPack().setInteger(settings_pack.int_types.alert_mask.swigValue(), ALERT_MASK))
    }

    override fun postTorrentUpdates() {
        // Status without the pieces bitfield (the default asks for every status field).
        runCatching { swig()?.post_torrent_updates(status_flags_t()) }
    }

    override fun postSessionStats() {
        if (EngineFlags.verboseStats) super.postSessionStats()
    }

    companion object {
        val ALERT_MASK: Int = alert.error_notification
            .or_(alert.status_notification)
            .or_(alert.storage_notification)
            .or_(alert.piece_progress_notification)
            .or_(alert.performance_warning)
            .to_int()
    }
}
