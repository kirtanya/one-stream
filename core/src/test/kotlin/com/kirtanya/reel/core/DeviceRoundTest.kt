package com.kirtanya.reel.core

import com.kirtanya.reel.core.round.DeviceRoundCli
import com.kirtanya.reel.core.round.IdleGate
import com.kirtanya.reel.core.round.RoundCompare
import com.kirtanya.reel.core.round.RoundLog
import com.kirtanya.reel.core.round.RoundMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundLogTest {
    private val key = "209c8226b299"
    private val sample = "209c8226b299b308beaf2b9cd3fb49212dbd13ec"

    // Real logcat -v brief shape: "I/ReelStats( 4321): ev=… "
    private fun l(s: String) = "I/ReelStats( 4321): $s"

    private val stats = listOf(
        l("ev=start key=$key dev=phone disk=mmap warm=cold build=p2 flags=inapp1"),
        l("ev=meta t=812 key=$key peers=4"),
        l("ev=prepared t=1500 key=$key size=123 pl=262144 pieces=2000 have=0"),
        l("ev=wait t=2100 key=$key p=3 ms=420 fileReady=1"),
        l("ev=ttff t=4321 key=$key"),
        l("ev=perf code=OUTSTANDING_REQUEST_LIMIT_REACHED"),
        l("ev=perf code=TOO_FEW_OUTSTANDING_REQUESTS"),
        l("ev=perf code=OUTSTANDING_DISK_BUFFER_LIMIT_REACHED"),
        l("ev=seek t=20000 key=$key ms=900"),
        l("ev=wait t=20100 key=$key p=90 ms=2500 fileReady=1"),
        l("ev=seek t=30000 key=$key ms=3100"),
        l("ev=seek t=40000 key=$key ms=1200"),
        l("ev=end key=$key ttm=812 ttfp=1900 ttff=4321 rebuf=2/3400/3000 seeks=3 p50=1200 p90=3100 waits=2/2500 fatalSlow=0"),
    )

    @Test fun parsesARound() {
        val m = RoundLog.parse(stats, emptyList(), sample)
        assertEquals(4321, m.ttffMs)
        assertEquals("cold", m.warm)
        assertEquals(0, m.haveAtStart)
        assertEquals(3, m.seeks)
        assertEquals(1200, m.seekP50Ms)
        assertEquals(3100, m.seekP90Ms)
        assertEquals(2, m.rebuffers)
        assertEquals(3400, m.rebufferMs)
        assertEquals(2, m.waits)
        assertEquals(2500, m.waitMaxMs)
        // OUTSTANDING_* only, not TOO_FEW_OUTSTANDING_REQUESTS.
        assertEquals(2, m.outstanding)
        assertEquals(0, m.crashes)
    }

    @Test fun noFirstFrameAndNoEndLine() {
        val m = RoundLog.parse(stats.take(3), emptyList(), sample)
        assertNull(m.ttffMs)
        assertNull(m.rebuffers)
        assertEquals(0, m.seeks)
        assertEquals(0, m.seekP50Ms)
    }

    @Test fun ignoresOtherStreams() {
        val other = listOf(l("ev=ttff t=999 key=deadbeef0000"), l("ev=seek t=5 key=deadbeef0000 ms=50000"))
        val m = RoundLog.parse(other + stats, emptyList(), sample)
        assertEquals(4321, m.ttffMs)
        assertEquals(3, m.seeks)
    }

    @Test fun threadtimeFormatToo() {
        val m = RoundLog.parse(listOf("10-20 23:01:02.345  4321  4400 I ReelStats: ev=ttff t=7000 key=$key"), emptyList(), sample)
        assertEquals(7000, m.ttffMs)
    }

    @Test fun countsOnlyOurCrashesAndAnrs() {
        val log = listOf(
            "E AndroidRuntime: FATAL EXCEPTION: main",
            "E AndroidRuntime: Process: com.kirtanya.reel, PID: 1234",
            "E AndroidRuntime: Process: com.kirtanya.reelother, PID: 99",
            "E AndroidRuntime: Process: com.oakever.meowdoku, PID: 5",
            "F DEBUG   : pid: 77, tid: 80, name: reel-engine  >>> com.kirtanya.reel <<<",
            "E ActivityManager: ANR in com.kirtanya.reel (com.kirtanya.reel/.player.PlayerActivity)",
            "I am_anr  : [0,1234,com.kirtanya.reel,952745540,Input dispatching timed out]",
            "E ActivityManager: ANR in com.google.android.youtube",
        )
        assertEquals(2, RoundLog.crashes(log))
        assertEquals(1, RoundLog.anrs(log))
    }
}

class IdleGateTest {
    private val launcher = "ActivityRecord{7165590 u0 com.kirtanya.one_launcher/.MainActivity t19537}"
    private val idleMedia = """
        package=com.netflix.mediaclient
          active=false
          state=null
    """.trimIndent()

    private fun dumps(
        media: String = idleMedia,
        top: String = "  topResumedActivity=$launcher",
        window: String = "isKeyguardShowing=false\nmShowingDream=false",
        power: String = "mWakefulness=Awake",
        home: String? = "com.kirtanya.one_launcher",
    ) = IdleGate.Dumps(media, top, window, power, home)

    @Test fun launcherOnTopIsIdle() = assertEquals(IdleGate.Result.Idle, IdleGate.check(dumps()))

    @Test fun ourHomeScreenIsIdle() =
        assertEquals(IdleGate.Result.Idle, IdleGate.check(dumps(top = "topResumedActivity=ActivityRecord{1 u0 com.kirtanya.reel/.MainActivity t5}")))

    @Test fun ourPlayerIsBusyEvenPaused() {
        val r = IdleGate.check(dumps(top = "topResumedActivity=ActivityRecord{1 u0 com.kirtanya.reel/.player.PlayerActivity t5}"))
        assertIs<IdleGate.Result.Busy>(r)
    }

    @Test fun playingInBothFormatsIsBusy() {
        for (s in listOf("state=PlaybackState {state=PLAYING(3), position=1000", "state=PlaybackState {state=3, position=1000", "state=PlaybackState {state=BUFFERING(6), position=0")) {
            val r = IdleGate.check(dumps(media = "$idleMedia\n  $s"))
            assertEquals(IdleGate.Result.Busy("something is playing"), r, s)
        }
    }

    @Test fun pausedElsewhereStillNeedsTheLauncher() {
        // Paused (2) is not "playing", but the paused app is on top, so the round still skips.
        val r = IdleGate.check(dumps(media = "state=PlaybackState {state=PAUSED(2), position=1", top = "topResumedActivity=ActivityRecord{1 u0 com.netflix.mediaclient/.ui.Player t3}"))
        assertEquals(IdleGate.Result.Busy("another app is open (com.netflix.mediaclient)"), r)
    }

    @Test fun gameOnTopIsBusy() {
        val r = IdleGate.check(dumps(top = "topResumedActivity=ActivityRecord{7165590 u0 com.oakever.meowdoku/com.godot.game.GodotAppLauncher t19537}"))
        assertEquals(IdleGate.Result.Busy("another app is open (com.oakever.meowdoku)"), r)
    }

    @Test fun lockedAsleepOrDreamingIsBusy() {
        assertIs<IdleGate.Result.Busy>(IdleGate.check(dumps(window = "isKeyguardShowing=true")))
        assertIs<IdleGate.Result.Busy>(IdleGate.check(dumps(power = "mWakefulness=Asleep")))
        assertIs<IdleGate.Result.Busy>(IdleGate.check(dumps(power = "mWakefulness=Dozing")))
        assertIs<IdleGate.Result.Busy>(IdleGate.check(dumps(window = "mShowingDream=true")))
    }

    @Test fun nothingOnTopIsBusy() = assertIs<IdleGate.Result.Busy>(IdleGate.check(dumps(top = "")))

    @Test fun readsTheDumpsFile() {
        val s = DeviceRoundCli.sections("### media_session\nstate=null\n### top\n$launcher\n### home\npriority=0\ncom.kirtanya.one_launcher/.MainActivity\n")
        assertEquals("state=null\n", s["media_session"])
        assertTrue(s.getValue("home").contains("one_launcher"))
    }
}

class RoundCompareTest {
    private val base = RoundMetrics(ttffMs = 8_000, warm = "cold", seeks = 2, seekP50Ms = 1_000, seekP90Ms = 2_000, rebuffers = 0, rebufferMs = 0, waits = 3, waitMaxMs = 800, outstanding = 1)

    private fun verdictOf(metric: String, before: RoundMetrics?, now: RoundMetrics) = RoundCompare.rows(before, now).single { it.metric == metric }.verdict

    @Test fun swarmNoiseIsSame() {
        // 1.4× slower, or 1.6× but only +4.8 s: both inside the noise band.
        assertEquals(RoundCompare.SAME, verdictOf("ttff", base, base.copy(ttffMs = 11_200)))
        assertEquals(RoundCompare.SAME, verdictOf("ttff", base.copy(ttffMs = 8_000), base.copy(ttffMs = 12_800)))
        assertEquals(RoundCompare.SAME, verdictOf("rebuffers", base, base.copy(rebuffers = 1)))
        assertEquals(RoundCompare.SAME, verdictOf("outstanding warnings", base, base.copy(outstanding = 4)))
    }

    @Test fun bigSwingsCount() {
        assertEquals(RoundCompare.WORSE, verdictOf("ttff", base, base.copy(ttffMs = 13_100)))
        assertEquals(RoundCompare.BETTER, verdictOf("ttff", base.copy(ttffMs = 20_000), base))
        assertEquals(RoundCompare.WORSE, verdictOf("seek p50", base, base.copy(seekP50Ms = 3_100)))
        assertEquals(RoundCompare.WORSE, verdictOf("rebuffers", base, base.copy(rebuffers = 2)))
        assertEquals(RoundCompare.WORSE, verdictOf("longest wait", base, base.copy(waitMaxMs = 4_000)))
    }

    @Test fun cachedVsColdTtffIsNotCompared() {
        assertEquals(RoundCompare.NOT_COMPARABLE, verdictOf("ttff", base, base.copy(ttffMs = 2_000, warm = "cached")))
        assertEquals(RoundCompare.NOT_COMPARABLE, verdictOf("start", base, base.copy(warm = "cached")))
    }

    @Test fun firstRoundIsNew() {
        assertEquals(RoundCompare.NEW, verdictOf("ttff", null, base))
        assertEquals("–", RoundCompare.rows(null, base).first { it.metric == "ttff" }.before)
    }

    @Test fun noSeeksMeansNoSeekTimes() {
        val rows = RoundCompare.rows(base, base.copy(seeks = 0, seekP50Ms = 0, seekP90Ms = 0))
        assertEquals("–", rows.single { it.metric == "seek p50" }.now)
        assertEquals(listOf("start", "ttff", "seeks", "seek p50", "seek p90"), rows.take(5).map { it.metric })
    }

    @Test fun onlyCrashesAnrsAndNoPlaybackFail() {
        assertEquals(emptyList(), RoundCompare.failures(base.copy(ttffMs = 90_000, rebuffers = 9, outstanding = 50)))
        assertEquals(1, RoundCompare.failures(base.copy(ttffMs = null)).size)
        assertEquals(2, RoundCompare.failures(base.copy(crashes = 1, anrs = 1)).size)
        assertEquals(RoundCompare.FAIL, verdictOf("crashes", base, base.copy(crashes = 1)))
        assertEquals(RoundCompare.FAIL, verdictOf("ttff", base, base.copy(ttffMs = null)))
    }

    @Test fun formatsTimes() {
        assertEquals("850 ms", RoundCompare.fmt(850, ms = true))
        assertEquals("12.3 s", RoundCompare.fmt(12_340, ms = true))
        assertEquals("–", RoundCompare.fmt(null, ms = true))
    }
}
