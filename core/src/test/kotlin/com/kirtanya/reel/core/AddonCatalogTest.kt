package com.kirtanya.reel.core

import com.kirtanya.reel.core.store.AddonCatalog
import com.kirtanya.reel.core.store.StoreFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddonCatalogTest {
    private fun item(name: String, url: String, resources: String, extra: String = "", desc: String = "") =
        """{"transportUrl":"$url","transportName":"http","manifest":{"id":"id.$name","name":"$name","version":"1.0.0","description":"$desc","resources":$resources$extra}}"""

    private val official = "[" + listOf(
        item("WatchHub", "https://watchhub.strem.io/manifest.json", """["stream"]""", desc = "Where to watch"),
        item("OpenSubtitles v3", "https://opensubtitles-v3.strem.io/manifest.json", """["subtitles"]"""),
        item("Anime Catalogs", "https://x/manifest.json", """["catalog","meta"]"""),
        item("Debrid thing", "https://d/manifest.json", """[{"name":"stream","types":["movie"]}]""", ""","behaviorHints":{"configurable":true,"configurationRequired":true}"""),
        """{"transportUrl":"ftp://bad/manifest.json","manifest":{"name":"Bad","resources":["stream"]}}""",
    ).joinToString(",") + "]"

    @Test fun parsesTheOfficialArray() {
        val list = AddonCatalog.parse(official, "Stremio")
        assertEquals(listOf("WatchHub", "OpenSubtitles v3", "Anime Catalogs", "Debrid thing"), list.map { it.name })
        val debrid = list.last()
        assertTrue(debrid.hasStreams && debrid.needsSetup && debrid.configurable)
        assertEquals("https://d/configure", debrid.configureUrl)
    }

    @Test fun parsesAddonCatalogResponses() {
        val body = """{"addons":[${item("WatchHub", "https://watchhub.strem.io/manifest.json", """["stream"]""")}]}"""
        assertEquals("WatchHub", AddonCatalog.parse(body, "Mine").single().name)
        assertTrue(AddonCatalog.parse("<html>", "x").isEmpty())
    }

    @Test fun searchHidesUnusableAndFilters() {
        val list = AddonCatalog.parse(official, "Stremio")
        assertEquals(listOf("OpenSubtitles v3", "WatchHub", "Debrid thing"), AddonCatalog.search(list, "", StoreFilter.ALL).map { it.name })
        assertEquals(listOf("OpenSubtitles v3"), AddonCatalog.search(list, "", StoreFilter.SUBTITLES).map { it.name })
        assertEquals(listOf("WatchHub"), AddonCatalog.search(list, "watch", StoreFilter.ALL).map { it.name })
        assertEquals(listOf("WatchHub"), AddonCatalog.search(list, "where to", StoreFilter.STREAMS).map { it.name }) // description match
    }
}
