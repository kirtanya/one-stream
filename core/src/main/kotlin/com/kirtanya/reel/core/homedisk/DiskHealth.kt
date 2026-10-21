package com.kirtanya.reel.core.homedisk

/** Why the disk isn't connected (docs/HOME_DISK.md §4.5). */
enum class NotConnectedReason {
    /** Set up, but never tested at home yet. */
    NOT_TESTED,
    /** Nothing answers at the address (no disk yet, router off). */
    NO_ANSWER,
    /** The router answers but has no shared folder by that name. */
    NO_SHARE,
    /** Stops all sign-ins until the settings change (a router can lock the account). */
    PASSWORD_REJECTED,
    FULL,
    /** On the TV: the saved home network changed (new router?). */
    NEW_NETWORK,
    ERROR,
}

/** What Settings and the rest of the app show about the disk. */
sealed interface HomeDiskState {
    data object NotSetUp : HomeDiskState
    /** Not at home: the phone never signs in from here; the TV can still reach the disk. */
    data object Away : HomeDiskState
    data object Checking : HomeDiskState
    data class Connected(val protocol: String, val freeBytes: Long, val totalBytes: Long?, val smb1: Boolean) : HomeDiskState
    data class NotConnected(val reason: NotConnectedReason, val detail: String? = null) : HomeDiskState
}

/** The parts of a Test the app measured (null = that step didn't run). */
data class DiskTestFacts(
    val address: String,
    val share: String,
    val reachable: Boolean,
    val signIn: SignIn? = null,
    /** "SMB 3.1.1", "SMB 2.0.2", "SMB1"… */
    val protocol: String? = null,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val writeMBps: Double? = null,
    val readMBps: Double? = null,
    val hashOk: Boolean? = null,
    /** Did a file larger than 4 GiB work (setLength 4 GiB + 1 and a byte at its end)? */
    val bigFiles: Boolean? = null,
    val error: String? = null,
    val smb1: Boolean = false,
) {
    enum class SignIn { OK, PASSWORD_REJECTED, NO_SHARE, FAILED }
}

/**
 * The Test's one plain line (§4.5), e.g. "Connected · SMB 3.1.1 · write 11 MB/s · read 24 MB/s ·
 * 412 GB free · big files OK", and the state it leaves the disk in.
 */
object DiskTest {
    const val TEST_BYTES = 8L * 1024 * 1024
    const val FAT32_LIMIT = 4L * 1024 * 1024 * 1024 - 1

    fun line(f: DiskTestFacts): String = when {
        !f.reachable -> "Not connected — nothing at ${f.address} yet. You can set everything up now."
        f.signIn == DiskTestFacts.SignIn.PASSWORD_REJECTED -> "Password rejected. Check the user and password set on the router."
        f.signIn == DiskTestFacts.SignIn.NO_SHARE -> "The router answers, but has no shared folder called “${f.share}”."
        f.signIn != DiskTestFacts.SignIn.OK -> "Couldn't sign in: ${f.error ?: "unknown error"}"
        f.error != null && f.writeMBps == null -> "Connected · ${f.protocol ?: "SMB"} · but writing failed: ${f.error}"
        else -> buildList {
            add("Connected")
            add(f.protocol ?: "SMB")
            f.writeMBps?.let { add("write ${mbps(it)} MB/s") }
            f.readMBps?.let { add("read ${mbps(it)} MB/s") }
            f.freeBytes?.let { add("${gb(it)} GB free") }
            when (f.bigFiles) {
                true -> add("big files OK")
                false -> add("big files: no (FAT32 disk, 4 GB limit: format it exFAT or NTFS)")
                null -> Unit
            }
            if (f.hashOk == false) add("read-back check FAILED")
        }.joinToString(" · ") + if (f.smb1) " (old SMB1: slower, fine at home)" else ""
    }

    /** The state a finished Test leaves behind. */
    fun state(f: DiskTestFacts): HomeDiskState = when {
        !f.reachable -> HomeDiskState.NotConnected(NotConnectedReason.NO_ANSWER)
        f.signIn == DiskTestFacts.SignIn.PASSWORD_REJECTED -> HomeDiskState.NotConnected(NotConnectedReason.PASSWORD_REJECTED)
        f.signIn == DiskTestFacts.SignIn.NO_SHARE -> HomeDiskState.NotConnected(NotConnectedReason.NO_SHARE)
        f.signIn != DiskTestFacts.SignIn.OK -> HomeDiskState.NotConnected(NotConnectedReason.ERROR, f.error)
        else -> HomeDiskState.Connected(f.protocol ?: "SMB", f.freeBytes ?: 0, f.totalBytes, f.smb1)
    }

    /** A Test that signed in and wrote + read back correctly: it saves the home network and writes disk.json. */
    fun succeeded(f: DiskTestFacts): Boolean = f.reachable && f.signIn == DiskTestFacts.SignIn.OK && f.hashOk == true

    /** Status card text (§17.1). */
    fun status(s: HomeDiskState, address: String): String = when (s) {
        HomeDiskState.NotSetUp -> "Not set up"
        HomeDiskState.Away -> "Away from home · your TV can reach the disk"
        HomeDiskState.Checking -> "Checking…"
        is HomeDiskState.Connected -> "Connected · ${gb(s.freeBytes)} GB free" + (s.totalBytes?.let { " of ${gb(it)} GB" } ?: "")
        is HomeDiskState.NotConnected -> when (s.reason) {
            NotConnectedReason.NOT_TESTED -> "Not connected yet · press Test when you're at home"
            NotConnectedReason.NO_ANSWER -> "Not connected — nothing at $address yet. You can set everything up now."
            NotConnectedReason.NO_SHARE -> "Not connected — the router has no such shared folder"
            NotConnectedReason.PASSWORD_REJECTED -> "Password rejected"
            NotConnectedReason.FULL -> "Home disk is full"
            NotConnectedReason.NEW_NETWORK -> "New router? Test the disk again"
            NotConnectedReason.ERROR -> "Not connected" + (s.detail?.let { " — $it" } ?: "")
        }
    }

    private fun mbps(v: Double) = if (v >= 10) v.toInt().toString() else "%.1f".format(v)
    private fun gb(bytes: Long) = (bytes / 1_000_000_000L).toString()
}

/**
 * When the connection manager may try again (§4.5): 5 s, 15 s, 30 s, 60 s, then every 5 min. A rejected
 * password stops everything until the settings change, and never more than 3 sign-ins an hour, so a
 * router can't lock the account.
 */
class ConnectPolicy {
    private val attempts = ArrayDeque<Long>()
    private var failures = 0
    private var stopped = false

    fun mayTry(now: Long, lastAttemptAt: Long?): Boolean {
        if (stopped) return false
        while (attempts.isNotEmpty() && now - attempts.first() >= HOUR) attempts.removeFirst()
        if (attempts.size >= MAX_PER_HOUR) return false
        if (lastAttemptAt == null || failures == 0) return true
        return now - lastAttemptAt >= backoffMs(failures)
    }

    fun recordAttempt(now: Long) { attempts.addLast(now) }

    fun recordResult(state: HomeDiskState) {
        when (state) {
            is HomeDiskState.Connected -> failures = 0
            is HomeDiskState.NotConnected -> {
                failures++
                if (state.reason == NotConnectedReason.PASSWORD_REJECTED) stopped = true
            }
            else -> Unit
        }
    }

    /** The user saved new settings: start fresh. */
    fun reset() {
        stopped = false
        failures = 0
        attempts.clear()
    }

    val isStopped: Boolean get() = stopped

    companion object {
        const val HOUR = 3_600_000L
        const val MAX_PER_HOUR = 3
        private val STEPS = longArrayOf(5_000, 15_000, 30_000, 60_000)
        fun backoffMs(failures: Int): Long = if (failures <= 0) 0 else STEPS.getOrElse(failures - 1) { 5 * 60_000L }
    }
}
