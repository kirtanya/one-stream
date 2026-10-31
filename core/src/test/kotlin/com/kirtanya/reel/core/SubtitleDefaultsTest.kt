package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.PlayerSourceChange
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.sources.FetchResponse
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.StremioAddon
import com.kirtanya.reel.core.sources.SubtitleLanguagePref
import com.kirtanya.reel.core.sources.SubtitleTrack
import com.kirtanya.reel.core.sources.Subtitles
import com.kirtanya.reel.core.store.BuiltInAddons
import com.kirtanya.reel.core.tmdb.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Subtitles on by default in English, OpenSubtitles built in, the subtitle probe, Change source (2026-10-21). */
class SubtitleDefaultsTest {
    private val openSubs = StremioAddon.parseManifest(BuiltInAddons.OPENSUBTITLES_MANIFEST)
    private val movie = SourceRequest(10378, "tt1254207", MediaType.MOVIE, "Big Buck Bunny", 2008)

    @Test fun languageCodesNormalise() {
        for (c in listOf("eng", "en", "EN", "English", "english", " en ", "en-US", "en_GB", "English (UK)")) {
            assertEquals("en", Subtitles.normalizeLanguage(c), c)
        }
        assertEquals("pt", Subtitles.normalizeLanguage("pt-BR"))
        assertEquals("pt", Subtitles.normalizeLanguage("pob"))
        assertEquals("hi", Subtitles.normalizeLanguage("hin"))
        assertEquals("id", Subtitles.normalizeLanguage("ind"))
        assertEquals("und", Subtitles.normalizeLanguage(""))
        assertEquals("xx", Subtitles.normalizeLanguage("xx"))
    }

    @Test fun unsetMeansEnglishAndOffStaysOff() {
        assertEquals("en", SubtitleLanguagePref.decode(null))
        assertEquals("en", SubtitleLanguagePref.decode(""))
        assertNull(SubtitleLanguagePref.decode("off"))
        assertNull(SubtitleLanguagePref.decode("OFF"))
        assertEquals("fr", SubtitleLanguagePref.decode("fre"))
        assertEquals("en", SubtitleLanguagePref.decode("English"))
        assertEquals("off", SubtitleLanguagePref.encode(null))
        assertEquals("hi", SubtitleLanguagePref.encode("hin"))
        // Round trip: Off is never read back as "never chosen".
        assertNull(SubtitleLanguagePref.decode(SubtitleLanguagePref.encode(null)))
        assertEquals("ta", SubtitleLanguagePref.decode(SubtitleLanguagePref.encode("ta")))
    }

    private fun track(url: String, lang: String) = SubtitleTrack(url, lang, "OpenSubtitles v3", "srt")

    @Test fun everyTrackIsAttachedAndEnglishShowsByItself() {
        val fromSource = listOf(SubtitleTrack("https://cdn/a.vtt", "eng"), SubtitleTrack("https://cdn/b", "fre"))
        val fromAddons = listOf(track("https://subs/1", "id"), track("https://subs/2", "en"), track("https://cdn/a.vtt", "en"))
        val all = Subtitles.merge(fromSource, fromAddons)
        // All attached (one per URL), the source's own first, languages normalised, format from the URL.
        assertEquals(listOf("https://cdn/a.vtt", "https://cdn/b", "https://subs/1", "https://subs/2"), all.map { it.url })
        assertEquals(listOf("en", "fr", "id", "en"), all.map { it.language })
        assertEquals("vtt", all[0].format)
        assertEquals("https://cdn/a.vtt", Subtitles.autoSelect(all, "en")?.url)
        assertEquals("https://cdn/a.vtt", Subtitles.autoSelect(all, "eng")?.url)
        // No track in that language: none selected, but every track is still there to pick.
        assertNull(Subtitles.autoSelect(all, "ta"))
        // Off: nothing shows by itself.
        assertNull(Subtitles.autoSelect(all, null))
        assertNull(Subtitles.autoSelect(emptyList(), "en"))
    }

    @Test fun waitOnlyWhenALanguageShouldShow() {
        assertEquals(400L, Subtitles.startWaitMs(languageWanted = true))
        assertEquals(0L, Subtitles.startWaitMs(languageWanted = false))
    }

    @Test fun openSubtitlesIsAddedOnceAndARemovalStays() {
        // New install / existing install without it: added, step marked.
        val first = BuiltInAddons.migrate(emptyList(), emptySet())
        assertEquals(listOf(BuiltInAddons.OPENSUBTITLES_URL), first.add.map { it.url })
        assertEquals(setOf(BuiltInAddons.OPENSUBTITLES_STEP), first.markDone)
        // Already installed (any spelling): nothing added, step still marked.
        val had = BuiltInAddons.migrate(listOf("stremio://opensubtitles-v3.strem.io/manifest.json"), emptySet())
        assertTrue(had.add.isEmpty())
        assertEquals(setOf(BuiltInAddons.OPENSUBTITLES_STEP), had.markDone)
        // The user removed it after the step ran: never re-added.
        val removed = BuiltInAddons.migrate(listOf("https://torrentio.example/manifest.json"), setOf(BuiltInAddons.OPENSUBTITLES_STEP))
        assertTrue(removed.add.isEmpty() && removed.markDone.isEmpty())
        // The shipped manifest is a valid subtitles-only addon for IMDb ids.
        assertEquals("OpenSubtitles v3", openSubs.name)
        assertTrue(openSubs.hasSubtitles && !openSubs.hasStreams)
        assertEquals("tt1254207", openSubs.streamIdFor(movie, resource = "subtitles"))
    }

    @Test fun safeUrlHidesAddonConfig() {
        assertEquals(
            "https://opensubtitles-v3.strem.io/subtitles/movie/tt1254207.json",
            Subtitles.safeUrl("https://opensubtitles-v3.strem.io/subtitles/movie/tt1254207.json"),
        )
        assertEquals(
            "https://addon.example/…/subtitles/series/tt1:1:2.json",
            Subtitles.safeUrl("https://addon.example/eyJrZXkiOiJzZWNyZXQifQ/subtitles/series/tt1:1:2.json"),
        )
        assertEquals("https://addon.example/…/subtitles/movie/tt1.json?…", Subtitles.safeUrl("https://addon.example/k/subtitles/movie/tt1.json?token=abc"))
        assertEquals(BuiltInAddons.OPENSUBTITLES_URL, Subtitles.safeUrl(BuiltInAddons.OPENSUBTITLES_URL))
        assertEquals("https://addon.example/…/manifest.json", Subtitles.safeUrl("https://addon.example/apikey=123/manifest.json"))
    }

    /** The probe logic (SourcesProbe `subsTest`) against fake addon answers. */
    @Test fun probeReportsEachAddon(): Unit = runBlocking {
        val ok = """{"subtitles":[{"id":"1","url":"https://subs5.strem.io/f/1","lang":"eng","subtitleFileName":"bbb.eng.srt"},""" +
            """{"id":"2","url":"https://subs5.strem.io/f/2","lang":"ind","subtitleFileName":"bbb.srt"},{"id":"3","url":"https://subs5.strem.io/f/3","lang":"eng"}]}"""
        val broken = StremioAddon.parseManifest("""{"id":"x.broken","name":"Broken subs","types":["movie"],"resources":["subtitles"],"idPrefixes":["tt"]}""")
        val failing = StremioAddon.parseManifest("""{"id":"x.fail","name":"Offline subs","types":["movie"],"resources":["subtitles"]}""")
        val slow = StremioAddon.parseManifest("""{"id":"x.slow","name":"Slow subs","types":["movie"],"resources":["subtitles"]}""")
        val seriesOnly = StremioAddon.parseManifest("""{"id":"x.tv","name":"Series subs","types":["series"],"resources":["subtitles"]}""")
        val asked = mutableListOf<String>()
        val http = HttpFetcher { r ->
            synchronized(asked) { asked += r.url }
            when {
                r.url.startsWith("https://opensubtitles-v3.strem.io/") -> FetchResponse(200, r.url, emptyMap(), ok.toByteArray())
                r.url.startsWith("https://broken.example/") -> FetchResponse(503, r.url, emptyMap(), ByteArray(0))
                r.url.startsWith("https://slow.example/") -> { delay(5_000); FetchResponse(200, r.url, emptyMap(), ok.toByteArray()) }
                else -> throw java.io.IOException("unreachable")
            }
        }
        val search = Subtitles.searchDetailed(
            movie,
            listOf(
                BuiltInAddons.OPENSUBTITLES_URL to openSubs,
                "https://broken.example/secret-key/manifest.json" to broken,
                "https://offline.example/manifest.json" to failing,
                "https://slow.example/manifest.json" to slow,
                "https://tv.example/manifest.json" to seriesOnly,
            ),
            http, timeoutMs = 300,
        )
        val (os, br, off, sl, tv) = search.answers
        assertEquals(200, os.status)
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt1254207.json", os.requestUrl)
        assertEquals(mapOf("en" to 2, "id" to 1), os.perLanguage)
        assertEquals(503, br.status)
        assertEquals("https://broken.example/…/subtitles/movie/tt1254207.json", br.requestUrl)
        assertEquals("answered 503", br.error)
        assertTrue(off.error!!.contains("unreachable"))
        assertEquals("no answer in 300 ms", sl.error)
        assertNull(tv.requestUrl)
        assertEquals("doesn't cover this movie", tv.error)
        // Indonesian was answered but isn't kept: English only.
        assertEquals(listOf("en", "en"), search.tracks.map { it.language })
        val lines = Subtitles.describe(search)
        assertEquals(6, lines.size)
        assertEquals("addon \"OpenSubtitles v3\" url=https://opensubtitles-v3.strem.io/subtitles/movie/tt1254207.json status=200 tracks=3 langs=en:2,id:1", lines[0])
        assertTrue(lines.none { it.contains("secret-key") })
        assertEquals("total addons=5 tracks=2 langs=en:2", lines.last())
        // A title without an IMDb id: OpenSubtitles (tt ids only) can't be asked, and says why.
        val noImdb = Subtitles.searchDetailed(movie.copy(imdbId = null), listOf(BuiltInAddons.OPENSUBTITLES_URL to openSubs), http)
        assertEquals("needs an IMDb id this title doesn't have", noImdb.answers.single().error)
        assertTrue(noImdb.tracks.isEmpty())
    }

    @Test fun onlyEnglishSubtitlesAreListed() {
        val t = { lang: String -> com.kirtanya.reel.core.sources.SubtitleTrack(url = "https://s/$lang", language = lang, name = "x") }
        assertEquals(listOf("eng", "en", "en-US"), Subtitles.onlyWanted(listOf(t("eng"), t("fre"), t("en"), t("hi"), t("en-US"))).map { it.language })
        // Inside the file: English ones; unlabelled only when there's no English.
        assertEquals(listOf(1, 3), Subtitles.embeddedToList(listOf("fre", "eng", null, "en")))
        assertEquals(listOf(1, 2), Subtitles.embeddedToList(listOf("fre", null, "und", "hin")))
        assertEquals(emptyList(), Subtitles.embeddedToList(listOf("fre", "hin")))
    }

    @Test fun changeSourceCarriesOnWhereYouWere() {
        val src = Source(SourceKind.TORRENT, "Film 1080p", "Torrent", Quality(1080))
        val film = Playback("movie:10378", "movie:10378", "Big Buck Bunny", tmdbType = "movie", tmdbId = 10378, source = src)
        val file = Playback.forTorrentFile("a".repeat(40), 0, "bbb.mkv")
        assertTrue(PlayerSourceChange.available(film))
        assertTrue(PlayerSourceChange.available(film.copy(key = "tv:1:s1e2", episode = EpisodeNumber(1, 2), tmdbType = "tv")))
        assertFalse(PlayerSourceChange.available(file))
        val entry = WatchEntry(film, 600_000, 5_000_000, 0)
        // Handed over: exact, even early on; otherwise the usual resume (5 s back).
        assertEquals(612_345, ContinueWatching.startPositionMs(612_345, entry))
        assertEquals(12_000, ContinueWatching.startPositionMs(12_000, null))
        assertEquals(595_000, ContinueWatching.startPositionMs(null, entry))
        assertEquals(595_000, ContinueWatching.startPositionMs(-1, entry))
    }

    @Test fun lateTracksInTheWantedLanguageAreRecognised() {
        fun t(lang: String) = com.kirtanya.reel.core.sources.SubtitleTrack(url = "https://x/$lang.srt", language = lang)
        val tracks = listOf(t("pt"), t("eng"), t(""))
        kotlin.test.assertTrue(com.kirtanya.reel.core.sources.Subtitles.containsLanguage(tracks, "en"))
        kotlin.test.assertTrue(com.kirtanya.reel.core.sources.Subtitles.containsLanguage(tracks, "English"))
        kotlin.test.assertFalse(com.kirtanya.reel.core.sources.Subtitles.containsLanguage(tracks, "fr"))
        kotlin.test.assertFalse(com.kirtanya.reel.core.sources.Subtitles.containsLanguage(tracks, "off"))
        kotlin.test.assertFalse(com.kirtanya.reel.core.sources.Subtitles.containsLanguage(tracks, null))
    }
}
