package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.FetchResponse
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.NuvioPlugins
import com.kirtanya.reel.core.sources.PluginRuntime
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.tmdb.MediaType
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the app's bundled plugins (app/src/main/assets/plugins) against canned responses. */
class BuiltinPluginsTest {
    private val dir = File("../app/src/main/assets/plugins")
    private fun code(name: String) = File(dir, name).readText()

    private val fake = HttpFetcher { r ->
        val body = when {
            r.url.startsWith("https://archive.org/advancedsearch.php") ->
                """{"response":{"docs":[
                    {"identifier":"NOTLD_trailer","title":"Night of the Living Dead trailer","year":"1968"},
                    {"identifier":"other","title":"Dawn of the Dead","year":"1968"},
                    {"identifier":"NOTLD_1968","title":"Night of the Living Dead","year":"1968","licenseurl":"http://creativecommons.org/licenses/publicdomain/"}]}}"""
            r.url == "https://archive.org/metadata/NOTLD_1968" ->
                """{"files":[{"name":"notld_1080p.mkv","size":"11523767255","height":"1080"},
                             {"name":"notld.mp4","size":"1862510336","height":"720"},
                             {"name":"tiny.mp4","size":"1000"},{"name":"notes.txt","size":"9999999999"}]}"""
            else -> "{}"
        }
        FetchResponse(200, r.url, emptyMap(), body.toByteArray())
    }
    private val runtime = PluginRuntime(fake)

    @Test fun manifestListsTheBuiltInPlugins() {
        val m = NuvioPlugins.parseManifest(File(dir, "manifest.json").readText())
        assertEquals(listOf("reel-open-movies", "reel-internet-archive", "reel-youtube-official"), m.scrapers.map { it.id })
        m.scrapers.forEach { assertTrue(File(dir, it.filename).isFile, it.filename) }
    }

    @Test fun openMoviesGivesAWebSeededMagnet(): Unit = runBlocking {
        val req = SourceRequest(10378, "tt1254207", MediaType.MOVIE, "Big Buck Bunny", 2008)
        val s = runtime.getStreams(code("open-movies.js"), "Open movies", req).single()
        assertEquals(SourceKind.TORRENT, s.kind)
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", s.infoHash)
        assertTrue(s.magnet!!.contains("&ws="))
        assertEquals("Big Buck Bunny.mp4", s.filename)
        assertTrue(runtime.getStreams(code("open-movies.js"), "Open movies", req.copy(tmdbId = 1)).isEmpty())
    }

    @Test fun internetArchiveGivesDirectLinksBestFirst(): Unit = runBlocking {
        val req = SourceRequest(10331, "tt0063350", MediaType.MOVIE, "Night of the Living Dead", 1968)
        val list = runtime.getStreams(code("internet-archive.js"), "Internet Archive", req)
        assertEquals(2, list.size)
        assertEquals(SourceKind.DIRECT, list[0].kind)
        assertEquals("https://archive.org/download/NOTLD_1968/notld_1080p.mkv", list[0].url)
        assertEquals(1080, list[0].quality.resolution)
        assertEquals(720, list[1].quality.resolution)
        assertTrue(list[0].title.contains("public domain"))
    }

    @Test fun internetArchiveOnlySearchesTheCuratedPublicDomainCollection(): Unit = runBlocking {
        val urls = mutableListOf<String>()
        val spy = PluginRuntime({ r -> urls += r.url; fake.fetch(r) })
        spy.getStreams(code("internet-archive.js"), "IA", SourceRequest(550, null, MediaType.MOVIE, "Fight Club", 1999))
        val query = java.net.URLDecoder.decode(urls.first(), "UTF-8")
        assertTrue(query.contains("AND collection:feature_films"), query)
        assertTrue(!query.contains("licenseurl"), query) // uploader-set licences aren't trusted
        assertTrue(query.contains("year:[1998 TO 2000]"), query)
    }

    private fun iaQuery(req: SourceRequest): Pair<String, Int> {
        val urls = mutableListOf<String>()
        val spy = PluginRuntime({ r -> urls += r.url; fake.fetch(r) })
        runBlocking { spy.getStreams(code("internet-archive.js"), "IA", req) }
        val searches = urls.filter { it.startsWith("https://archive.org/advancedsearch.php") }
        return java.net.URLDecoder.decode(searches.first(), "UTF-8") to searches.size
    }

    @Test fun internetArchiveAlsoSearchesTheRomanisedOriginalTitleInOneRequest() {
        val (query, searches) = iaQuery(SourceRequest(1, null, MediaType.MOVIE, "Mughal-E-Azam: The Great Mughal", 1960, originalTitle = "Mughal-E-Azam"))
        assertTrue(query.contains("title:(\"Mughal-E-Azam: The Great Mughal\" OR \"Mughal-E-Azam\")"), query)
        assertTrue(query.contains("AND collection:feature_films"), "still only the curated collection: $query")
        assertEquals(1, searches, "one search request for both titles")
    }

    @Test fun internetArchiveSkipsAnOriginalTitleThatAddsNothing() {
        // Same as the title (ignoring case and punctuation), non-Latin, or missing: the query is the plain one.
        listOf("night of the living dead!", "मुग़ल-ए-आज़म", "鬼滅の刃", "", null).forEach { original ->
            val (query, _) = iaQuery(SourceRequest(2, null, MediaType.MOVIE, "Night of the Living Dead", 1968, originalTitle = original))
            assertTrue(query.contains("title:(\"Night of the Living Dead\") AND"), "$original: $query")
        }
    }

    @Test fun internetArchiveKeepsAFilmFoundUnderItsOriginalTitle(): Unit = runBlocking {
        val hit = HttpFetcher { r ->
            val body = when {
                r.url.startsWith("https://archive.org/advancedsearch.php") -> """{"response":{"docs":[{"identifier":"mea","title":"Mughal-E-Azam","year":"1960"}]}}"""
                r.url == "https://archive.org/metadata/mea" -> """{"files":[{"name":"mea.mp4","size":"1862510336","height":"480"}]}"""
                else -> "{}"
            }
            FetchResponse(200, r.url, emptyMap(), body.toByteArray())
        }
        val list = PluginRuntime(hit).getStreams(code("internet-archive.js"), "IA", SourceRequest(3, null, MediaType.MOVIE, "Mughal-E-Azam: The Great Mughal", 1960, originalTitle = "Mughal-E-Azam"))
        assertEquals(1, list.size)
        assertEquals("https://archive.org/download/mea/mea.mp4", list[0].url)
        // Without the original title the same answer is dropped: its title doesn't start with the English one.
        assertTrue(PluginRuntime(hit).getStreams(code("internet-archive.js"), "IA", SourceRequest(3, null, MediaType.MOVIE, "Mughal-E-Azam: The Great Mughal", 1960)).isEmpty())
    }
}
