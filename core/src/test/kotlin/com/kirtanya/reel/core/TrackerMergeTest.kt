package com.kirtanya.reel.core

import com.kirtanya.reel.core.magnet.DEFAULT_TRACKERS
import com.kirtanya.reel.core.magnet.TrackerMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerMergeTest {
    @Test fun sameHostSpelledTwiceCollapses() {
        val r = TrackerMerge.merge(listOf("udp://Tracker.Example.com:80/announce", "udp://tracker.example.com:80/announce/"), emptyList())
        assertEquals(listOf("udp://Tracker.Example.com:80/announce"), r)
    }

    @Test fun websocketAndGarbageAreDropped() {
        val r = TrackerMerge.merge(listOf("wss://tracker.openwebtorrent.com", "nonsense", "udp://:80/x", "http://a.b:1/announce"), emptyList())
        assertEquals(listOf("http://a.b:1/announce"), r)
        assertNull(TrackerMerge.normalize("ws://x.y"))
    }

    @Test fun ownTrackersComeFirstThenDefaultsWithoutDuplicates() {
        val own = listOf("udp://own.example:1337/announce", "udp://TRACKER.OPENTRACKR.ORG:1337/announce")
        val r = TrackerMerge.merge(own)
        assertEquals("udp://own.example:1337/announce", r.first())
        assertEquals(DEFAULT_TRACKERS.size + 1, r.size)
        assertEquals("udp://TRACKER.OPENTRACKR.ORG:1337/announce", r[1], "the torrent's own spelling wins, no second copy")
    }

    @Test fun hugeListsAreCappedButDefaultsSurvive() {
        val own = (1..100).map { "udp://t$it.example:6969/announce" }
        val r = TrackerMerge.merge(own)
        assertEquals(TrackerMerge.MAX_TRACKERS, r.size)
        assertTrue(DEFAULT_TRACKERS.all { d -> r.any { it == d } })
    }

    @Test fun privateTorrentGetsNoDefaults() {
        val own = listOf("http://private.example/announce?passkey=abc")
        assertEquals(own, TrackerMerge.merge(own, emptyList()))
    }
}
