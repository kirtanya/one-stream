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

/**
 * youtube-official.js against saved channel search pages (core/src/test/resources/youtube, trimmed from the real
 * ytInitialData of Shemaroo Movies' search on 2026-11-09; no personal data).
 */
class YoutubeOfficialPluginTest {
    private val plugin = File("../app/src/main/assets/plugins/youtube-official.js").readText()
    private fun fixture(name: String) = File("src/test/resources/youtube/$name").readText()

    private val shemaroo = "UCBOmfqgTZi7yDp4-3Lr_3lA"
    private val goldmines = "UCOF23vGxkbhN4wl7ROrgXsA"

    private class Net(val language: String?, val pages: Map<String, String>) {
        val urls = mutableListOf<String>()
        val fetcher = HttpFetcher { r ->
            urls += r.url
            val body = when {
                r.url.startsWith("https://api.themoviedb.org/") ->
                    if (language == null) null else """{"original_language":"$language"}"""
                r.url.startsWith("https://www.youtube.com/channel/") ->
                    pages[r.url.substringAfter("/channel/").substringBefore("/")] ?: "<html></html>"
                else -> "{}"
            }
            if (body == null) FetchResponse(500, r.url, emptyMap(), ByteArray(0)) else FetchResponse(200, r.url, emptyMap(), body.toByteArray())
        }
        val youtubeUrls get() = urls.filter { it.contains("youtube.com") }
    }

    private fun search(net: Net, title: String, year: Int?) = runBlocking {
        PluginRuntime(net.fetcher).getStreams(plugin, "Free on YouTube", SourceRequest(1, null, MediaType.MOVIE, title, year))
    }

    private fun deewaarNet(language: String? = "hi") = Net(language, mapOf(shemaroo to fixture("shemaroo-search-deewaar.html")))

    @Test fun fullFilmBecomesAnExternalYoutubeLink() {
        val s = search(deewaarNet(), "Deewaar", 1975).single()
        assertEquals(SourceKind.EXTERNAL, s.kind)
        assertEquals("https://www.youtube.com/watch?v=xuOQqWU2UQA", s.externalUrl)
        assertEquals("Free on YouTube · Shemaroo Movies", s.provider)
        assertTrue(s.title.contains("2h 46m"), s.title)
        assertEquals(null, s.url)
    }

    @Test fun theYearKeepsRemakesApart() {
        assertEquals("uQK7NG89IS8", search(deewaarNet(), "Deewaar", 2004).single().externalUrl!!.substringAfter("v="))
    }

    @Test fun clipsAndPromosUnder70MinutesNeverMatch() {
        // The page also lists a 1:00 and a 0:25 "Deewaar" promo; neither may come back for either year.
        val ids = (search(deewaarNet(), "Deewaar", 1975) + search(deewaarNet(), "Deewaar", 2004)).map { it.externalUrl }
        assertTrue(ids.none { it!!.endsWith("NnzUT2xoxQ0") || it.endsWith("yOU7U2Wjb4c") }, ids.toString())
    }

    @Test fun aTitleThatIsOnlyPartOfAnotherFilmsNameDoesNotMatch() {
        val net = Net("hi", mapOf(shemaroo to fixture("shemaroo-search-don.html")))
        assertTrue(search(net, "Don", 2006).isEmpty())
        assertTrue(search(net, "Shool", 1999).single().externalUrl!!.endsWith("MKf4EwIfGi0"))
    }

    @Test fun yearsWrittenBareOrInBracketsBothCount() {
        val net = Net("hi", mapOf(goldmines to fixture("goldmines-bollywood-search-full-movie.html")))
        // "Ghar Ho To Aisa 1990 | Full Hindi Movie | cast": bare year.
        assertEquals("fKBJCt_vOsk", search(net, "Ghar Ho To Aisa", 1990).single().externalUrl!!.substringAfter("v="))
        assertTrue(search(net, "Ghar Ho To Aisa", 2005).isEmpty())
        // A film whose name is a number, and two uploads of the same film: both, newest search order first, at most 2.
        assertEquals("tj814hcan3c", search(net, "1920", 2008).single().externalUrl!!.substringAfter("v="))
        assertEquals(listOf("cSIZyglAB2c", "XZ8T_KrHTPM"), search(net, "Phool", 1993).map { it.externalUrl!!.substringAfter("v=") })
        assertEquals("Free on YouTube · Goldmines Bollywood", search(net, "Phool", 1993).first().provider)
    }

    @Test fun laterTitleSegmentsNeedTheYear() {
        // "<Hindi quote> | Deewaar Full Movie (HD) | Amitabh…" has no year, and its cast is the 2004 remake's.
        val ids = search(deewaarNet(), "Deewaar", 1975).map { it.externalUrl }
        assertTrue(ids.none { it!!.endsWith("95lRewQ3As0") }, ids.toString())
    }

    @Test fun anEnglishFilmAsksNoYoutubeChannelAtAll() {
        val net = deewaarNet("en")
        assertTrue(search(net, "Deewaar", 1975).isEmpty())
        assertEquals(emptyList(), net.youtubeUrls)
    }

    @Test fun anIndianOriginalLanguageAsksEveryAllowedChannelByIdWithTheTitle() {
        val net = deewaarNet("te")
        search(net, "Deewaar", 1975)
        assertEquals(5, net.youtubeUrls.size)
        assertTrue(net.youtubeUrls.all { it.startsWith("https://www.youtube.com/channel/UC") && it.endsWith("/search?query=Deewaar") }, net.youtubeUrls.toString())
    }

    @Test fun anUnknownLanguageOnlyAsksTheTwoHindiFilmChannels() {
        val net = deewaarNet(null)
        assertEquals(1, search(net, "Deewaar", 1975).size)
        assertEquals(2, net.youtubeUrls.size)
    }

    @Test fun aPageThatIsNotTheExpectedChannelIsIgnored() {
        // Goldmines' id served Shemaroo's page (a handle / redirect mix-up): its videos must not be trusted.
        val net = Net("hi", mapOf(goldmines to fixture("shemaroo-search-deewaar.html")))
        assertTrue(search(net, "Deewaar", 1975).isEmpty())
    }

    @Test fun neverMoreThanTwoAndNeverForShows() {
        val net = deewaarNet("hi")
        assertTrue(search(net, "Deewaar", 1975).size <= 2)
        val tv = runBlocking { PluginRuntime(net.fetcher).getStreams(plugin, "Free on YouTube", SourceRequest(1, null, MediaType.TV, "Deewaar", 1975, 1, 1)) }
        assertTrue(tv.isEmpty())
    }

    @Test fun manifestRegistersTheAllowListedPlugin() {
        val m = NuvioPlugins.parseManifest(File("../app/src/main/assets/plugins/manifest.json").readText())
        val p = m.scrapers.single { it.id == "reel-youtube-official" }
        assertEquals("Free on YouTube", p.name)
        assertEquals(listOf("movie"), p.supportedTypes)
        // Only the five verified channel ids may ever be queried.
        val ids = Regex("""id: '(UC[\w-]{22})'""").findAll(plugin).map { it.groupValues[1] }.toList()
        assertEquals(
            setOf("UCBOmfqgTZi7yDp4-3Lr_3lA", "UCOF23vGxkbhN4wl7ROrgXsA", "UCyoXW-Dse7fURq30EWl_CUA", "UCObyBrdrtQ20BU9PxHQDY9w", "UCEKWXRsfUHkan-D_ljU8Asw"),
            ids.toSet(),
        )
    }

    @Test fun pluginResultsOnlyOpenWebLinksExternally() {
        val body = """[{"name":"A","provider":"P","title":"web","externalUrl":"https://www.youtube.com/watch?v=abcdefghijk"},
            {"name":"B","title":"intent","externalUrl":"intent://x#Intent;scheme=https;package=evil;end"},
            {"name":"C","title":"tel","externalUrl":"tel:112"}]"""
        val list = NuvioPlugins.parseResults(body, "Free on YouTube")
        assertEquals(1, list.size)
        assertEquals(SourceKind.EXTERNAL, list[0].kind)
        assertEquals("Free on YouTube · P", list[0].provider)
        assertEquals("ext:https://www.youtube.com/watch?v=abcdefghijk", list[0].key)
    }
}
