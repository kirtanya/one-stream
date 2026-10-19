package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.FetchResponse
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.NuvioPlugins
import com.kirtanya.reel.core.sources.PluginException
import com.kirtanya.reel.core.sources.PluginRuntime
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.RankPrefs
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceAggregator
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceProvider
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.StremioAddon
import com.kirtanya.reel.core.sources.TMDB_KEY_PLACEHOLDER
import com.kirtanya.reel.core.tmdb.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MOVIE = SourceRequest(tmdbId = 10378, imdbId = "tt1254207", type = MediaType.MOVIE, title = "Big Buck Bunny", year = 2008)
private val EPISODE = SourceRequest(tmdbId = 1399, imdbId = "tt0944947", type = MediaType.TV, title = "Game of Thrones", year = 2011, season = 1, episode = 3)

class QualityTest {
    @Test fun resolutions() {
        assertEquals(2160, QualityParser.parse("Movie.2024.2160p.WEB-DL.DV.HDR.H265").resolution)
        assertEquals(1080, QualityParser.parse("Movie 1080p BluRay x264").resolution)
        assertEquals(720, QualityParser.parse("[720p] Movie").resolution)
        assertNull(QualityParser.parse("Movie WEBRip").resolution)
    }

    @Test fun flags() {
        val q = QualityParser.parse("Movie.2160p.DV.HDR10.HEVC")
        assertTrue(q.dolbyVision && q.hdr && q.hevc)
        assertEquals("4K DV", q.label)
        assertTrue(QualityParser.parse("Movie 2024 HDCAM").lowQualityRip)
        assertFalse(QualityParser.parse("Movie 1080p HDR").lowQualityRip)
    }

    @Test fun sizeAndSeeders() {
        assertEquals((2.35 * (1L shl 30)).toLong(), QualityParser.parseSize("👤 120 💾 2.35 GB ⚙️ x"))
        assertEquals(700L shl 20, QualityParser.parseSize("700 MB"))
        assertEquals(120, QualityParser.parseSeeders("👤 120 💾 2.35 GB"))
        assertEquals(42, QualityParser.parseSeeders("Seeders: 42"))
    }
}

class RankerTest {
    private fun t(res: Int?, seeds: Int?, size: Long? = null, cam: Boolean = false) =
        Source(SourceKind.TORRENT, "x$res$seeds", "p", Quality(resolution = res, lowQualityRip = cam), infoHash = "a".repeat(40), seeders = seeds, sizeBytes = size)

    @Test fun prefers1080WithSeeders() {
        val ranked = SourceRanker.rank(listOf(t(720, 500), t(1080, 50), t(2160, 50), t(1080, 0)))
        assertEquals(1080, ranked.first().quality.resolution)
        assertEquals(0, ranked.last().seeders) // dead torrents sink
    }

    @Test fun camsSink() {
        assertEquals(false, SourceRanker.rank(listOf(t(1080, 900, cam = true), t(720, 10))).first().quality.lowQualityRip)
    }

    @Test fun tooBigForTheTvSinks() {
        val prefs = RankPrefs(maxBytes = 4L shl 30)
        val ranked = SourceRanker.rank(listOf(t(1080, 300, size = 20L shl 30), t(1080, 30, size = 2L shl 30)), prefs)
        assertEquals(2L shl 30, ranked.first().sizeBytes)
        assertFalse(SourceRanker.fits(ranked.last(), prefs))
    }

    @Test fun externalLinksLast() {
        val ext = Source(SourceKind.EXTERNAL, "Netflix", "p", Quality(), externalUrl = "https://netflix.com/x")
        assertEquals(SourceKind.EXTERNAL, SourceRanker.rank(listOf(ext, t(480, 1))).last().kind)
    }
}

class StremioTest {
    private val manifest = StremioAddon.parseManifest(
        """{"id":"org.test","name":"Test","version":"1.0.0","types":["movie","series"],
            "resources":["catalog",{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]}]}""",
    )

    @Test fun urls() {
        assertEquals("https://addon.example/abc", StremioAddon.baseUrl("https://addon.example/abc/manifest.json"))
        assertEquals("https://addon.example/abc/manifest.json", StremioAddon.manifestUrl("stremio://addon.example/abc/manifest.json"))
        assertEquals("tt0944947:1:3", manifest.streamIdFor(EPISODE))
        assertEquals(
            "https://addon.example/abc/stream/series/tt0944947:1:3.json",
            StremioAddon.streamUrl("https://addon.example/abc/manifest.json", EPISODE, "tt0944947:1:3"),
        )
    }

    @Test fun noImdbIdMeansUnsupportedForTtAddons() {
        assertNull(manifest.streamIdFor(MOVIE.copy(imdbId = null)))
        val tmdbAddon = StremioAddon.parseManifest("""{"id":"x","name":"X","types":["movie"],"resources":["stream"],"idPrefixes":["tmdb:"]}""")
        assertEquals("tmdb:10378", tmdbAddon.streamIdFor(MOVIE))
    }

    @Test fun parsesTorrentDirectAndExternalStreams() {
        val body = """{"streams":[
            {"name":"Addon\n1080p","title":"Big.Buck.Bunny.1080p.x264\n👤 25 💾 1.2 GB","infoHash":"DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C","fileIdx":1,
             "sources":["tracker:udp://a:1","dht:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"],"behaviorHints":{"bingeGroup":"g1","filename":"bbb.mp4"}},
            {"name":"HTTP 720p","url":"https://cdn.example/bbb.mp4","behaviorHints":{"notWebReady":true,"proxyHeaders":{"request":{"Referer":"https://x"}}}},
            {"name":"Netflix","title":"Subscription","externalUrl":"https://www.netflix.com/title/1","androidUrl":"https://www.netflix.com/watch/1","androidTvUrl":"intent://www.netflix.com/watch/1#Intent;scheme=https;package=com.netflix.ninja;end"},
            {"name":"broken"}
        ]}"""
        val list = StremioAddon.parseStreams(body, "Test")
        assertEquals(listOf(SourceKind.TORRENT, SourceKind.DIRECT, SourceKind.EXTERNAL), list.map { it.kind })
        val bt = list[0]
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", bt.infoHash)
        assertEquals(1, bt.fileIdx)
        assertEquals(25, bt.seeders)
        assertEquals(listOf("udp://a:1"), bt.trackers)
        assertEquals(1080, bt.quality.resolution)
        assertEquals("g1", bt.bingeGroup)
        assertTrue(bt.magnetUri()!!.startsWith("magnet:?xt=urn:btih:dd8255"))
        assertEquals(mapOf("Referer" to "https://x"), list[1].headers)
        assertEquals(720, list[1].quality.resolution)
        assertEquals("Netflix · Subscription", list[2].title)
        assertEquals("https://www.netflix.com/watch/1", list[2].appUrl)
        assertTrue(list[2].tvAppUrl!!.startsWith("intent://"))
    }
}

class NuvioPluginsTest {
    @Test fun manifestAndCodeUrl() {
        val m = NuvioPlugins.parseManifest(
            """{"name":"Repo","version":"1.2","scrapers":[
                {"id":"a","name":"A","version":"1","filename":"providers/a.js","supportedTypes":["movie","series"]},
                {"id":"b","name":"B","version":"1","filename":"b.js","enabled":false},
                {"name":"no id"}]}""",
        )
        assertEquals(listOf("a", "b"), m.scrapers.map { it.id })
        assertEquals(listOf("movie", "tv"), m.scrapers[0].supportedTypes)
        assertFalse(m.scrapers[1].enabledByDefault)
        assertEquals("https://raw.example/repo/providers/a.js", NuvioPlugins.codeUrl("https://raw.example/repo/manifest.json", "providers/a.js"))
    }

    @Test fun results() {
        val list = NuvioPlugins.parseResults(
            """[{"name":"P","title":"Film 1080p","url":"https://cdn/x.m3u8","quality":"1080p","size":"1.4 GB","headers":{"Referer":"https://r"}},
                {"name":"P","title":"Film 4K","url":"magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=x","seeders":12},
                {"title":"bad"}]""",
            "Plugin",
        )
        assertEquals(listOf(SourceKind.DIRECT, SourceKind.TORRENT), list.map { it.kind })
        assertEquals(1080, list[0].quality.resolution)
        assertEquals(mapOf("Referer" to "https://r"), list[0].headers)
        assertEquals((1.4 * (1L shl 30)).toLong(), list[0].sizeBytes)
        assertEquals(12, list[1].seeders)
        assertEquals(2160, list[1].quality.resolution)
    }
}

class PluginRuntimeTest {
    private val requests = mutableListOf<FetchRequest>()
    private val fake = HttpFetcher { r ->
        requests += r
        val body = when {
            r.url.startsWith("https://api.themoviedb.org/") -> """{"title":"Big Buck Bunny","release_date":"2008-05-30"}"""
            r.url.startsWith("https://site.example/search") -> """
                <html><body><ul id="results">
                  <li class="item" data-q="1080p"><a href="/watch/1">Big Buck Bunny (2008)</a></li>
                  <li class="item" data-q="720p"><a href="/watch/2">Big Buck Bunny HD</a></li>
                </ul></body></html>"""
            r.url.startsWith("https://site.example/api") -> """{"stream":"https://cdn.example/bbb.m3u8"}"""
            else -> "not found"
        }
        FetchResponse(if (body == "not found") 404 else 200, r.url, mapOf("Content-Type" to "text/html"), body.toByteArray())
    }
    private val runtime = PluginRuntime(fake, timeoutMs = 10_000)

    @Test fun realisticScraper(): Unit = runBlocking {
        val code = """
            const cheerio = require('cheerio-without-node-native');
            async function getStreams(tmdbId, mediaType, season, episode) {
              const meta = await fetch('https://api.themoviedb.org/3/movie/' + tmdbId + '?api_key=' + TMDB_API_KEY).then(r => r.json());
              const url = new URL('/search', 'https://site.example');
              url.searchParams.set('q', meta.title);
              const html = await (await fetch(url.toString())).text();
              const ${'$'} = cheerio.load(html);
              const out = [];
              ${'$'}('#results li.item').each((i, el) => {
                out.push({ name: 'Site', title: ${'$'}(el).find('a').text() + ' ' + ${'$'}(el).attr('data-q'), quality: ${'$'}(el).data('q'),
                           url: 'https://site.example' + ${'$'}(el).find('a').attr('href') });
              });
              await new Promise(r => setTimeout(r, 5));
              const api = await fetch('https://site.example/api', { method: 'POST', body: { id: tmdbId }, headers: { 'Content-Type': 'application/json' } });
              out.push({ name: 'Site', title: 'API ' + REEL.year, url: (await api.json()).stream, headers: { Referer: 'https://site.example/' } });
              console.log('found', out.length, btoa('hi'), CryptoJS.MD5('a').toString());
              return out;
            }
            module.exports = { getStreams };
        """.trimIndent()
        val logs = mutableListOf<String>()
        val sources = runtime.getStreams(code, "Site", MOVIE, log = { logs += it })
        assertEquals(3, sources.size)
        assertEquals(1080, sources[0].quality.resolution)
        assertEquals("https://site.example/watch/1", sources[0].url)
        assertEquals("https://cdn.example/bbb.m3u8", sources[2].url)
        assertEquals(mapOf("Referer" to "https://site.example/"), sources[2].headers)
        assertTrue(requests.first().url.contains("api_key=$TMDB_KEY_PLACEHOLDER"))
        assertEquals("https://site.example/search?q=Big%20Buck%20Bunny", requests[1].url)
        assertEquals("""{"id":"10378"}""", requests[2].body!!.decodeToString())
        assertTrue(logs.any { it.contains("found 3 aGk= 0cc175b9c0f1b6a831c399e269772661") }, logs.toString())
    }

    @Test fun pluginErrorsBecomeExceptions(): Unit = runBlocking {
        val e = assertFailsWith<PluginException> {
            runtime.getStreams("async function getStreams() { throw new Error('site down'); }", "X", MOVIE)
        }
        assertTrue(e.message!!.contains("site down"), e.message)
        assertFailsWith<PluginException> { runtime.getStreams("var nothing = 1;", "X", MOVIE) }
    }

    @Test fun hangingPluginTimesOut(): Unit = runBlocking {
        val slow = PluginRuntime(fake, timeoutMs = 300)
        assertFailsWith<PluginException> {
            slow.getStreams("async function getStreams() { await new Promise(r => setTimeout(r, 5000)); return []; }", "X", MOVIE)
        }
    }
}

class AggregatorTest {
    private fun provider(name: String, delayMs: Long, result: () -> List<Source>) = object : SourceProvider {
        override val id = name
        override val name = name
        override fun supports(req: SourceRequest) = true
        override suspend fun find(req: SourceRequest): List<Source> { delay(delayMs); return result() }
    }

    private fun bt(seeds: Int, res: Int) = Source(SourceKind.TORRENT, "t$res", "p", Quality(resolution = res), infoHash = "b".repeat(40), fileIdx = 0, seeders = seeds)

    @Test fun mergesDedupesAndReportsFailures(): Unit = runBlocking {
        val updates = SourceAggregator(timeoutMs = 500).search(
            EPISODE,
            listOf(
                provider("fast", 10) { listOf(bt(5, 1080)) },
                provider("dupe", 50) { listOf(bt(50, 1080)) },
                provider("broken", 20) { error("boom") },
                provider("slow", 2_000) { listOf(bt(1, 720)) },
            ),
        ).toList()
        val last = updates.last()
        assertTrue(last.done)
        assertEquals(1, last.sources.size) // same hash + file → one source, the healthier one kept
        assertEquals(50, last.sources.single().seeders)
        assertEquals(setOf("broken", "slow"), last.failed.keys)
        assertEquals("Timed out", last.failed["slow"])
        assertEquals(4, updates.first().pending.size)
    }
}
