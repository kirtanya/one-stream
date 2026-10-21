package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.Badge
import com.kirtanya.reel.core.homedisk.BadgeTone
import com.kirtanya.reel.core.homedisk.ConnectPolicy
import com.kirtanya.reel.core.homedisk.DeleteAction
import com.kirtanya.reel.core.homedisk.DeleteWhere
import com.kirtanya.reel.core.homedisk.DiskLayout
import com.kirtanya.reel.core.homedisk.DiskPresence
import com.kirtanya.reel.core.homedisk.DiskTest
import com.kirtanya.reel.core.homedisk.DiskTestFacts
import com.kirtanya.reel.core.homedisk.DownloadWhere
import com.kirtanya.reel.core.homedisk.DownloadWhereSheet
import com.kirtanya.reel.core.homedisk.EvictReason
import com.kirtanya.reel.core.homedisk.HomeBadges
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.HomeNetwork
import com.kirtanya.reel.core.homedisk.HomeReason
import com.kirtanya.reel.core.homedisk.HomeSettings
import com.kirtanya.reel.core.homedisk.KnownNetworks
import com.kirtanya.reel.core.homedisk.Lease
import com.kirtanya.reel.core.homedisk.LeaseWatch
import com.kirtanya.reel.core.homedisk.LibraryName
import com.kirtanya.reel.core.homedisk.LinkFacts
import com.kirtanya.reel.core.homedisk.ManifestFile
import com.kirtanya.reel.core.homedisk.ManifestOrigin
import com.kirtanya.reel.core.homedisk.ManifestWriter
import com.kirtanya.reel.core.homedisk.NetworkFingerprint
import com.kirtanya.reel.core.homedisk.NetworkNow
import com.kirtanya.reel.core.homedisk.NotConnectedReason
import com.kirtanya.reel.core.homedisk.Outbox
import com.kirtanya.reel.core.homedisk.P2pRule
import com.kirtanya.reel.core.homedisk.P2pUse
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.homedisk.PartsMap
import com.kirtanya.reel.core.homedisk.RetentionPolicy
import com.kirtanya.reel.core.homedisk.RetentionSettings
import com.kirtanya.reel.core.homedisk.StoredTitle
import com.kirtanya.reel.core.homedisk.StreamToken
import com.kirtanya.reel.core.homedisk.TitleKind
import com.kirtanya.reel.core.homedisk.TitleManifest
import com.kirtanya.reel.core.homedisk.TitleWhere
import com.kirtanya.reel.core.homedisk.Transport
import com.kirtanya.reel.core.homedisk.WaitReason
import com.kirtanya.reel.core.report.Redactor
import com.kirtanya.reel.core.sync.HomeOps
import com.kirtanya.reel.core.sync.HomeRpc
import com.kirtanya.reel.core.sync.JobState
import com.kirtanya.reel.core.sync.RpcRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

class DiskLayoutTest {
    @Test fun namesAreCleanedForRouterFileSystems() {
        assertEquals("Dune Part Two", DiskLayout.clean("Dune: Part  Two"))
        assertEquals("a b c d", DiskLayout.clean("a<b>c|d"))
        assertEquals("What If", DiskLayout.clean("What If?..."))
        assertEquals("tab", DiskLayout.clean("\ttab\u0001"))
        assertEquals("Untitled", DiskLayout.clean("???"))
        assertEquals("hidden", DiskLayout.clean(".hidden"))
    }

    @Test fun componentsAreCutBeforeTheExtension() {
        val long = "x".repeat(200) + ".mkv"
        val cut = DiskLayout.clean(long)
        assertEquals(DiskLayout.MAX_COMPONENT, cut.length)
        assertTrue(cut.endsWith(".mkv"))
    }

    @Test fun libraryFolders() {
        assertEquals("OneStream/library/Movies/Dune Part Two (2024)", DiskLayout.titleFolder(LibraryName("Dune: Part Two", 2024), "x"))
        assertEquals("OneStream/library/Shows/Severance (2022)/Season 02", DiskLayout.titleFolder(LibraryName("Severance", 2022, isShow = true, season = 2), "x"))
        assertEquals("OneStream/library/Other/Big Buck Bunny", DiskLayout.titleFolder(null, "Big Buck Bunny"))
    }

    @Test fun wholePathStaysUnder240() {
        val folder = DiskLayout.titleFolder(LibraryName("t".repeat(110), 2024), "x")
        val path = DiskLayout.libraryFile(folder, "f".repeat(118) + ".mkv")
        assertTrue(path.length <= DiskLayout.MAX_PATH, "${path.length}")
        assertTrue(path.endsWith(".mkv"))
        assertTrue(path.startsWith(folder))
    }

    @Test fun takenNamesGetANumberIgnoringCase() {
        assertEquals("Dune (2021)", DiskLayout.unique("Dune (2021)", listOf("Other")))
        assertEquals("Dune (2021) (2)", DiskLayout.unique("Dune (2021)", listOf("dune (2021)")))
        assertEquals("Dune (2021) (3)", DiskLayout.unique("Dune (2021)", listOf("Dune (2021)", "DUNE (2021) (2)")))
    }

    @Test fun pathsUseTheKey() {
        assertEquals("OneStream/incoming/$KEY/.onestream/parts/0.parts", DiskLayout.parts(DiskLayout.incoming(KEY), 0))
        assertEquals("OneStream/.trash/$KEY-202610211830", DiskLayout.trash(KEY, "202610211830"))
        assertTrue(runCatching { DiskLayout.incoming("../../etc") }.isFailure)
    }

    @Test fun manifestRoundTrip() {
        val m = TitleManifest(
            key = KEY, name = "Dune.Part.Two.2024.2160p", origin = ManifestOrigin("movie", 693134, label = "Dune: Part Two (2024)"),
            pieceLength = 4_194_304, totalSize = 25_165_824_000,
            files = listOf(ManifestFile(0, 0, 25_165_824_000, "Dune.mkv", "Movies/Dune Part Two (2024)/Dune.mkv")),
            writer = ManifestWriter("tv:9f2c11aa", ManifestWriter.CONVEYOR), createdAt = 1, lastUsedAt = 1,
        )
        assertEquals(m, TitleManifest.decode(TitleManifest.encode(m)))
        assertTrue(TitleManifest.encode(m).contains("\"state\":\"INCOMING\""))
        assertNull(TitleManifest.decode("{broken"))
    }
}

class PartsMapTest {
    @Test fun roundTrip() {
        val m = PartsMap(1000, listOf(0L until 100L, 200L until 300L), generation = 7, updatedAtMs = 99)
        val back = PartsMap.decode(m.encode())!!
        assertEquals(m.ranges, back.ranges)
        assertEquals(7, back.generation)
        assertEquals(1000, back.fileSize)
    }

    @Test fun crcDamageFallsBackToPrev() {
        val prev = PartsMap(1000, listOf(0L until 100L)).encode()
        val cur = PartsMap(1000, listOf(0L until 500L), generation = 1).encode().also { it[40] = (it[40] + 1).toByte() }
        assertNull(PartsMap.decode(cur))
        assertEquals(listOf(0L until 100L), PartsMap.readBest(cur, prev, 1000).ranges)
        assertEquals(0, PartsMap.readBest(cur, null, 1000).rangeCount)
        assertEquals(0, PartsMap.readBest(byteArrayOf(1, 2, 3), null, 1000).rangeCount)
        // A map of another file size is not this file's map.
        assertEquals(0, PartsMap.readBest(PartsMap(999, listOf(0L until 10L)).encode(), null, 1000).rangeCount)
    }

    @Test fun overlappingAndTouchingInsertsMerge() {
        val m = PartsMap(1000)
        m.add(100, 200)
        m.add(300, 400)
        m.add(150, 320)
        assertEquals(listOf(100L until 400L), m.ranges)
        m.add(400, 450) // touching
        m.add(0, 100)
        assertEquals(listOf(0L until 450L), m.ranges)
        m.add(500, 500) // empty
        assertEquals(1, m.rangeCount)
    }

    @Test fun containsAcrossRangeEdges() {
        val m = PartsMap(1000, listOf(0L until 100L, 200L until 300L))
        assertTrue(m.contains(0, 100))
        assertTrue(m.contains(250, 300))
        assertFalse(m.contains(99, 101))
        assertFalse(m.contains(100, 200))
        assertFalse(m.contains(50, 250))
        assertTrue(m.contains(500, 500))
        assertEquals(100L, m.firstGap(0))
        assertEquals(150L, m.firstGap(150))
        assertEquals(300L, m.firstGap(200))
    }

    @Test fun sizeZeroIsComplete() {
        val m = PartsMap(0)
        assertTrue(m.complete)
        assertNull(m.firstGap())
        assertEquals(0, PartsMap.decode(m.encode())!!.fileSize)
    }

    @Test fun completeFlagAndLastGap() {
        val m = PartsMap(1000, listOf(0L until 1000L))
        assertTrue(m.complete)
        assertNull(m.firstGap(10))
        assertEquals(1, m.encode()[7].toInt()) // flags low byte
    }

    @Test fun rarestFirstWorstCase() {
        val m = PartsMap(10_000_000)
        for (i in 0 until 5000) m.add(i * 2000L, i * 2000L + 1000)
        assertEquals(5000, m.rangeCount)
        val bytes = m.encode()
        assertEquals(36 + 16 * 5000 + 4, bytes.size)
        assertEquals(5000, PartsMap.decode(bytes)!!.rangeCount)
    }

    @Test fun rejectsRangesOutsideTheFile() {
        assertTrue(runCatching { PartsMap(10).add(5, 11) }.isFailure)
    }
}

class LeaseTest {
    @Test fun staleOnlyAfterTenMinutesOfTheSameCounter() {
        val w = LeaseWatch()
        val l = Lease("tv:1", "TV", "conveyor", counter = 3, since = 0)
        assertFalse(w.observe(l, 0))
        assertFalse(w.observe(l, Lease.STALE_MS - 1))
        assertTrue(w.observe(l, Lease.STALE_MS))
    }

    @Test fun aRenewedCounterRestartsTheClock() {
        val w = LeaseWatch()
        val l = Lease("tv:1", "TV", "conveyor", counter = 3, since = 0)
        w.observe(l, 0)
        assertFalse(w.observe(l.renewed(), 9 * 60_000))
        assertFalse(w.observe(l.renewed(), 18 * 60_000))
        assertTrue(w.observe(l.renewed(), 19 * 60_000 + 1))
        assertTrue(w.observe(null, 0))
        assertEquals(l, Lease.decode(Lease.encode(l)))
    }
}

class HomeNetworkTest {
    private val homeFacts = LinkFacts(listOf("192.168.1.1"), listOf("192.168.1.1"), "192.168.1.0/24")
    private val home = NetworkNetwork.wifi(NetworkFingerprint.of(homeFacts))
    private val office = NetworkNetwork.wifi(NetworkFingerprint.of(LinkFacts(listOf("10.0.0.1"), listOf("10.0.0.2"), "10.0.0.0/16", domains = "corp.example")))

    private object NetworkNetwork {
        fun wifi(fp: String) = NetworkNow(Transport.WIFI, fingerprint = fp)
    }

    @Test fun fingerprintIgnoresOrderButNotChanges() {
        val a = NetworkFingerprint.of(LinkFacts(listOf("192.168.1.1"), listOf("8.8.8.8", "1.1.1.1"), "192.168.1.0/24"))
        val b = NetworkFingerprint.of(LinkFacts(listOf("192.168.1.1"), listOf("1.1.1.1", "8.8.8.8"), "192.168.1.0/24"))
        val c = NetworkFingerprint.of(LinkFacts(listOf("192.168.1.254"), listOf("1.1.1.1", "8.8.8.8"), "192.168.1.0/24"))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
        assertEquals("192.168.1.0/24", NetworkFingerprint.ipv4Prefix("192.168.1.37", 24))
        assertEquals("10.64.0.0/10", NetworkFingerprint.ipv4Prefix("10.100.3.4", 10))
        assertNull(NetworkFingerprint.ipv4Prefix("fe80::1", 64))
    }

    @Test fun privatePeers() {
        listOf("192.168.1.5", "10.1.2.3", "172.16.0.1", "172.31.255.255", "169.254.1.1", "127.0.0.1", "100.64.0.1", "100.127.1.1", "/192.168.0.2")
            .forEach { assertTrue(NetworkFingerprint.isPrivateOrTailnet(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.0.1", "fe80::1", "", null)
            .forEach { assertFalse(NetworkFingerprint.isPrivateOrTailnet(it), "$it") }
        assertTrue(NetworkFingerprint.isTailnet("100.101.102.103"))
        assertFalse(NetworkFingerprint.isPrivateLan("100.101.102.103"))
        assertTrue(NetworkFingerprint.isPrivateLan("192.168.1.128"))
    }

    @Test fun decisionTable() {
        val saved = home.fingerprint
        assertEquals(HomeReason.NO_NETWORK, HomeNetwork.decide(NetworkNow.NONE, saved, false).reason)
        assertEquals(HomeReason.NOT_WIFI, HomeNetwork.decide(NetworkNow(Transport.CELLULAR, true, "x"), saved, true).reason)
        HomeNetwork.decide(home, saved, false).let { assertTrue(it.isHome); assertEquals(HomeReason.FINGERPRINT, it.reason) }
        HomeNetwork.decide(office, saved, false).let { assertFalse(it.isHome); assertEquals(HomeReason.OTHER_NETWORK, it.reason) }
        HomeNetwork.decide(home, null, false).let { assertFalse(it.isHome); assertEquals(HomeReason.NO_HOME_SAVED, it.reason) }
        // The paired TV answering is home even before (or after a router change of) the saved fingerprint, and saves the new one.
        HomeNetwork.decide(home, null, true).let { assertTrue(it.isHome); assertEquals(home.fingerprint, it.saveFingerprint) }
        HomeNetwork.decide(home, saved, true).let { assertTrue(it.isHome); assertNull(it.saveFingerprint) }
    }

    @Test fun neverSignsInOffTheHomeNetwork() {
        val saved = home.fingerprint
        assertTrue(HomeNetwork.maySignIn(HomeNetwork.decide(home, saved, false), userConfirmedHome = false, markedWork = false))
        assertFalse(HomeNetwork.maySignIn(HomeNetwork.decide(office, saved, false), userConfirmedHome = true, markedWork = false))
        assertFalse(HomeNetwork.maySignIn(HomeNetwork.decide(NetworkNow(Transport.CELLULAR, true, "x"), saved, false), true, false))
        // First Test with no home saved: only when the user says they're at home, and never on a work Wi-Fi.
        assertFalse(HomeNetwork.maySignIn(HomeNetwork.decide(home, null, false), userConfirmedHome = false, markedWork = false))
        assertTrue(HomeNetwork.maySignIn(HomeNetwork.decide(home, null, false), userConfirmedHome = true, markedWork = false))
        assertFalse(HomeNetwork.maySignIn(HomeNetwork.decide(office, null, false), userConfirmedHome = true, markedWork = true))
        assertFalse(HomeNetwork.maySignIn(HomeNetwork.decide(home, saved, false), userConfirmedHome = true, markedWork = true))
    }
}

class P2pRuleTest {
    private val homeFp = "h".repeat(64)
    private val officeFp = "o".repeat(64)
    private val cafeFp = "c".repeat(64)
    private val friendFp = "f".repeat(64)
    private val known = KnownNetworks(allowed = setOf(friendFp), work = setOf(officeFp))
    private fun wifi(fp: String?) = NetworkNow(Transport.WIFI, fingerprint = fp)
    private val mobile = NetworkNow(Transport.CELLULAR, metered = true, fingerprint = "m".repeat(64))

    private fun d(net: NetworkNow, isHome: Boolean = false, use: P2pUse = P2pUse.DOWNLOAD, mobileData: Boolean = false, anyway: Boolean = false) =
        P2pRule.decide(use, net, isHome, known, mobileData, anyway)

    @Test fun home() {
        assertEquals(P2pVerdict.Allow, d(wifi(homeFp), isHome = true))
        assertEquals(P2pVerdict.Allow, d(wifi(homeFp), isHome = true, use = P2pUse.STREAM))
    }

    @Test fun mobileDataWaitsForWifiUnlessAllowed() {
        assertEquals(P2pVerdict.Wait(WaitReason.MOBILE_DATA, canStartAnyway = true), d(mobile))
        assertEquals(P2pVerdict.Allow, d(mobile, anyway = true))
        assertEquals(P2pVerdict.Allow, d(mobile, mobileData = true))
        // Play on mobile data is unchanged.
        assertEquals(P2pVerdict.Allow, d(mobile, use = P2pUse.STREAM))
    }

    @Test fun allowedWifi() = assertEquals(P2pVerdict.Allow, d(wifi(friendFp)))

    @Test fun workNeverEvenWhenAskedTo() {
        assertEquals(P2pVerdict.Wait(WaitReason.WORK_NETWORK), d(wifi(officeFp)))
        assertEquals(P2pVerdict.Wait(WaitReason.WORK_NETWORK), d(wifi(officeFp), anyway = true, mobileData = true))
        assertEquals(P2pVerdict.Block(WaitReason.WORK_NETWORK), d(wifi(officeFp), use = P2pUse.STREAM))
        // Marked work wins over anything that looks like home.
        assertEquals(P2pVerdict.Block(WaitReason.WORK_NETWORK), d(wifi(officeFp), isHome = true, use = P2pUse.STREAM))
    }

    @Test fun unknownWifiAsksOnceForBothUses() {
        assertEquals(P2pVerdict.Ask(cafeFp), d(wifi(cafeFp)))
        assertEquals(P2pVerdict.Ask(cafeFp), d(wifi(cafeFp), use = P2pUse.STREAM))
        assertEquals(P2pVerdict.Ask(cafeFp), d(wifi(cafeFp), anyway = true))
        assertEquals(WaitReason.UNKNOWN_WIFI, P2pRule.waitReason(d(wifi(cafeFp))))
        assertEquals(P2pVerdict.Wait(WaitReason.UNKNOWN_WIFI), d(wifi(null)))
    }

    @Test fun noNetworkAndTv() {
        assertEquals(P2pVerdict.Wait(WaitReason.NO_NETWORK), d(NetworkNow.NONE))
        assertEquals(P2pVerdict.Allow, P2pRule.decide(P2pUse.DOWNLOAD, wifi(officeFp), false, known, false, isTv = true))
    }

    @Test fun answersAreRememberedPerNetwork() {
        var s = HomeSettings()
        s = s.answer(cafeFp, "Wi-Fi seen 21 Oct", allowed = true, now = 1)
        assertEquals(P2pVerdict.Allow, P2pRule.decide(P2pUse.DOWNLOAD, wifi(cafeFp), false, s.known, false))
        s = s.answer(cafeFp, "Wi-Fi seen 21 Oct", allowed = false, now = 2)
        assertEquals(1, s.networks.size)
        assertEquals(P2pVerdict.Wait(WaitReason.WORK_NETWORK), P2pRule.decide(P2pUse.DOWNLOAD, wifi(cafeFp), false, s.known, false))
        assertEquals(P2pVerdict.Ask(cafeFp), P2pRule.decide(P2pUse.DOWNLOAD, wifi(cafeFp), false, s.forget(cafeFp).known, false))
        assertEquals(s, HomeSettings.decode(HomeSettings.encode(s)))
        assertEquals("smb://192.168.1.1/usb1/", HomeSettings(share = "/usb1/").shareUrl)
    }
}

class DeleteWhereTest {
    private fun actions(onPhone: Boolean, disk: DiskPresence) = DeleteWhere.choice(onPhone, disk).options.map { it.action to it.enabled }

    @Test fun phoneOnly() = assertEquals(listOf(DeleteAction.PHONE to true), actions(true, DiskPresence.NOT_ON_DISK))
    @Test fun diskOnly() = assertEquals(listOf(DeleteAction.DISK to true), actions(false, DiskPresence.ON_DISK))
    @Test fun both() = assertEquals(listOf(DeleteAction.PHONE to true, DeleteAction.DISK to true, DeleteAction.BOTH to true), actions(true, DiskPresence.ON_DISK))

    @Test fun stillCopyingWarns() {
        val c = DeleteWhere.choice(true, DiskPresence.COPYING)
        assertEquals(listOf(DeleteAction.PHONE, DeleteAction.STOP_COPY), c.options.map { it.action })
        assertEquals("The home copy isn't finished; it will be removed too.", c.warning)
    }

    @Test fun downloadingAtHome() = assertEquals(listOf(DeleteAction.CANCEL_AT_HOME to true), actions(false, DiskPresence.DOWNLOADING_AT_HOME))

    @Test fun noDiskYetDisablesDiskOptions() {
        val c = DeleteWhere.choice(true, DiskPresence.UNKNOWN)
        assertEquals(listOf(DeleteAction.PHONE to true, DeleteAction.DISK to false, DeleteAction.BOTH to false), c.options.map { it.action to it.enabled })
        assertTrue(c.options.drop(1).all { it.note == DeleteWhere.NOT_CONNECTED })
    }
}

class HomeBadgesTest {
    @Test fun everyRowOfTheScreenSpec() {
        assertEquals(Badge("Phone"), HomeBadges.badge(TitleWhere(phoneDone = true), homeSetUp = true))
        assertEquals(Badge("Home disk", BadgeTone.ACCENT), HomeBadges.badge(TitleWhere(disk = DiskPresence.ON_DISK), true))
        assertEquals(Badge("Phone + home disk", BadgeTone.ACCENT), HomeBadges.badge(TitleWhere(phoneDone = true, disk = DiskPresence.ON_DISK), true))
        assertEquals(Badge("Copying to home disk 63 %"), HomeBadges.badge(TitleWhere(phoneDone = true, disk = DiskPresence.COPYING, copyPercent = 63), true))
        assertEquals(Badge("Downloading at home 42 %"), HomeBadges.badge(TitleWhere(atHome = JobState.DOWNLOADING, atHomePercent = 42), true))
        assertEquals(Badge("Queued at home #2"), HomeBadges.badge(TitleWhere(atHome = JobState.QUEUED, queuePos = 2), true))
        assertEquals(Badge("Waiting to reach your TV", BadgeTone.WARN), HomeBadges.badge(TitleWhere(waitingForTv = true), true))
        assertEquals(Badge("Waiting for home Wi-Fi", BadgeTone.WARN), HomeBadges.badge(TitleWhere(held = WaitReason.UNKNOWN_WIFI), true))
        assertEquals(Badge("Waiting for Wi-Fi", BadgeTone.WARN), HomeBadges.badge(TitleWhere(held = WaitReason.MOBILE_DATA), false))
        assertEquals(Badge("At home · waiting for the disk", BadgeTone.WARN), HomeBadges.badge(TitleWhere(atHome = JobState.WAITING_DISK), true))
    }

    @Test fun nothingWithoutHomeStorage() {
        assertNull(HomeBadges.badge(TitleWhere(phoneDone = true), homeSetUp = false))
        assertNull(HomeBadges.badge(TitleWhere(), homeSetUp = true))
    }
}

class DownloadWhereSheetTest {
    @Test fun atHomeNeedsSetupAndATv() {
        val notSetUp = DownloadWhereSheet.rows(false, true, true, true, P2pVerdict.Allow)[0]
        assertFalse(notSetUp.enabled)
        assertEquals("Set up home storage first", notSetUp.status)
        assertFalse(DownloadWhereSheet.rows(true, false, false, true, P2pVerdict.Allow)[0].enabled)
        assertEquals("TV ready", DownloadWhereSheet.rows(true, true, true, true, P2pVerdict.Allow)[0].status)
        DownloadWhereSheet.rows(true, true, false, true, P2pVerdict.Allow)[0].let {
            assertTrue(it.enabled)
            assertEquals("Starts when your TV is reachable", it.status)
        }
    }

    @Test fun onThisPhoneSaysWhenItWaits() {
        val rows = DownloadWhereSheet.rows(true, true, true, true, P2pVerdict.Wait(WaitReason.UNKNOWN_WIFI))
        assertEquals("Copied to the home disk when you're home.", rows[1].body)
        assertEquals("Waits for home Wi-Fi (this is not an allowed network)", rows[1].status)
        assertEquals("Waits for Wi-Fi (you're on mobile data)", DownloadWhereSheet.rows(false, false, false, true, P2pVerdict.Wait(WaitReason.MOBILE_DATA, true))[1].status)
        assertEquals("Saved on this phone.", DownloadWhereSheet.rows(false, false, false, true, P2pVerdict.Allow)[1].body)
        assertEquals("At home · change", DownloadWhereSheet.rememberedLine(DownloadWhere.AT_HOME))
        assertNull(DownloadWhereSheet.rememberedLine(DownloadWhere.ASK))
    }
}

class RetentionPolicyTest {
    private val day = RetentionPolicy.DAY_MS
    private val tb = 1_000_000_000_000L

    @Test fun expiredFirstNeverLibraryOrPinned() {
        val now = 100 * day
        val titles = listOf(
            StoredTitle("old", TitleKind.CACHE, 10_000_000_000, lastUsedAt = now - 8 * day),
            StoredTitle("new", TitleKind.CACHE, 10_000_000_000, lastUsedAt = now - 1 * day),
            StoredTitle("lib", TitleKind.LIBRARY, 10_000_000_000, lastUsedAt = 0),
            StoredTitle("playing", TitleKind.CACHE, 10_000_000_000, lastUsedAt = 0, pinned = true),
        )
        val out = RetentionPolicy.evict(titles, now, tb, freeBytes = 500_000_000_000, settings = RetentionSettings())
        assertEquals(listOf("old" to EvictReason.AGE), out.map { it.key to it.reason })
    }

    @Test fun budgetAndFloor() {
        val now = 10 * day
        val titles = (1..5).map { StoredTitle("c$it", TitleKind.CACHE, 100_000_000_000, lastUsedAt = now - it * 1000) }
        // 500 GB cache, budget 40 % of 1 TB = 400 GB: the oldest one goes.
        assertEquals(listOf("c5" to EvictReason.BUDGET), RetentionPolicy.evict(titles, now, tb, 300_000_000_000, RetentionSettings()).map { it.key to it.reason })
        // Below the 50 GB floor (5 % of 1 TB): evict until above.
        val floor = RetentionPolicy.evict(titles, now, tb, 10_000_000_000, RetentionSettings(cacheSharePct = 60))
        assertEquals(listOf("c5" to EvictReason.FLOOR), floor.map { it.key to it.reason })
        assertEquals(20_000_000_000, RetentionPolicy.floor(100_000_000_000))
    }

    @Test fun untilSpaceIsNeededNeverExpiresByAge() {
        val t = listOf(StoredTitle("a", TitleKind.CACHE, 1, lastUsedAt = 0))
        assertTrue(RetentionPolicy.evict(t, 1000 * day, tb, 900_000_000_000, RetentionSettings(days = 0)).isEmpty())
        assertEquals("Until space is needed", RetentionSettings.daysLabel(0))
    }
}

class StreamTokenTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun makeAndVerify() {
        val t = StreamToken.make("phone1", KEY, 0, expirySec = 1000, streamKey = key)
        assertEquals(StreamToken.Parsed("phone1", KEY, 0, 1000), StreamToken.verify(t, key, 999) { it == "phone1" })
        assertNull(StreamToken.verify(t, key, 1000) { true }, "expired")
        assertNull(StreamToken.verify(t, key, 1) { false }, "device forgotten")
        assertNull(StreamToken.verify(t.replace(".0.", ".1."), key, 1) { true }, "tampered")
        assertNull(StreamToken.verify(t, ByteArray(32), 1) { true }, "rotated key")
        assertTrue(StreamToken.needsRefresh(StreamToken.Parsed("p", KEY, 0, 1000), 1000 - 60))
    }
}

class OutboxTest {
    private val req = RpcRequest("id-1", HomeOps.DOWNLOAD)

    @Test fun backoffAndGiveUp() {
        var items = Outbox.add(emptyList(), req, now = 0)
        items = Outbox.add(items, req, now = 5) // same id: once
        assertEquals(1, items.size)
        assertEquals(1, Outbox.due(items, 0).size)
        items = Outbox.failed(items, "id-1", now = 1_000, error = "unreachable")
        assertTrue(Outbox.due(items, 1_000 + 29_999).isEmpty())
        assertEquals(1, Outbox.due(items, 1_000 + 30_000).size)
        assertEquals(1, Outbox.due(items, 1_001, force = true).size)
        assertEquals(30 * 60_000L, Outbox.retryAfterMs(30))
        assertTrue(Outbox.due(items, Outbox.GIVE_UP_MS, force = true).isEmpty())
        assertTrue(Outbox.acknowledged(items, "id-1").isEmpty())
        assertEquals(items, Outbox.decode(Outbox.encode(items)))
    }
}

class DiskTestLineTest {
    private val base = DiskTestFacts("192.168.1.1", "usb1", reachable = true, signIn = DiskTestFacts.SignIn.OK, protocol = "SMB 3.1.1")

    @Test fun notConnectedToday() {
        val f = DiskTestFacts("192.168.1.1", "usb1", reachable = false)
        assertEquals("Not connected — nothing at 192.168.1.1 yet. You can set everything up now.", DiskTest.line(f))
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.NO_ANSWER), DiskTest.state(f))
        assertFalse(DiskTest.succeeded(f))
    }

    @Test fun connectedLine() {
        val f = base.copy(freeBytes = 412_000_000_000, totalBytes = 931_000_000_000, writeMBps = 11.4, readMBps = 24.0, hashOk = true, bigFiles = true)
        assertEquals("Connected · SMB 3.1.1 · write 11 MB/s · read 24 MB/s · 412 GB free · big files OK", DiskTest.line(f))
        assertTrue(DiskTest.succeeded(f))
        assertEquals("Connected · 412 GB free of 931 GB", DiskTest.status(DiskTest.state(f), "192.168.1.1"))
    }

    @Test fun fat32AndSmb1() {
        val f = base.copy(protocol = "SMB1", smb1 = true, writeMBps = 4.25, readMBps = 6.0, freeBytes = 2_000_000_000, hashOk = true, bigFiles = false)
        assertEquals(
            "Connected · SMB1 · write 4.3 MB/s · read 6.0 MB/s · 2 GB free · big files: no (FAT32 disk, 4 GB limit: format it exFAT or NTFS) (old SMB1: slower, fine at home)",
            DiskTest.line(f),
        )
    }

    @Test fun signInProblems() {
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.PASSWORD_REJECTED), DiskTest.state(base.copy(signIn = DiskTestFacts.SignIn.PASSWORD_REJECTED)))
        assertTrue(DiskTest.line(base.copy(signIn = DiskTestFacts.SignIn.NO_SHARE)).contains("“usb1”"))
    }

    @Test fun passwordRejectedStopsRetries() {
        val p = ConnectPolicy()
        assertTrue(p.mayTry(0, null))
        p.recordAttempt(0)
        p.recordResult(HomeDiskState.NotConnected(NotConnectedReason.NO_ANSWER))
        assertFalse(p.mayTry(4_999, 0))
        assertTrue(p.mayTry(5_000, 0))
        p.recordAttempt(5_000)
        p.recordResult(HomeDiskState.NotConnected(NotConnectedReason.PASSWORD_REJECTED))
        assertFalse(p.mayTry(10_000_000, 5_000))
        p.reset()
        assertTrue(p.mayTry(10_000_000, 5_000))
    }

    @Test fun atMostThreeSignInsAnHour() {
        val p = ConnectPolicy()
        repeat(3) { p.recordAttempt(it * 1000L) }
        assertFalse(p.mayTry(10_000, null))
        assertTrue(p.mayTry(ConnectPolicy.HOUR + 1, null))
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 300_000L, 300_000L), (1..6).map(ConnectPolicy::backoffMs))
    }
}

class RedactorTest {
    @Test fun passwordLinesDropNamesMasked() {
        val r = Redactor(passwords = listOf("hunter22", "abc"), names = listOf("kirtanya", "usb1"))
        val lines = listOf("connect usb1 as Kirtanya ok", "password was hunter22", "plain line", "abc stays")
        assertEquals(listOf("connect ‹hidden› as ‹hidden› ok", "plain line", "abc stays"), r.lines(lines))
        assertEquals("smb://192.168.1.1/‹hidden›/ (‹hidden›)", r.scrub("smb://192.168.1.1/usb1/ (hunter22)"))
        assertTrue(Redactor.NONE.isEmpty)
    }
}

class HomeSettingsTest {
    @Test fun defaults() {
        val s = HomeSettings()
        assertEquals(DownloadWhere.ASK, s.downloadWhere)
        assertTrue(s.copyPhoneDownloads)
        assertFalse(s.mobileData)
        assertEquals(RetentionSettings(keepTvViewing = true, days = 7, cacheSharePct = 40, autoCacheDetails = false), s.retention)
        assertFalse(s.filledIn)
        assertTrue(s.copy(share = "usb1", user = "me").filledIn)
        assertEquals(HomeSettings(), HomeSettings.decode("not json"))
    }

    @Test fun v2TvIsDetected() {
        val v2 = com.kirtanya.reel.core.sync.HelloResponse("TV", "tv", "n", protocol = 2)
        assertFalse(HomeRpc.supported(v2))
        assertTrue(HomeRpc.supported(v2.copy(protocol = 3)))
        assertNotNull(HomeRpc.UPDATE_TV)
    }
}
