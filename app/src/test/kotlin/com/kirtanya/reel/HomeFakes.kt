package com.kirtanya.reel

import com.kirtanya.reel.core.homedisk.DiskTest
import com.kirtanya.reel.core.homedisk.DiskTestFacts
import com.kirtanya.reel.core.homedisk.DownloadWhere
import com.kirtanya.reel.core.homedisk.HomeDecision
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.HomeReason
import com.kirtanya.reel.core.homedisk.HomeSettings
import com.kirtanya.reel.core.homedisk.KnownNetwork
import com.kirtanya.reel.core.homedisk.NetworkNow
import com.kirtanya.reel.core.homedisk.NotConnectedReason
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.homedisk.Transport
import com.kirtanya.reel.core.homedisk.WaitReason
import com.kirtanya.reel.core.sync.HomeDest
import com.kirtanya.reel.core.sync.JobState
import com.kirtanya.reel.core.sync.TvJob
import com.kirtanya.reel.homedisk.DiskUi
import com.kirtanya.reel.homedisk.PhoneJob
import com.kirtanya.reel.ui.homedisk.HomeView

/** Home disk states for the screen renders (docs/HOME_DISK.md §17): set up today, no disk yet. */
object FakeHome {
    private const val HOME = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private const val FRIEND = "f1e2d3c4b5a60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private const val OFFICE = "0ff1ce00b5a60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private const val CAFE = "cafe0000b5a60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private const val NOW = 1_761_050_000_000L

    private val settings = HomeSettings(
        share = "usb1", user = "kirtanya", homeFingerprint = HOME, homeSavedAt = NOW, downloadWhere = DownloadWhere.ON_PHONE,
        networks = listOf(
            KnownNetwork(FRIEND, "Wi-Fi · 14 Oct, 19:02", allowed = true, since = NOW - 600_000_000),
            KnownNetwork(OFFICE, "Wi-Fi · 21 Oct, 10:15", allowed = false, since = NOW - 30_000_000),
        ),
    )

    private val noDisk = DiskTestFacts("192.168.1.1", "usb1", reachable = false)

    /** Nothing filled in yet. */
    val notSetUp = HomeView(network = NetworkNow(Transport.WIFI, fingerprint = HOME), downloadVerdict = P2pVerdict.Allow)

    /** Filled in and tested at home: "Not connected — nothing at 192.168.1.1 yet". */
    val notConnected = HomeView(
        settings = settings, passwordSaved = true,
        disk = DiskUi(HomeDiskState.NotConnected(NotConnectedReason.NO_ANSWER), lastTest = DiskTest.line(noDisk), lastTestAt = NOW, home = HomeDecision(true, HomeReason.FINGERPRINT), tvReachable = true),
        tvPaired = true, network = NetworkNow(Transport.WIFI, fingerprint = HOME),
    )

    /** On a café Wi-Fi nobody answered for: downloads wait and the rule asks. */
    val newWifi = notConnected.copy(
        disk = notConnected.disk.copy(state = HomeDiskState.Away, home = HomeDecision(false, HomeReason.OTHER_NETWORK), tvReachable = false),
        network = NetworkNow(Transport.WIFI, fingerprint = CAFE),
        downloadVerdict = P2pVerdict.Ask(CAFE), waiting = WaitReason.UNKNOWN_WIFI,
        held = setOf(Fake.torrents[0].infoHash.lowercase(), Fake.torrents[6].infoHash.lowercase()),
        jobs = listOf(
            PhoneJob("r1", "The Night Manager · S2 E6", "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", createdAt = NOW, updatedAt = NOW),
            PhoneJob("r2", "Dune: Part Two", "08ada5a7a6183aae1e09d831df6748d566095a10", jobId = "j1", state = JobState.WAITING_DISK, note = "Accepted, waiting for home storage", createdAt = NOW - 1000, updatedAt = NOW),
            PhoneJob("r3", "Interstellar", "c9e15763f722f23e98a29decdfae341b98d53056", jobId = "j2", state = JobState.QUEUED, queuePos = 2, createdAt = NOW - 2000, updatedAt = NOW),
        ),
        unsent = setOf("r1"),
    )

    /** Mobile data: downloads wait for Wi-Fi. */
    val mobile = notConnected.copy(
        disk = notConnected.disk.copy(state = HomeDiskState.Away, home = HomeDecision(false, HomeReason.NOT_WIFI), tvReachable = false),
        network = NetworkNow(Transport.CELLULAR, metered = true, fingerprint = "m".repeat(64)),
        downloadVerdict = P2pVerdict.Wait(WaitReason.MOBILE_DATA, canStartAnyway = true), waiting = WaitReason.MOBILE_DATA,
        held = setOf(Fake.torrents[0].infoHash.lowercase()),
    )

    /** The TV, settings from the phone, two at-home requests waiting for the disk. */
    val tv = notConnected.copy(
        isTv = true,
        tvJobs = listOf(
            TvJob("j1", listOf("r2"), "08ada5a7a6183aae1e09d831df6748d566095a10", "Dune: Part Two", state = JobState.WAITING_DISK, createdAt = NOW, updatedAt = NOW, dest = HomeDest.LIBRARY),
            TvJob("j2", listOf("r3"), "c9e15763f722f23e98a29decdfae341b98d53056", "The Night Manager · S2 E6", state = JobState.WAITING_DISK, createdAt = NOW - 1000, updatedAt = NOW),
        ),
    )
}
