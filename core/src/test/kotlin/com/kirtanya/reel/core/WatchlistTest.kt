package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.Watchlist
import com.kirtanya.reel.core.library.WatchlistItem
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchlistTest {
    private fun item(key: String, at: Long, removed: Boolean = false) = WatchlistItem(key, key, changedAt = at, removed = removed)
    private val now = 1_000_000_000L

    @Test fun newerChangeWinsEitherWay(): Unit {
        // Phone removed it after the TV added it → gone.
        val a = Watchlist.merge(listOf(item("movie:1", 100)), listOf(item("movie:1", 200, removed = true)), now)
        assertTrue(Watchlist.visible(a).isEmpty())
        // TV saved it again after the phone's removal → stays.
        val b = Watchlist.merge(listOf(item("movie:1", 300)), listOf(item("movie:1", 200, removed = true)), now)
        assertEquals(listOf("movie:1"), Watchlist.visible(b).map { it.key })
    }

    @Test fun titlesSavedOnlyOnTheTvSurvive(): Unit {
        val merged = Watchlist.merge(listOf(item("tv:9", 50)), listOf(item("movie:1", 60)), now)
        assertEquals(listOf("movie:1", "tv:9"), Watchlist.visible(merged).map { it.key })
    }

    @Test fun oldTombstonesAreDropped(): Unit {
        val merged = Watchlist.merge(emptyList(), listOf(item("movie:1", now - Watchlist.TOMBSTONE_MS - 1, removed = true), item("movie:2", now - 10, removed = true)), now)
        assertEquals(listOf("movie:2"), merged.map { it.key })
    }

    @Test fun refsRoundTrip(): Unit {
        assertEquals(TitleRef(MediaType.TV, 1399), Watchlist.parseRef("tv:1399"))
        assertNull(Watchlist.parseRef("person:3"))
        assertNull(Watchlist.parseRef("movie"))
    }

    @Test fun bundleStaysCompatibleWithOlderApps(): Unit {
        val json = Json { ignoreUnknownKeys = true }
        // An older phone sends no watchlist → null (the TV leaves its list alone).
        assertNull(json.decodeFromString(SourcesBundle.serializer(), """{"addons":[],"repos":[]}""").watchlist)
        // A bundle without a watchlist doesn't mention it at all (older TVs never see the field).
        assertFalse("watchlist" in json.encodeToString(SourcesBundle.serializer(), SourcesBundle()))
        val withList = SourcesBundle(watchlist = listOf(item("movie:1", 5)))
        assertEquals(withList, json.decodeFromString(SourcesBundle.serializer(), json.encodeToString(SourcesBundle.serializer(), withList)))
    }
}
