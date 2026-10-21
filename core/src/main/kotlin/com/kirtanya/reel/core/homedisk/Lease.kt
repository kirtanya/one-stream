package com.kirtanya.reel.core.homedisk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The single-writer lease on a title folder, `.onestream/lease.json` (docs/HOME_DISK.md §3.4). The holder
 * rewrites it every [RENEW_MS] with `counter + 1`. Staleness is judged by counters and the observer's own
 * monotonic clock, never timestamps, so the devices' clocks never matter.
 */
@Serializable
data class Lease(val device: String, val deviceName: String, val op: String, val counter: Long = 0, val since: Long) {
    fun renewed(): Lease = copy(counter = counter + 1)

    companion object {
        const val RENEW_MS = 60_000L
        const val STALE_MS = 10 * 60_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(l: Lease): String = json.encodeToString(serializer(), l)
        fun decode(text: String?): Lease? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Watches someone else's lease. [observe] with each reading and this device's monotonic time; the lease is
 * stale once the same holder shows the same counter for [Lease.STALE_MS] of our time.
 */
class LeaseWatch {
    private var lastHolder: String? = null
    private var lastCounter = Long.MIN_VALUE
    private var unchangedSince = 0L

    /** Returns true when the lease may be taken over (deleted and acquired). A missing lease is free. */
    fun observe(lease: Lease?, monotonicMs: Long): Boolean {
        if (lease == null) {
            lastHolder = null
            return true
        }
        if (lease.device != lastHolder || lease.counter != lastCounter) {
            lastHolder = lease.device
            lastCounter = lease.counter
            unchangedSince = monotonicMs
            return false
        }
        return monotonicMs - unchangedSince >= Lease.STALE_MS
    }
}
