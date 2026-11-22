package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.ClipKind
import com.kirtanya.reel.core.player.DebridWait
import com.kirtanya.reel.core.player.PlaceholderCheck
import com.kirtanya.reel.core.player.Readiness
import com.kirtanya.reel.core.player.ReadyCheck
import com.kirtanya.reel.core.player.StatusClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Addon status clips by file name, and how long the player waits for a debrid service to finish. */
class StatusClipTest {
    private val host = "https://addon.example"

    @Test fun downloadingClipIsWorthWaiting(): Unit {
        val url = "$host/videos/downloading_v2.mp4"
        assertEquals("downloading", StatusClip.name(url))
        assertEquals(ClipKind.DOWNLOADING, StatusClip.kind(url))
        assertEquals(Readiness.NOT_READY, ReadyCheck.classify(url, 206))
    }

    @Test fun accountClipIsRefused(): Unit {
        val url = "$host/videos/failed_access_v2.mp4"
        assertEquals("failed_access", StatusClip.name(url))
        assertEquals(ClipKind.ACCOUNT, StatusClip.kind(url))
        assertEquals(Readiness.FAILED, ReadyCheck.classify(url, 200))
        assertEquals("failed_access", PlaceholderCheck.matchUrl(url))
    }

    @Test fun fileFailuresAreFailed(): Unit {
        listOf("failed_infringement_v2", "failed_rar_v2", "failed_too_big", "failed_unexpected_v2").forEach {
            val url = "$host/videos/$it.mp4"
            assertEquals(ClipKind.FAILED, StatusClip.kind(url), it)
            assertEquals(Readiness.FAILED, ReadyCheck.classify(url, 200), it)
        }
    }

    @Test fun realFilesAreNeverNamed(): Unit {
        assertNull(StatusClip.name("$host/videos/movie.mp4"))
        assertNull(StatusClip.name("https://debrid.example/dl/abc/The.Film.2019.1080p.mkv"))
        assertEquals(Readiness.READY, ReadyCheck.classify("https://debrid.example/dl/abc/The.Film.2019.1080p.mkv", 206))
    }

    @Test fun keyInLinkNeverReachesTheName(): Unit {
        val name = StatusClip.name("$host/SECRETKEY123/videos/failed_access_v2.mp4?token=abc")
        assertEquals("failed_access", name)
        assertFalse(name!!.contains("secret", ignoreCase = true))
    }

    @Test fun waitBacksOffThenStops(): Unit {
        assertEquals(listOf(10_000L, 15_000L, 20_000L, 30_000L, 30_000L), (0..4).map(DebridWait::delayMs))
        assertTrue(DebridWait.keepWaiting(9 * 60_000L))
        assertFalse(DebridWait.keepWaiting(DebridWait.MAX_WAIT_MS))
    }

    @Test fun serviceFromProvider(): Unit {
        assertEquals("AllDebrid", DebridWait.serviceName("Torrentio AD"))
        assertEquals("Real-Debrid", DebridWait.serviceName("Debridio | RD"))
        assertEquals("TorBox", DebridWait.serviceName("Comet (TB)"))
        assertNull(DebridWait.serviceName("Internet Archive"))
        // "ADD" or "Download" are not AllDebrid.
        assertNull(DebridWait.serviceName("Download ADD"))
    }

    @Test fun copy(): Unit {
        assertEquals("1:05", DebridWait.clock(65_000))
        assertTrue(DebridWait.waitingText("AllDebrid", 65_000).endsWith("Waiting 1:05"))
        assertTrue(DebridWait.accountText("AllDebrid").contains("subscription"))
        assertEquals("Check your AllDebrid account", DebridWait.accountTitle("AllDebrid"))
        listOf(
            DebridWait.waitingText("A", 0), DebridWait.accountText("A"), DebridWait.failedText("A"), DebridWait.gaveUpText("A"),
            DebridWait.accountTitle("A"), DebridWait.FAILED_TITLE,
        )
            .forEach { assertFalse(it.contains('—') || it.contains('!'), it) }
    }
}
