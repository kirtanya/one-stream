package com.kirtanya.reel.core

import com.kirtanya.reel.core.store.AddonCatalog
import com.kirtanya.reel.core.store.StoreKind
import com.kirtanya.reel.core.store.StoreQuery
import com.kirtanya.reel.core.store.StoreStatus
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
        fun names(q: StoreQuery, installed: Set<String> = emptySet()) = AddonCatalog.search(list, q, installed).map { it.name }
        assertEquals(listOf("OpenSubtitles v3", "WatchHub", "Debrid thing"), names(StoreQuery()))
        assertEquals(listOf("OpenSubtitles v3"), names(StoreQuery(kinds = setOf(StoreKind.SUBTITLES))))
        assertEquals(listOf("WatchHub"), names(StoreQuery("watch")))
        assertEquals(listOf("WatchHub"), names(StoreQuery("where to", kinds = setOf(StoreKind.STREAMS)))) // description match
        assertEquals(listOf("OpenSubtitles v3", "WatchHub", "Debrid thing"), names(StoreQuery(kinds = setOf(StoreKind.STREAMS, StoreKind.SUBTITLES))))
    }

    private val typed = "[" + listOf(
        item("Films", "https://f/manifest.json", """["stream"]""", ""","types":["movie"]"""),
        item("Shows", "https://s/manifest.json", """["stream","catalog"]""", ""","types":["series","movie"]"""),
        item("Subs", "https://u/manifest.json", """["subtitles"]""", ""","types":["movie","series"]"""),
        item("Setup", "https://x/manifest.json", """["stream"]""", ""","types":["tv"],"behaviorHints":{"configurationRequired":true}"""),
        item("CatalogOnly", "https://c/manifest.json", """["catalog"]""", ""","types":["movie"]"""),
    ).joinToString(",") + "]"

    @Test fun parsesTypesAndCatalogs() {
        val list = AddonCatalog.parse(typed, "official")
        assertEquals(listOf("series", "movie"), list.first { it.name == "Shows" }.types)
        assertTrue(list.first { it.name == "Shows" }.hasCatalogs)
        assertTrue(!list.first { it.name == "Films" }.hasCatalogs)
    }

    @Test fun facetsCountWhatIsListed() {
        val list = AddonCatalog.parse(typed, "official") + AddonCatalog.parse(typed, "community").map { it.copy(id = it.id + ".c") }.take(1)
        val f = AddonCatalog.facets(list, installedIds = setOf("id.Films"), catalogName = { if (it == "official") "Stremio official" else "Community" })
        assertEquals(listOf("Streams" to 4, "Subtitles" to 1, "Catalogs" to 1), f.kinds.map { it.label to it.count })
        assertEquals(listOf("Added" to 1, "Not added" to 3, "Needs setup" to 1), f.status.map { it.label to it.count })
        assertEquals(listOf("Movies" to 4, "Series" to 2, "Live TV" to 1), f.types.map { it.label to it.count })
        assertEquals(listOf("Stremio official" to 4, "Community" to 1), f.catalogs.map { it.label to it.count })
        // One catalog only: no catalog chips.
        assertTrue(AddonCatalog.facets(AddonCatalog.parse(typed, "official"), emptySet()).catalogs.isEmpty())
    }

    @Test fun filtersByStatusTypeAndCatalog() {
        val list = AddonCatalog.parse(typed, "official")
        fun names(q: StoreQuery) = AddonCatalog.search(list, q, setOf("id.Films")).map { it.name }
        assertEquals(listOf("Films"), names(StoreQuery(status = setOf(StoreStatus.ADDED))))
        assertEquals(listOf("Shows", "Subs"), names(StoreQuery(status = setOf(StoreStatus.NOT_ADDED))))
        assertEquals(listOf("Setup"), names(StoreQuery(status = setOf(StoreStatus.NEEDS_SETUP))))
        assertEquals(listOf("Shows", "Subs"), names(StoreQuery(types = setOf("series"))))
        assertEquals(listOf("Shows"), names(StoreQuery(types = setOf("series"), kinds = setOf(StoreKind.STREAMS))))
        assertEquals(listOf("Shows"), names(StoreQuery(kinds = setOf(StoreKind.CATALOGS))))
        assertEquals(emptyList(), names(StoreQuery(catalogs = setOf("elsewhere"))))
        // An added addon that needs setup counts as added.
        assertEquals(StoreStatus.ADDED, AddonCatalog.status(list.first { it.name == "Setup" }, setOf("id.Setup")))
    }

    @Test fun pruneDropsMissingChips() {
        val list = AddonCatalog.parse(typed, "official")
        val q = StoreQuery("x", types = setOf("series", "anime"), catalogs = setOf("gone"))
        val p = AddonCatalog.prune(q, AddonCatalog.facets(list, emptySet()))
        assertEquals(setOf("series"), p.types)
        assertTrue(p.catalogs.isEmpty())
        assertEquals("x", p.text)
    }
}
