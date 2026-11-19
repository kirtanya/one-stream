package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceFacts
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRanker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackRankingTest {
    private fun bt(title: String, seeders: Int, res: Int = 1080) =
        Source(SourceKind.TORRENT, title, "p", Quality(resolution = res), infoHash = title.hashCode().toUInt().toString(16).padStart(40, '0'), seeders = seeders)

    @Test fun multiSeasonPacksAreDetected() {
        for (t in listOf(
            "Better Call Saul S01-S06 1080p BluRay", "Show Seasons 1-5 Complete", "Breaking Bad Complete Series 1080p",
            "Marvel Collection 1080p", "Show S01-06 WEB-DL", "Lord of the Rings Trilogy 1080p",
        )) assertTrue(SourceFacts.isMultiSeasonPack(t), t)
    }

    @Test fun singleReleasesAreNotPacks() {
        for (t in listOf("Show S03E04 1080p WEB-DL", "Show S03 1080p BluRay", "Film 2160p Season 1 - 2160p", "Film 1080p, 2160p", "Completely Different 1080p"))
            assertFalse(SourceFacts.isMultiSeasonPack(t), t)
    }

    @Test fun equalHealthPrefersTheSingleRelease() {
        val pack = bt("Show S01-S06 1080p", 100)
        val single = bt("Show S03E04 1080p", 100)
        assertEquals(single.key, SourceRanker.rank(listOf(pack, single)).first().key)
    }

    @Test fun aMuchBetterSeededPackStillWins() {
        val pack = bt("Show S01-S06 1080p", 1000)
        val single = bt("Show S03E04 1080p", 20)
        assertEquals(pack.key, SourceRanker.rank(listOf(single, pack)).first().key)
    }

    @Test fun deadTorrentsSinkBelowLivePacks() {
        val pack = bt("Show Complete Series 1080p", 8)
        val dead = bt("Show S03E04 1080p", 0)
        assertEquals(pack.key, SourceRanker.rank(listOf(dead, pack)).first().key)
    }
}
