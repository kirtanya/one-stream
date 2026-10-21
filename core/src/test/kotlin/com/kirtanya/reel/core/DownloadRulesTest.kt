package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.CacheEntry
import com.kirtanya.reel.core.stream.CachePolicy
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.GiB
import com.kirtanya.reel.core.stream.KiB
import com.kirtanya.reel.core.stream.MiB
import com.kirtanya.reel.core.stream.NightRule
import com.kirtanya.reel.core.stream.NightWindow
import com.kirtanya.reel.core.stream.RestoreFlags
import com.kirtanya.reel.core.stream.RestoreMeta
import com.kirtanya.reel.core.stream.RestorePolicy
import com.kirtanya.reel.core.stream.WatchCap
import com.kirtanya.reel.core.stream.WatchGovernor
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/STREAMING_V2.md §8.3: overnight window, speed cap while watching, stream-cache limit. */
class DownloadRulesTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min
    private val night = NightWindow(enabled = true, startHour = 1, endHour = 7)

    // ── A. Overnight window ──────────────────────────────────────────────────────────────

    @Test fun windowEdgesAreStartInclusiveEndExclusive() {
        assertFalse(NightRule.inWindow(night, m(0, 59)))
        assertTrue(NightRule.inWindow(night, m(1)))
        assertTrue(NightRule.inWindow(night, m(6, 59)))
        assertFalse(NightRule.inWindow(night, m(7)))
        assertFalse(NightRule.inWindow(night, m(14)))
    }

    @Test fun windowCrossingMidnight() {
        val w = NightWindow(true, 22, 6)
        assertTrue(NightRule.inWindow(w, m(23)))
        assertTrue(NightRule.inWindow(w, m(0)))
        assertTrue(NightRule.inWindow(w, m(5, 59)))
        assertFalse(NightRule.inWindow(w, m(6)))
        assertFalse(NightRule.inWindow(w, m(21, 59)))
    }

    @Test fun offOrEqualHoursMeansAlways() {
        assertTrue(NightRule.inWindow(night.copy(enabled = false), m(14)))
        assertTrue(NightRule.inWindow(NightWindow(true, 3, 3), m(14)))
    }

    @Test fun onlyUnfinishedUnclaimedDownloadsWithoutStartNowAreHeld() {
        val day = m(14)
        assertTrue(NightRule.holds(night, day, download = true, startNow = false, finished = false, claimed = false))
        assertFalse(NightRule.holds(night, day, download = false, startNow = false, finished = false, claimed = false), "streams are never held")
        assertFalse(NightRule.holds(night, day, download = true, startNow = true, finished = false, claimed = false), "Start now overrides")
        assertFalse(NightRule.holds(night, day, download = true, startNow = false, finished = true, claimed = false), "seeding isn't downloading")
        assertFalse(NightRule.holds(night, day, download = true, startNow = false, finished = false, claimed = true), "a download being played")
        assertFalse(NightRule.holds(night, m(3), download = true, startNow = false, finished = false, claimed = false), "inside the window")
    }

    @Test fun nextEdgeIsTheNearestFlipAfterNow() {
        val z = ZoneId.of("Asia/Kolkata")
        fun at(h: Int, min: Int = 0) = ZonedDateTime.of(2026, 10, 22, h, min, 0, 0, z)
        assertEquals(ZonedDateTime.of(2026, 10, 23, 1, 0, 0, 0, z), NightRule.nextEdge(night, at(14)))
        assertEquals(at(7), NightRule.nextEdge(night, at(3)))
        assertEquals(at(1), NightRule.nextEdge(night, at(0, 30)))
        // Exactly on an edge: the next one, never "now" again.
        assertEquals(at(7), NightRule.nextEdge(night, at(1)))
        assertNull(NightRule.nextEdge(night.copy(enabled = false), at(14)))
        assertNull(NightRule.nextEdge(NightWindow(true, 4, 4), at(14)))
    }

    @Test fun waitingLabelNamesTheStartHour() {
        assertEquals("Waiting for night (01:00)", NightRule.waitingLabel(night))
        assertEquals("Waiting for night (23:00)", NightRule.waitingLabel(NightWindow(true, 23, 6)))
    }

    @Test fun restoreKeepsAHeldDownloadPausedAndGivesAReleasedOneBack() {
        val running = RestoreFlags(autoManaged = true, paused = false, sequential = false)
        assertEquals(RestoreFlags(false, true, false), RestorePolicy.restore(running, RestoreMeta(download = true, nightHold = true)))
        // Held overnight before the restart, the window is open now: back to the queue.
        val heldBefore = RestoreFlags(autoManaged = false, paused = true, sequential = false)
        assertEquals(RestoreFlags(true, false, false), RestorePolicy.restore(heldBefore, RestoreMeta(download = true, pausedForNight = true)))
        // The user's own pause still wins over everything.
        assertEquals(RestoreFlags(false, true, false), RestorePolicy.restore(heldBefore, RestoreMeta(download = true, userPaused = true, pausedForNight = true)))
        // Streams aren't touched by the window.
        assertEquals(RestoreFlags(false, true, false), RestorePolicy.restore(running, RestoreMeta(download = false, nightHold = true)))
    }

    // ── B. Speed cap while watching ──────────────────────────────────────────────────────

    @Test fun fixedCapIsSplitBetweenFetchingDownloads() {
        val g = WatchGovernor()
        val d = g.tick(0, watching = true, sessionDownBps = 5 * MiB, choice = WatchCap.M1, fetching = 2, targets = 3)
        assertTrue(d.capping)
        assertEquals(1 * MiB, d.totalDown)
        assertEquals(512 * KiB, d.perTorrentDown)
        assertEquals(WatchGovernor.UPLOAD_CAP, d.perTorrentUp)
    }

    @Test fun autoIsAQuarterOfThePeakLinkWithAFloor() {
        val g = WatchGovernor()
        g.tick(0, watching = false, sessionDownBps = 8 * MiB, choice = WatchCap.AUTO, fetching = 1, targets = 1)
        val d = g.tick(2_000, watching = true, sessionDownBps = 3 * MiB, choice = WatchCap.AUTO, fetching = 1, targets = 1)
        assertEquals(2 * MiB, d.totalDown, "25 % of the 8 MiB/s peak, not of the current 3")
        // The peak ages out after the window.
        val later = g.tick(70_000, watching = true, sessionDownBps = 100 * KiB, choice = WatchCap.AUTO, fetching = 1, targets = 1)
        assertEquals(WatchGovernor.MIN_AUTO_TOTAL, later.totalDown)
    }

    @Test fun perTorrentCapNeverDropsBelowTheFloorAndIsNeverZero() {
        val g = WatchGovernor()
        val d = g.tick(0, watching = true, sessionDownBps = 0, choice = WatchCap.K500, fetching = 100, targets = 100)
        assertEquals(WatchGovernor.MIN_PER_TORRENT, d.perTorrentDown)
        assertTrue(d.perTorrentUp >= 1 * KiB)
        // Nothing fetching (only seeds): the whole cap, still not 0 (0 = unlimited in libtorrent).
        assertEquals(500 * KiB, g.tick(1, true, 0, WatchCap.K500, fetching = 0, targets = 1).perTorrentDown)
    }

    @Test fun capLiftsAFewSecondsAfterWatchingStops() {
        val g = WatchGovernor()
        assertTrue(g.tick(0, true, MiB, WatchCap.M2, 1, 1).capping)
        assertTrue(g.tick(2_000, false, MiB, WatchCap.M2, 1, 1).capping, "a pause of 2 s keeps the cap")
        assertTrue(g.tick(4_000, false, MiB, WatchCap.M2, 1, 1).capping)
        assertFalse(g.tick(6_000, false, MiB, WatchCap.M2, 1, 1).capping, "lifted 5 s after the last watching tick")
        assertTrue(g.tick(8_000, true, MiB, WatchCap.M2, 1, 1).capping, "back on at once when playback resumes")
    }

    @Test fun offOrNothingToCapMeansNoCap() {
        val g = WatchGovernor()
        assertFalse(g.tick(0, true, MiB, WatchCap.OFF, 1, 1).capping)
        assertFalse(g.tick(1, true, MiB, WatchCap.M1, 0, 0).capping)
        assertFalse(WatchGovernor().tick(0, false, MiB, WatchCap.M1, 1, 1).capping, "never watched")
    }

    @Test fun limitsAreRewrittenOnlyOnBigChanges() {
        assertFalse(WatchGovernor.changedEnough(1000, 1200))
        assertTrue(WatchGovernor.changedEnough(1000, 1300))
        assertTrue(WatchGovernor.changedEnough(1000, 700))
        assertTrue(WatchGovernor.changedEnough(0, 500), "first write")
    }

    // ── C. Stream-cache limit ────────────────────────────────────────────────────────────

    @Test fun nullLimitKeepsTheAutomaticBudget() {
        assertEquals(5 * GiB, CachePolicy.budget(FormFactor.PHONE, 30 * GiB, 0, null))
        assertEquals(4 * GiB, CachePolicy.budget(FormFactor.TV, 20 * GiB, 0, null))
        assertEquals(CachePolicy.budget(FormFactor.PHONE, 100 * GiB, 3 * GiB), CachePolicy.budget(FormFactor.PHONE, 100 * GiB, 3 * GiB, null))
    }

    @Test fun limitReplacesTheBudgetButKeepsTheReserve() {
        assertEquals(2 * GiB, CachePolicy.budget(FormFactor.PHONE, 100 * GiB, 0, 2 * GiB))
        assertEquals(20 * GiB, CachePolicy.budget(FormFactor.PHONE, 100 * GiB, 0, 20 * GiB))
        // TV with ~1.7 GB free asking for 10 GB: only what leaves 1.5 GiB free.
        assertEquals(3 * GiB - (3 * GiB) / 2, CachePolicy.budget(FormFactor.TV, 2 * GiB, 1 * GiB, 10 * GiB))
        assertEquals(0, CachePolicy.budget(FormFactor.TV, 1 * GiB, 0, 5 * GiB))
    }

    @Test fun limitEvictsOldestStoppedFirstButNeverThePlayingOneOrTheProtected() {
        val entries = listOf(
            CacheEntry("playing", 3 * GiB, lastUsedAt = 100, lastStoppedAt = 0, claimed = true),
            CacheEntry("oldest", 1 * GiB, lastUsedAt = 1, lastStoppedAt = 2, claimed = false),
            CacheEntry("old", 1 * GiB, lastUsedAt = 3, lastStoppedAt = 4, claimed = false),
            CacheEntry("recent1", 1 * GiB, lastUsedAt = 5, lastStoppedAt = 50, claimed = false),
            CacheEntry("recent2", 1 * GiB, lastUsedAt = 6, lastStoppedAt = 60, claimed = false),
        )
        val budget = CachePolicy.budget(FormFactor.PHONE, 100 * GiB, entries.sumOf { it.bytes }, 2 * GiB)
        val out = CachePolicy.evict(entries, budget, free = 100 * GiB, hardFloor = CachePolicy.hardFloor(FormFactor.PHONE), explicit = false)
        assertEquals(listOf("oldest", "old"), out, "the two most recently stopped stay protected; the playing one is never evicted")
    }
}
