package com.kirtanya.reel.core.homedisk

import com.kirtanya.reel.core.sync.JobState

/** How a badge reads: plain, accent (good news), or warn (waiting on something). */
enum class BadgeTone { PLAIN, ACCENT, WARN }

data class Badge(val label: String, val tone: BadgeTone = BadgeTone.PLAIN)

/** A title's home-disk facts on the phone's Downloads screen. */
data class TitleWhere(
    /** A finished download on this phone. */
    val phoneDone: Boolean = false,
    /** The phone download is held by the network rule. */
    val held: WaitReason? = null,
    val disk: DiskPresence = DiskPresence.UNKNOWN,
    val copyPercent: Int? = null,
    /** An at-home request the TV hasn't acknowledged yet. */
    val waitingForTv: Boolean = false,
    val atHome: JobState? = null,
    val atHomePercent: Int? = null,
    val queuePos: Int? = null,
)

/**
 * The badge under a Downloads row (docs/HOME_DISK.md §17.4). Null when there is nothing to say beyond the
 * row's own status (a download in progress on the phone, or home storage not set up).
 */
object HomeBadges {
    fun badge(w: TitleWhere, homeSetUp: Boolean): Badge? {
        w.held?.let { return Badge(heldLabel(it), BadgeTone.WARN) }
        if (w.waitingForTv) return Badge("Waiting to reach your TV", BadgeTone.WARN)
        w.atHome?.let { return atHomeBadge(it, w.atHomePercent, w.queuePos) }
        if (w.disk == DiskPresence.COPYING) return Badge("Copying to home disk ${w.copyPercent ?: 0} %")
        if (!homeSetUp) return null
        return when {
            w.phoneDone && w.disk == DiskPresence.ON_DISK -> Badge("Phone + home disk", BadgeTone.ACCENT)
            w.disk == DiskPresence.ON_DISK -> Badge("Home disk", BadgeTone.ACCENT)
            w.phoneDone -> Badge("Phone")
            else -> null
        }
    }

    fun heldLabel(r: WaitReason): String = when (r) {
        WaitReason.MOBILE_DATA -> "Waiting for Wi-Fi"
        WaitReason.NO_NETWORK -> "Waiting for a connection"
        WaitReason.WORK_NETWORK, WaitReason.UNKNOWN_WIFI -> "Waiting for home Wi-Fi"
    }

    private fun atHomeBadge(state: JobState, percent: Int?, queuePos: Int?): Badge = when (state) {
        JobState.QUEUED -> Badge(if (queuePos != null && queuePos > 1) "Queued at home #$queuePos" else "Queued at home")
        JobState.METADATA, JobState.DOWNLOADING -> Badge("Downloading at home ${percent ?: 0} %")
        JobState.VERIFYING -> Badge("Checking at home")
        JobState.DONE -> Badge("Home disk", BadgeTone.ACCENT)
        JobState.WAITING_DISK -> Badge("At home · waiting for the disk", BadgeTone.WARN)
        JobState.DISK_FULL -> Badge("Home disk is full", BadgeTone.WARN)
        JobState.WAITING_SPACE -> Badge("Waiting for space on the TV", BadgeTone.WARN)
        JobState.FAILED -> Badge("Couldn't download at home", BadgeTone.WARN)
        JobState.CANCELLED -> Badge("Cancelled at home")
    }
}
