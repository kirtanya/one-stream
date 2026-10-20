package com.kirtanya.reel.core

import com.kirtanya.reel.core.report.LogWindow
import com.kirtanya.reel.core.report.ReportEntry
import com.kirtanya.reel.core.report.ReportPolicy
import com.kirtanya.reel.core.report.ShakeGate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogWindowTest {
    private val now = LocalDateTime.of(2026, 10, 20, 14, 5, 0)
    private fun l(stamp: String, tag: String = "ReelPlayer", msg: String = "hello") = "$stamp  4321  4400 I $tag: $msg"

    @Test fun parsesThreadtime() {
        val e = LogWindow.parse(l("10-20 14:03:12.345", "ReelStats", "ev=start key=abc"), now)!!
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 3, 12, 345_000_000), e.time)
        assertEquals("ReelStats", e.tag)
        assertEquals("ev=start key=abc", e.message)
    }

    @Test fun parsesYearStampAndPaddedTag() {
        val e = LogWindow.parse("2025-12-31 23:59:59.000  1  2 W chromium  : [WARNING] x: y", now)!!
        assertEquals(2025, e.time.year)
        assertEquals("chromium", e.tag)
        assertEquals("[WARNING] x: y", e.message)
    }

    @Test fun skipsNonLogLines() {
        assertNull(LogWindow.parse("--------- beginning of main", now))
        assertNull(LogWindow.parse("", now))
    }

    @Test fun keepsOnlyTheWindow() {
        val lines = listOf(
            l("10-20 14:02:59.999"), // 120.001 s before: out
            l("10-20 14:03:00.000"), // exactly 120 s: in
            "--------- beginning of crash",
            l("10-20 14:04:59.000"),
            l("10-20 14:06:30.000"), // 90 s ahead: clock jitter beyond a minute, out
        )
        assertEquals(listOf(lines[1], lines[3]), LogWindow.filter(lines, now, 120))
    }

    @Test fun newYearRollover() {
        val newYear = LocalDateTime.of(2027, 1, 1, 0, 0, 30)
        val dec = l("12-31 23:59:00.000")
        assertEquals(2026, LogWindow.parse(dec, newYear)!!.time.year)
        assertEquals(listOf(dec), LogWindow.filter(listOf(dec), newYear, 120))
    }

    @Test fun midnightRollover() {
        val justAfter = LocalDateTime.of(2026, 10, 21, 0, 0, 30)
        val lines = listOf(l("10-20 23:59:00.000"), l("10-21 00:00:10.000"), l("10-20 23:50:00.000"))
        assertEquals(lines.take(2), LogWindow.filter(lines, justAfter, 120))
    }

    @Test fun leapDay() {
        val march = LocalDateTime.of(2028, 3, 1, 0, 0, 30)
        assertEquals(2028, LogWindow.parse(l("02-29 23:59:50.000"), march)!!.time.year)
    }

    @Test fun dropsSecrets() {
        val token = "eyJhbGciOiJIUzI1NiJ9.token"
        val lines = listOf(
            l("10-20 14:04:00.000", msg = "GET /3/movie?x=1 $token"),
            l("10-20 14:04:01.000", msg = "Authorization: Bearer abc"),
            l("10-20 14:04:02.000", msg = "client_secret=123"),
            l("10-20 14:04:03.000", msg = "ok"),
        )
        assertEquals(listOf(lines[3]), LogWindow.filter(lines, now, 120, listOf(token)))
    }

    @Test fun shortSecretsAreIgnored() = assertFalse(LogWindow.isSecret("playing a film", listOf("a", "")))

    @Test fun messagesForTag() {
        val lines = listOf(l("10-20 14:04:00.000", "ReelStats", "ev=stall ms=6000"), l("10-20 14:04:01.000", "ReelPlayer", "x"))
        assertEquals(listOf("ev=stall ms=6000"), LogWindow.messagesFor(lines, "ReelStats", now))
    }
}

class ReportPolicyTest {
    private fun e(i: Int, mb: Long = 1, kind: String = "manual", key: String? = null) =
        ReportEntry("r$i", mb * 1024 * 1024, createdAt = i * 1_000L, kind = kind, key = key)

    @Test fun keepsNewestForty() {
        val all = (1..45).map { e(it) }
        assertEquals((1..5).map { "r$it" }, ReportPolicy.toDelete(all.shuffled()).map { it.name })
    }

    @Test fun keepsWithinEightyMegabytes() {
        val all = (1..10).map { e(it, mb = 10) }
        // Newest 8 = 80 MB fit; the two oldest go, oldest first.
        assertEquals(listOf("r1", "r2"), ReportPolicy.toDelete(all).map { it.name })
    }

    @Test fun anOldSmallReportDoesNotSurvivePastABigOne() {
        val all = listOf(e(1, mb = 1), e(2, mb = 79), e(3, mb = 2))
        assertEquals(listOf("r1", "r2"), ReportPolicy.toDelete(all).map { it.name })
    }

    @Test fun newestAlwaysStays() {
        val all = listOf(e(1), e(2, mb = 200))
        assertEquals(listOf("r1"), ReportPolicy.toDelete(all).map { it.name })
        assertEquals(emptyList(), ReportPolicy.toDelete(emptyList()))
    }

    @Test fun autoRateLimit() {
        val recent = listOf(ReportEntry("a", 10, createdAt = 1_000_000, kind = "stall", key = "tv:1:s1e1"))
        assertFalse(ReportPolicy.shouldAutoSave(recent, "stall", "tv:1:s1e1", now = 1_000_000 + 9 * 60_000))
        assertTrue(ReportPolicy.shouldAutoSave(recent, "stall", "tv:1:s1e1", now = 1_000_000 + 10 * 60_000))
        assertTrue(ReportPolicy.shouldAutoSave(recent, "stall", "movie:2", now = 1_000_000 + 60_000))
        assertTrue(ReportPolicy.shouldAutoSave(recent, "error", "tv:1:s1e1", now = 1_000_000 + 60_000))
    }

    @Test fun crashesAlwaysSaved() {
        val recent = listOf(ReportEntry("a", 10, createdAt = 1_000, kind = "crash", key = null))
        assertTrue(ReportPolicy.shouldAutoSave(recent, "crash", null, now = 2_000))
    }

    @Test fun folderNames() {
        val t = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("20260102-030405-exit-crash-native", ReportPolicy.folderName(t, "exit-CRASH_NATIVE"))
        assertEquals("20260102-030405-report", ReportPolicy.folderName(t, "  "))
        assertEquals("exit-anr", ReportPolicy.kindOf("20260102-030405-exit-anr"))
        assertEquals("stall", ReportPolicy.kindOf("20260102-030405-stall-2"))
        assertNull(ReportPolicy.kindOf("notes.txt"))
    }
}

class ShakeGateTest {
    @Test fun doubleJoltIsAShake() {
        val g = ShakeGate()
        assertFalse(g.feed(3f, 1_000))
        assertFalse(g.feed(3f, 1_050)) // same jolt
        assertFalse(g.feed(1f, 1_150))
        assertTrue(g.feed(3f, 1_400))
    }

    @Test fun singleBumpIsNot() {
        val g = ShakeGate()
        assertFalse(g.feed(4f, 1_000))
        assertFalse(g.feed(1f, 1_200))
        assertFalse(g.feed(4f, 2_500)) // too late: a new first jolt
        assertFalse(g.feed(1.2f, 2_700))
    }

    @Test fun gentleMovementIsNot() {
        val g = ShakeGate()
        assertFalse((0 until 100).map { g.feed(1.8f, it * 20L) }.any { it })
    }

    @Test fun cooldownAfterAShake() {
        val g = ShakeGate()
        g.feed(3f, 0); assertTrue(g.feed(3f, 300))
        assertFalse(g.feed(3f, 600)); assertFalse(g.feed(3f, 900))
        assertFalse(g.feed(3f, 3_400)); assertTrue(g.feed(3f, 3_700))
    }
}
