package com.kirtanya.reel.core

import com.kirtanya.reel.core.artmatch.ArtLookup
import com.kirtanya.reel.core.artmatch.ArtMatch
import com.kirtanya.reel.core.artmatch.ArtMatchCache
import com.kirtanya.reel.core.artmatch.ArtResolver
import com.kirtanya.reel.core.artmatch.MatchCandidate
import com.kirtanya.reel.core.artmatch.ReleaseName
import com.kirtanya.reel.core.artmatch.ReleaseNames
import com.kirtanya.reel.core.artmatch.TitleMatcher
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.SeasonSummary
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.WatchProviders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtMatchTest {
    private fun parse(s: String) = ReleaseNames.parse(s, thisYear = 2026)

    // ─── Release names ─────────────────────────────────────────────────────────────────────

    private fun assertName(raw: String, title: String, year: Int? = null, season: Int? = null, episode: Int? = null, country: String? = null) {
        assertEquals(ReleaseName(title, year, season, episode, country), parse(raw), raw)
    }

    @Test fun films() {
        assertName("Tears.of.Steel.2012.1080p.BluRay.x264-Blender", "Tears of Steel", 2012)
        assertName("Tears.of.Steel.2012.1080p.BluRay.x264.Blender.Open.Movie", "Tears of Steel", 2012)
        assertName("Avengers Infinity War (2018) [BluRay] [1080p] [YTS.AM]", "Avengers Infinity War", 2018)
        assertName("Avengers.Infinity.War.(2018).[BluRay].1080p.x264", "Avengers Infinity War", 2018)
        assertName("Sintel.2010.720p.mkv", "Sintel", 2010)
        assertName("deadpool.2016.1080p.bluray.x264-sparks", "deadpool", 2016)
        assertName("The.Matrix.1999.REMASTERED.1080p.BluRay", "The Matrix", 1999)
        assertName("Oppenheimer 2023 1080p WEB-DL DDP5.1 Atmos H.264-FLUX", "Oppenheimer", 2023)
        assertName("The.Lord.of.the.Rings.The.Fellowship.of.the.Ring.2001.EXTENDED.1080p", "The Lord of the Rings The Fellowship of the Ring", 2001)
        assertName("Spider-Man.No.Way.Home.2021.2160p.UHD.BluRay.x265.10bit.HDR", "Spider-Man No Way Home", 2021)
        assertName("Avatar.The.Way.of.Water.2022.HDR.2160p.WEB.H265", "Avatar The Way of Water", 2022)
        assertName("Interstellar", "Interstellar")
        assertName("big_buck_bunny_1080p_h264.mov", "big buck bunny")
        assertName("Charlotte's Web 2006 720p BRRip", "Charlotte's Web", 2006)
    }

    @Test fun yearsInsideTitles() {
        assertName("Blade.Runner.2049.2017.2160p.UHD.BluRay.x265", "Blade Runner 2049", 2017)
        assertName("Blade.Runner.2049.1080p.BluRay", "Blade Runner 2049") // 2049 is no year yet
        assertName("2001.A.Space.Odyssey.1968.1080p", "2001 A Space Odyssey", 1968)
        assertName("1917.2019.1080p.WEBRip.x264", "1917", 2019)
        assertName("2012.2009.720p.BluRay", "2012", 2009)
    }

    @Test fun tagsPathsAndLinks() {
        assertName("[YTS.MX] Dune Part Two (2024) [2160p] [4K] [WEB] [5.1]", "Dune Part Two", 2024)
        assertName("www.TamilBlasters.xyz - Jawan (2023) Hindi HQ HDRip - 1080p", "Jawan", 2023)
        assertName("[ www.Torrenting.com ] - Inception.2010.1080p.BluRay", "Inception", 2010)
        assertName("/storage/emulated/0/Download/Movies/Inception.2010.1080p.BluRay.x264.mkv", "Inception", 2010)
        assertName("C:\\Users\\me\\Videos\\Sintel.2010.mp4", "Sintel", 2010)
        assertName("Movie%20Night%20Story%202019.mp4", "Movie Night Story", 2019)
        assertName("Time.To.Die.2020.1080p", "Time To Die", 2020) // ".To" isn't a site
    }

    @Test fun episodes() {
        assertName("Game.of.Thrones.S01E01.Winter.Is.Coming.1080p.BluRay.x264", "Game of Thrones", season = 1, episode = 1)
        assertName("The.Office.US.S02E03.720p.WEB-DL", "The Office", season = 2, episode = 3, country = "US")
        assertName("Breaking Bad 1x02 Cat's in the Bag.mkv", "Breaking Bad", season = 1, episode = 2)
        assertName("Friends Season 1 Episode 2.mp4", "Friends", season = 1, episode = 2)
        assertName("Doctor.Who.2005.S01E01.Rose.720p", "Doctor Who", 2005, 1, 1)
        assertName("Mr.Robot.S01E01.eps1.0_hellofriend.mov.1080p", "Mr Robot", season = 1, episode = 1)
        assertName("S.W.A.T.2017.S01E01.720p.HDTV", "SWAT", 2017, 1, 1)
        assertName("Loki.S02E01.1080p.WEB.H264-SuccessfulCrab", "Loki", season = 2, episode = 1)
        assertName("Severance S01 E02 1080p", "Severance", season = 1, episode = 2)
        assertName("Show.Name.S01E01E02.720p", "Show Name", season = 1, episode = 1)
        assertName("Doctor Who (2005) - S03E10 - Blink", "Doctor Who", 2005, 3, 10)
        assertName("The Office (US) S05E14 Stress Relief", "The Office", season = 5, episode = 14, country = "US")
        assertName("Office, The S01E01", "The Office", season = 1, episode = 1)
    }

    @Test fun seasonPacks() {
        assertName("Stranger.Things.S04.COMPLETE.1080p.NF.WEB-DL", "Stranger Things", season = 4)
        assertName("Breaking Bad Season 2 Complete 720p", "Breaking Bad", season = 2)
    }

    @Test fun nothingToSearch() {
        assertNull(parse("a1b2c3d4"))
        assertNull(parse("0123456789abcdef0123456789abcdef01234567"))
        assertNull(parse(""))
        assertNull(ReleaseNames.parse(null))
        assertNull(parse("[YTS.MX]"))
    }

    @Test fun resolutionIsNoEpisode() {
        assertNull(parse("Some.Film.2015.1920x1080.mkv")!!.episode)
    }

    @Test fun lookupKeyIgnoresEpisodeAndPunctuation() {
        assertEquals(parse("Game.of.Thrones.S01E01")!!.lookupKey, parse("Game of Thrones - S03E09")!!.lookupKey)
        assertTrue(parse("The.Office.US.S02E03")!!.lookupKey != parse("The.Office.S02E03")!!.lookupKey)
    }

    // ─── Matcher ───────────────────────────────────────────────────────────────────────────

    private fun movie(id: Int, title: String, year: Int?, pop: Double = 10.0, original: String? = null) =
        MatchCandidate(MediaType.MOVIE, id, title, original, year, pop, posterPath = "/p$id.jpg", backdropPath = "/b$id.jpg")

    private fun show(id: Int, title: String, year: Int?, pop: Double = 10.0, countries: List<String> = emptyList()) =
        MatchCandidate(MediaType.TV, id, title, null, year, pop, countries, "/p$id.jpg", "/b$id.jpg")

    @Test fun normalizeAndSimilarity() {
        assertEquals("avengers infinity war", TitleMatcher.normalize("Avengers: Infinity War"))
        assertEquals("amelie", TitleMatcher.normalize("Amélie"))
        assertEquals("fast and furious", TitleMatcher.normalize("Fast & Furious"))
        assertEquals(1.0, TitleMatcher.similarity("The Office", "Office"))
        assertEquals(1.0, TitleMatcher.similarity("Spider-Man No Way Home", "Spider-Man: No Way Home"))
        assertTrue(TitleMatcher.similarity("Spiderman", "Spider-Man") > 0.85)
        assertTrue(TitleMatcher.similarity("Avengers Infinity War", "Avengers: Endgame") < 0.6)
    }

    @Test fun exactTitleAndYearWins() {
        val out = TitleMatcher.choose(parse("Tears.of.Steel.2012.1080p")!!, listOf(movie(133701, "Tears of Steel", 2012, pop = 2.5), movie(9, "Steel", 1997, pop = 8.0)))
        assertEquals(133701, out.candidate?.id)
        assertTrue(out.score!! > 0.9, "score ${out.score}")
    }

    @Test fun yearDecidesBetweenRemakes() {
        val cands = listOf(movie(8587, "The Lion King", 1994, pop = 80.0), movie(420818, "The Lion King", 2019, pop = 60.0))
        assertEquals(420818, TitleMatcher.choose(parse("The.Lion.King.2019.1080p")!!, cands).candidate?.id)
        assertEquals(8587, TitleMatcher.choose(parse("The Lion King (1994) [BluRay]")!!, cands).candidate?.id)
        // No year and two popular films of that name: no guess.
        val none = TitleMatcher.choose(parse("The.Lion.King.1080p.BluRay")!!, cands)
        assertNull(none.candidate)
        assertEquals("ambiguous", none.reason)
    }

    @Test fun yearOffByOneStillMatchesTwoDoesNot() {
        assertEquals(1, TitleMatcher.choose(parse("Some.Film.2013.1080p")!!, listOf(movie(1, "Some Film", 2012))).candidate?.id)
        assertNull(TitleMatcher.choose(parse("Some.Film.2014.1080p")!!, listOf(movie(1, "Some Film", 2012))).candidate)
    }

    @Test fun differentTitleIsNoMatch() {
        val out = TitleMatcher.choose(parse("Avengers.Infinity.War.2018")!!, listOf(movie(299534, "Avengers: Endgame", 2019, pop = 100.0)))
        assertNull(out.candidate)
        assertEquals("noSimilarTitle", out.reason)
        assertEquals("noResults", TitleMatcher.choose(parse("Avengers.Infinity.War.2018")!!, emptyList()).reason)
    }

    @Test fun obscureTitleWithoutYearIsNoMatch() {
        // Exact name, but nothing else backs it up (no year, nobody watches it): too thin.
        val out = TitleMatcher.choose(parse("Quiet.Harbour.1080p")!!, listOf(movie(5, "Quiet Harbour", 2003, pop = 0.6)))
        assertNull(out.candidate)
        assertEquals("lowScore", out.reason)
    }

    @Test fun popularExactTitleWithoutYearMatches() {
        val cands = listOf(movie(157336, "Interstellar", 2014, pop = 150.0), movie(301, "Interstellar", 2012, pop = 0.8))
        assertEquals(157336, TitleMatcher.choose(parse("Interstellar.1080p.BluRay")!!, cands).candidate?.id)
    }

    @Test fun subtitleInTmdbTitleNeedsTheYear() {
        val cand = movie(575264, "Mission: Impossible - Dead Reckoning Part One", 2023, pop = 60.0)
        assertEquals(575264, TitleMatcher.choose(parse("Mission.Impossible.Dead.Reckoning.2023.1080p")!!, listOf(cand)).candidate?.id)
        assertNull(TitleMatcher.choose(parse("Mission.Impossible.Dead.Reckoning.1080p")!!, listOf(cand)).candidate)
    }

    @Test fun originalTitleCounts() {
        val cand = movie(194, "Amélie", 2001, pop = 30.0, original = "Le Fabuleux Destin d'Amélie Poulain")
        assertEquals(194, TitleMatcher.choose(parse("Le.Fabuleux.Destin.d.Amelie.Poulain.2001.720p")!!, listOf(cand)).candidate?.id)
    }

    @Test fun countryTellsShowsApart() {
        val cands = listOf(show(2316, "The Office", 2005, pop = 150.0, countries = listOf("US")), show(2996, "The Office", 2001, pop = 40.0, countries = listOf("GB")))
        assertEquals(2316, TitleMatcher.choose(parse("The.Office.US.S02E03.720p")!!, cands).candidate?.id)
        assertEquals(2996, TitleMatcher.choose(parse("The.Office.UK.S01E02.720p")!!, cands).candidate?.id)
        assertNull(TitleMatcher.choose(parse("The.Office.S02E03.720p")!!, cands).candidate)
    }

    @Test fun showYearRulesOutTheOldSeries() {
        val cands = listOf(show(57243, "Doctor Who", 2005, pop = 90.0), show(121, "Doctor Who", 1963, pop = 70.0))
        assertEquals(57243, TitleMatcher.choose(parse("Doctor.Who.2005.S01E01.720p")!!, cands).candidate?.id)
    }

    @Test fun parsesTmdbSearch() {
        val body = """{"page":1,"results":[
            {"id":1399,"name":"Game of Thrones","original_name":"Game of Thrones","first_air_date":"2011-04-17","popularity":250.5,"origin_country":["US"],"poster_path":"/p.jpg","backdrop_path":"/b.jpg"},
            {"id":7,"name":"","first_air_date":""},
            {"name":"no id"}
        ]}"""
        val c = TitleMatcher.parseSearch(body, MediaType.TV)
        assertEquals(1, c.size)
        assertEquals(MatchCandidate(MediaType.TV, 1399, "Game of Thrones", "Game of Thrones", 2011, 250.5, listOf("US"), "/p.jpg", "/b.jpg"), c[0])
        val m = TitleMatcher.parseSearch("""{"results":[{"id":133701,"title":"Tears of Steel","release_date":"2012-09-26","popularity":3.1,"poster_path":null}]}""", MediaType.MOVIE)
        assertEquals(2012, m[0].year)
        assertNull(m[0].posterPath)
        assertTrue(TitleMatcher.parseSearch("not json", MediaType.MOVIE).isEmpty())
    }

    // ─── Applying a match ──────────────────────────────────────────────────────────────────

    private val file = Playback.forTorrentFile("%040x".format(77), 0, "Tears.of.Steel.2012.1080p.BluRay.x264")
    private val gotFile = Playback.forTorrentFile("%040x".format(78), 2, "Game.of.Thrones.S01E02.1080p.BluRay")

    private fun details(ref: TitleRef, title: String, year: Int, seasons: List<SeasonSummary> = emptyList(), imdb: String? = "tt2285752") = TitleDetails(
        summary = TitleSummary(ref, title, year, "/poster.jpg", "/backdrop.jpg", 7.0, ""),
        tagline = null, runtimeMin = 12, genres = emptyList(), certification = null, cast = emptyList(),
        seasons = seasons, imdbId = imdb, providers = WatchProviders.NONE,
    )

    @Test fun needsMatchOnlyForBareEntries() {
        assertTrue(ArtMatch.needsMatch(file))
        assertFalse(ArtMatch.needsMatch(file.copy(tmdbId = 1, tmdbType = "movie")))
        assertFalse(ArtMatch.needsMatch(file.copy(backdropPath = "/b.jpg")))
    }

    @Test fun seasonPackFileNameAddsTheEpisode() {
        val pack = Playback.forTorrentFile("%040x".format(5), 3, "Game.of.Thrones.S01.1080p.BluRay", filename = "Game.of.Thrones.S01E04.1080p.mkv")
        assertEquals(ReleaseName("Game of Thrones", season = 1, episode = 4), ArtMatch.releaseName(pack))
        val hashOnly = Playback.forTorrentFile("%040x".format(5), 3, "0000abcd", filename = "Sintel.2010.mkv")
        assertEquals(ReleaseName("Sintel", 2010), ArtMatch.releaseName(hashOnly))
    }

    @Test fun keepsKeysAndSource() = runTest {
        val r = resolver().resolve(file)
        val p = assertNotNull(r.playback)
        assertEquals(file.key, p.key)
        assertEquals(file.titleKey, p.titleKey)
        assertEquals(file.source, p.source)
        assertEquals("Tears of Steel", p.title)
        assertEquals("movie", p.tmdbType)
        assertEquals(133701, p.tmdbId)
        assertEquals("tt2285752", p.imdbId)
        assertEquals("/backdrop.jpg", p.backdropPath)
        assertNull(p.episode)
    }

    @Test fun episodeGetsStillNameAndNext() = runTest {
        val p = assertNotNull(resolver().resolve(gotFile).playback)
        assertEquals("Game of Thrones", p.title)
        assertEquals(EpisodeNumber(1, 2), p.episode)
        assertEquals("S1 E2 · The Kingsroad", p.subtitle)
        assertEquals("/still_2.jpg", p.stillPath)
        assertEquals(EpisodeNumber(1, 3), p.next)
        assertEquals("tv", p.tmdbType)
        assertEquals(gotFile.key, p.key)
    }

    @Test fun seasonPackWithoutEpisodeGetsArtworkOnly() = runTest {
        val pack = Playback.forTorrentFile("%040x".format(6), null, "Game.of.Thrones.S01.1080p.BluRay")
        val p = assertNotNull(resolver().resolve(pack).playback)
        assertEquals("/backdrop.jpg", p.backdropPath)
        assertEquals("Game of Thrones", p.title)
        assertNull(p.tmdbId)
        assertFalse(ArtMatch.needsMatch(p))
    }

    @Test fun noMatchIsCachedAndNotAskedAgain() = runTest {
        val lookup = FakeLookup()
        val cache = ArtMatchCache()
        var now = 1_000L
        val r = ArtResolver(lookup, cache) { now }
        val odd = Playback.forTorrentFile("%040x".format(9), 0, "Unknown.Home.Video.2019.1080p")
        assertNull(r.resolve(odd).playback)
        val searches = lookup.searches
        assertEquals(2, searches) // plain, then with the year
        assertEquals("none", r.resolve(odd).outcome)
        assertEquals(searches, lookup.searches)
        // A week later it's asked again.
        now += ArtMatchCache.TTL_MS
        r.resolve(odd)
        assertTrue(lookup.searches > searches)
    }

    @Test fun matchIsCachedWithImdbSoAFilmNeedsNoCalls() = runTest {
        val lookup = FakeLookup()
        val r = ArtResolver(lookup, ArtMatchCache()) { 1L }
        r.resolve(file)
        val calls = lookup.calls
        val again = r.resolve(Playback.forTorrentFile("%040x".format(99), 1, "Tears of Steel (2012) [720p]"))
        assertEquals(133701, again.playback?.tmdbId)
        assertTrue(again.cached)
        assertEquals(calls, lookup.calls)
        assertEquals(0, again.calls)
    }

    @Test fun networkFailureThrowsAndCachesNothing() = runTest {
        val cache = ArtMatchCache()
        val r = ArtResolver(FakeLookup(fail = true), cache) { 1L }
        assertFailsWith<java.io.IOException> { r.resolve(file) }
        assertEquals(0, cache.size())
    }

    @Test fun detailsFailureStillGivesArtwork() = runTest {
        val p = assertNotNull(ArtResolver(FakeLookup(failDetails = true), ArtMatchCache()) { 1L }.resolve(file).playback)
        assertEquals("/b133701.jpg", p.backdropPath)
        assertNull(p.imdbId)
    }

    @Test fun keepMatchSurvivesASaveOfTheBareFile() {
        val matched = file.copy(title = "Tears of Steel", tmdbType = "movie", tmdbId = 133701, backdropPath = "/b.jpg", imdbId = "tt2285752")
        val again = Playback.forTorrentFile("%040x".format(77), 0, "Tears.of.Steel.2012.1080p.BluRay.x264", magnet = "magnet:?xt=urn:btih:x")
        val kept = ArtMatch.keepMatch(again, matched)
        assertEquals(133701, kept.tmdbId)
        assertEquals("/b.jpg", kept.backdropPath)
        assertEquals(again.source, kept.source)
        // Another key, or an entry that already has its own title: untouched.
        assertEquals(gotFile, ArtMatch.keepMatch(gotFile, matched))
        val film = matched.copy(tmdbId = 1)
        assertEquals(film, ArtMatch.keepMatch(film, matched))
        assertEquals(again, ArtMatch.keepMatch(again, null))
    }

    @Test fun cacheRoundTripsAndDropsStale() {
        val cache = ArtMatchCache()
        cache.put("movie|a||", ArtMatchCache.Entry(ArtMatch.Matched(MediaType.MOVIE, 1, "A", 2000), 0.9, at = 100))
        cache.put("movie|b||", ArtMatchCache.Entry(null, 0.5, at = 100 + ArtMatchCache.TTL_MS))
        val back = ArtMatchCache.decode(cache.encode(now = 100 + ArtMatchCache.TTL_MS))
        assertNull(back.get("movie|a||", 100 + ArtMatchCache.TTL_MS))
        assertNotNull(back.get("movie|b||", 100 + ArtMatchCache.TTL_MS))
        assertEquals(0, ArtMatchCache.decode("garbage").size())
    }

    @Test fun matchedFileGetsNoWatchedTickUnderItsFileKey() {
        val matched = file.copy(tmdbType = "movie", tmdbId = 133701, backdropPath = "/b.jpg")
        val done = com.kirtanya.reel.core.playback.WatchEntry(matched, 12 * 60_000L, 12 * 60_000L, 5)
        assertTrue(com.kirtanya.reel.core.library.Watched.fromHistory(emptyMap(), listOf(done)).isEmpty())
    }

    private fun resolver() = ArtResolver(FakeLookup(), ArtMatchCache()) { 1L }

    private inner class FakeLookup(val fail: Boolean = false, val failDetails: Boolean = false) : ArtLookup {
        var searches = 0
        var calls = 0
        override suspend fun search(type: MediaType, query: String, year: Int?): List<MatchCandidate> {
            searches++; calls++
            if (fail) throw java.io.IOException("offline")
            return when (type) {
                MediaType.MOVIE -> listOf(movie(133701, "Tears of Steel", 2012, pop = 3.0), movie(10, "Man of Steel", 2013, pop = 40.0))
                MediaType.TV -> listOf(show(1399, "Game of Thrones", 2011, pop = 250.0))
            }.filter { TitleMatcher.similarity(it.title, query) > 0.5 }
        }

        override suspend fun details(ref: TitleRef): TitleDetails {
            calls++
            if (failDetails) throw java.io.IOException("offline")
            return if (ref.type == MediaType.TV) details(ref, "Game of Thrones", 2011, listOf(SeasonSummary(1, "Season 1", 10, null, 2011)), "tt0944947")
            else details(ref, "Tears of Steel", 2012)
        }

        override suspend fun season(tvId: Int, season: Int): List<Episode> {
            calls++
            return listOf("Winter Is Coming", "The Kingsroad", "Lord Snow").mapIndexed { i, n -> Episode(season, i + 1, n, "", "/still_${i + 1}.jpg", null, 55) }
        }
    }
}
