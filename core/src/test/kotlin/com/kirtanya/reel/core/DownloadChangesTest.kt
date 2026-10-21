package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.DownloadOrigins
import com.kirtanya.reel.core.library.SourceSwap
import com.kirtanya.reel.core.library.SwapKind
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.ModeChange
import com.kirtanya.reel.core.stream.ModeFacts
import com.kirtanya.reel.core.stream.ModeStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure decisions only: nothing here touches the engine or the network. */
class DownloadChangesTest {
    private val hash = "a".repeat(40)

    private fun torrent(h: String, file: Int?) = Source(SourceKind.TORRENT, "x", "p", Quality(1080), infoHash = h, fileIdx = file)

    @Test fun swapKinds() {
        assertEquals(SwapKind.SAME_FILE, SourceSwap.kind(hash, 2, torrent(hash.uppercase(), 2)))
        assertEquals(SwapKind.SAME_FILE, SourceSwap.kind(hash, null, torrent(hash, 4)))
        assertEquals(SwapKind.SAME_TORRENT, SourceSwap.kind(hash, 2, torrent(hash, 3)))
        assertEquals(SwapKind.NEW_TORRENT, SourceSwap.kind(hash, 2, torrent("b".repeat(40), 2)))
        assertEquals(SwapKind.DIRECT, SourceSwap.kind(hash, 2, Source(SourceKind.DIRECT, "x", "p", Quality(), url = "https://e/x.mp4")))
        assertEquals(SwapKind.NOT_DOWNLOADABLE, SourceSwap.kind(hash, 2, Source(SourceKind.EXTERNAL, "x", "p", Quality(), externalUrl = "https://e")))
    }

    @Test fun confirmationSaysWhatIsLost() {
        val label = "Dune: Part Two"
        assertNull(SourceSwap.confirm(SwapKind.SAME_FILE, label, finished = false, done = "1 GB"))
        val lost = SourceSwap.confirm(SwapKind.NEW_TORRENT, label, finished = false, done = "4.2 GB")!!
        assertTrue("4.2 GB downloaded so far will be lost" in lost.body)
        assertEquals("Replace", lost.confirm)
        assertTrue("Nothing has been downloaded yet" in SourceSwap.confirm(SwapKind.DIRECT, label, false, "0 B")!!.body)
        assertTrue("finished download will be deleted" in SourceSwap.confirm(SwapKind.NEW_TORRENT, label, true, "9 GB")!!.body)
        // Same torrent: nothing is lost.
        val same = SourceSwap.confirm(SwapKind.SAME_TORRENT, label, false, "4.2 GB")!!
        assertTrue("kept" in same.body && "lost" !in same.body)
    }

    @Test fun originsRoundTrip() {
        val map = mapOf(hash to DownloadOrigin("tv", 1399, 2, 6, "The Night Manager · S2 E6"), "b".repeat(40) to DownloadOrigin("movie", 550, label = "Fight Club"))
        val back = DownloadOrigins.parse(DownloadOrigins.encode(map))
        assertEquals(map, back)
        assertTrue(DownloadOrigins.parse("not json").isEmpty())
        assertTrue(DownloadOrigins.parse(null).isEmpty())
    }

    @Test fun keepAsStreamCache() {
        assertEquals(ModeStep.NOW, ModeChange.toCache(ModeFacts(isDownload = true, claimed = false)))
        assertEquals(ModeStep.DEFER, ModeChange.toCache(ModeFacts(isDownload = true, claimed = true)))
        assertEquals(ModeStep.NONE, ModeChange.toCache(ModeFacts(isDownload = false, claimed = false)))
        // Save as download was asked while playing, then Keep as stream cache: the later wish wins.
        assertEquals(ModeStep.CANCEL_PENDING, ModeChange.toCache(ModeFacts(isDownload = false, claimed = true, keepPending = true)))
    }

    @Test fun saveAsDownload() {
        assertEquals(ModeStep.NOW, ModeChange.toDownload(ModeFacts(isDownload = false, claimed = false)))
        assertEquals(ModeStep.DEFER, ModeChange.toDownload(ModeFacts(isDownload = false, claimed = true)))
        assertEquals(ModeStep.NONE, ModeChange.toDownload(ModeFacts(isDownload = true, claimed = false)))
        assertEquals(ModeStep.CANCEL_PENDING, ModeChange.toDownload(ModeFacts(isDownload = true, claimed = true, cachePending = true)))
    }

    @Test fun keepAsCacheConfirmation() {
        val done = ModeChange.cacheConfirm(finished = true)
        assertTrue("stays on this device as stream cache" in done && "stops" !in done)
        val partial = ModeChange.cacheConfirm(finished = false)
        assertTrue("Downloading stops" in partial && "deleted automatically when space is needed" in partial)
    }

    @Test fun cacheRemembersTheFileToPlay() {
        assertEquals(3, ModeChange.cacheStreamFile(3, listOf(1, 2), 0))
        assertEquals(1, ModeChange.cacheStreamFile(null, listOf(4, 1), 0))
        assertEquals(0, ModeChange.cacheStreamFile(null, emptyList(), 0))
        assertNull(ModeChange.cacheStreamFile(null, emptyList(), null))
        assertEquals(200L, ModeChange.cacheLastUsedAt(200, 100))
        assertEquals(300L, ModeChange.cacheLastUsedAt(200, 300))
    }
}
