package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.UsbPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbPathsTest {
    @Test fun volumeIdIsTheUuidOfARemovableVolumeOnly() {
        assertEquals("37EE-1DEE", UsbPaths.volumeIdOf("/storage/37EE-1DEE/Android/data/com.kirtanya.reel/files/downloads"))
        assertEquals("37EE-1DEE", UsbPaths.volumeIdOf("/storage/37EE-1DEE"))
        assertNull(UsbPaths.volumeIdOf("/storage/emulated/0/Android/data/x"))
        assertNull(UsbPaths.volumeIdOf("/storage/self/primary"))
        assertNull(UsbPaths.volumeIdOf("/data/user/0/com.kirtanya.reel/files/downloads"))
        assertNull(UsbPaths.volumeIdOf("/storage/"))
    }

    @Test fun missingMeansOnAUsbVolumeThatIsNotMounted() {
        val saved = "/storage/37EE-1DEE/Android/data/com.kirtanya.reel/files/downloads"
        assertTrue(UsbPaths.isMissing(saved, emptySet()))
        assertTrue(UsbPaths.isMissing(saved, setOf("AAAA-BBBB")))
        assertFalse(UsbPaths.isMissing(saved, setOf("37EE-1DEE")))
        assertFalse(UsbPaths.isMissing("/data/user/0/x/files/downloads", emptySet()))
    }

    @Test fun fatSafePathsTouchesOnlyWhatFatRefuses() {
        val paths = listOf("Show S01/ep1.mkv", "Show S01/what? ep2: final.mkv", "Show S01/ends with dot.", "Show S01/ok.srt")
        val fixed = UsbPaths.fatSafePaths(paths)
        assertEquals(setOf(1, 2), fixed.keys)
        assertEquals("Show S01/what ep2 final.mkv", fixed[1])
        assertEquals("Show S01/ends with dot", fixed[2])
    }

    @Test fun aRenameNeverCollidesWithAnotherFileCaseIgnored() {
        val fixed = UsbPaths.fatSafePaths(listOf("T/a?b.mkv", "T/A B.MKV", "T/a*b.mkv"))
        // "a?b.mkv" → "a b.mkv" collides with "A B.MKV" (FAT ignores case); "a*b.mkv" collides with that and the first rename.
        assertEquals("T/a b (2).mkv", fixed[0])
        assertEquals("T/a b (3).mkv", fixed[2])
        assertEquals(setOf(0, 2), fixed.keys)
    }

    @Test fun cleanPathsAreLeftAlone() = assertTrue(UsbPaths.fatSafePaths(listOf("Movie (2020)/Movie.2020.1080p.mkv", "Movie (2020)/Sample/s.mkv")).isEmpty())
}
