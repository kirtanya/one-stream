package com.kirtanya.reel.core.stream

/** Phone or TV: sets connection budgets and timeouts. */
enum class FormFactor { PHONE, TV }

/** libtorrent disk backend: mmap (64-bit) or posix on the network thread (the 32-bit TV). */
enum class DiskBackend { MMAP, POSIX }

/**
 * libtorrent session settings for streaming, as plain data (the engine maps each field to its
 * settings_pack name). See docs/STREAMING_V2.md §8.2 for the reasoning behind each value.
 *
 * The big wins: announce to every tracker at once (libtorrent's default waits ~10 s per dead
 * tracker), a faster connection ramp, shorter request queues so urgent blocks aren't stuck behind
 * old requests, and a bigger disk-write queue on phones.
 */
data class SessionTuning(
    val alertQueueSize: Int,
    val announceToAllTrackers: Boolean,
    val announceToAllTiers: Boolean,
    val maxMetadataSize: Int,
    val connectionsLimit: Int,
    val connectionSpeed: Int,
    val torrentConnectBoost: Int,
    val smoothConnects: Boolean,
    val peerConnectTimeout: Int,
    val handshakeTimeout: Int,
    val minReconnectTime: Int,
    val pieceTimeout: Int,
    val requestTimeout: Int,
    val requestQueueTime: Int,
    /**
     * Most block requests (16 KiB) outstanding to one peer. libtorrent's 500 = 8 MiB in flight; a
     * fast, far peer needs more (logged as OUTSTANDING_REQUEST_LIMIT_REACHED on the TV).
     */
    val maxOutRequestQueue: Int,
    /** Null = keep libtorrent's default. */
    val maxQueuedDiskBytes: Int?,
    val unchokeSlotsLimit: Int,
    val activeDownloads: Int,
    val activeSeeds: Int,
    val activeLimit: Int,
    /** Null = keep libtorrent's default. */
    val inactiveDownRate: Int?,
    val inactiveUpRate: Int?,
    val dhtAnnounceInterval: Int,
    val stopTrackerTimeout: Int,
    /** Null = keep libtorrent's default. */
    val maxPeerlistSize: Int?,
    val maxPausedPeerlistSize: Int?,
) {
    companion object {
        fun of(formFactor: FormFactor, disk: DiskBackend): SessionTuning {
            val tv = formFactor == FormFactor.TV
            val mmap = disk == DiskBackend.MMAP
            return SessionTuning(
                alertQueueSize = 4000,
                announceToAllTrackers = true,
                announceToAllTiers = true,
                maxMetadataSize = (if (tv) 16 else 30) * MiB.toInt(),
                connectionsLimit = if (tv) 100 else 200,
                connectionSpeed = if (tv) 40 else 60,
                torrentConnectBoost = if (tv) 40 else 80,
                // The TV's network thread also does its disk I/O: don't burst connects there.
                smoothConnects = !mmap,
                peerConnectTimeout = if (tv) 8 else 7,
                handshakeTimeout = 10,
                minReconnectTime = 20,
                pieceTimeout = 15,
                requestTimeout = if (mmap) 30 else 40,
                requestQueueTime = 2,
                maxOutRequestQueue = if (tv) 1500 else 2000,
                maxQueuedDiskBytes = if (mmap) (8 * MiB).toInt() else null,
                unchokeSlotsLimit = if (tv) 4 else 8,
                activeDownloads = if (tv) 2 else 3,
                activeSeeds = if (tv) 0 else 2,
                activeLimit = if (tv) 4 else 10,
                inactiveDownRate = if (tv) null else 32 * KiB.toInt(),
                inactiveUpRate = if (tv) null else 32 * KiB.toInt(),
                dhtAnnounceInterval = 300,
                stopTrackerTimeout = 2,
                maxPeerlistSize = if (tv) 2000 else null,
                maxPausedPeerlistSize = if (tv) 500 else null,
            )
        }
    }
}
