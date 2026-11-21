package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.LibraryFacts
import com.kirtanya.reel.core.library.LibraryFilter
import com.kirtanya.reel.core.library.LibraryList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryListTest {
    private val downloading = LibraryFacts(kept = true, finished = false)
    private val keptDone = LibraryFacts(kept = true, finished = true)
    private val played = LibraryFacts(kept = false, finished = false)
    private val playingNow = LibraryFacts(kept = false, finished = false, streaming = true)

    @Test fun filters() {
        assertTrue(listOf(downloading, keptDone, played).all { LibraryList.matches(it, LibraryFilter.ALL) })
        assertTrue(LibraryList.matches(downloading, LibraryFilter.DOWNLOADING))
        assertFalse(LibraryList.matches(keptDone, LibraryFilter.DOWNLOADING))
        assertFalse(LibraryList.matches(played, LibraryFilter.DOWNLOADING))
        assertTrue(LibraryList.matches(downloading, LibraryFilter.KEPT) && LibraryList.matches(keptDone, LibraryFilter.KEPT))
        assertFalse(LibraryList.matches(played, LibraryFilter.KEPT))
        assertFalse(LibraryList.matches(keptDone, LibraryFilter.HOME))
    }

    @Test fun noStreamCacheTab() {
        assertEquals(listOf("All", "Downloading", "Kept", "Home disk"), LibraryFilter.entries.map { it.label })
    }

    @Test fun orderPutsDownloadingFirstThenPlayingThenTheRestStable() {
        val items = listOf("a" to played, "b" to keptDone, "c" to downloading, "d" to playingNow, "e" to played, "f" to downloading)
        assertEquals(listOf("c", "f", "d", "a", "b", "e"), LibraryList.order(items) { it.second }.map { it.first })
    }

    @Test fun copy() {
        assertEquals("Played recently · 1.2 GB · not kept", LibraryList.notKeptLine("1.2 GB"))
        assertEquals("Played recently · not kept", LibraryList.notKeptLine(null))
        assertTrue("stops" !in LibraryList.unkeepConfirm(true))
        assertTrue(LibraryList.unkeepConfirm(false).startsWith("Downloading stops."))
    }
}
