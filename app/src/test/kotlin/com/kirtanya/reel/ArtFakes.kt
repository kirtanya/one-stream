package com.kirtanya.reel

import com.kirtanya.reel.core.artmatch.ArtLookup
import com.kirtanya.reel.core.artmatch.MatchCandidate
import com.kirtanya.reel.core.artmatch.TitleMatcher
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.WatchProviders

/** TMDB for the raw-file artwork matcher (artmatch/ArtMatcher) in screen renders: never the network. */
object ArtFakes {
    /** Every render by default: TMDB knows no file name, so raw files keep their letter tile. */
    val nothing = object : ArtLookup {
        override suspend fun search(type: MediaType, query: String, year: Int?): List<MatchCandidate> = emptyList()
        override suspend fun details(ref: TitleRef): TitleDetails = throw java.io.IOException("no network in renders")
        override suspend fun season(tvId: Int, season: Int): List<Episode> = emptyList()
    }

    /** Not TMDB's real id (133701): FakeCatalog gives ids ≡ 2 (mod 4) no logo, so the TV info block shows the name as text. */
    const val TEARS_ID = 133702

    /** The "matched" scenes: TMDB knows the seeded raw file, "Tears.of.Steel.2012.1080p…". */
    val tearsOfSteel = object : ArtLookup {
        override suspend fun search(type: MediaType, query: String, year: Int?): List<MatchCandidate> =
            if (type == MediaType.MOVIE && TitleMatcher.similarity(query, "Tears of Steel") > 0.8) {
                listOf(MatchCandidate(MediaType.MOVIE, TEARS_ID, "Tears of Steel", "Tears of Steel", 2012, 3.2, posterPath = "/poster_7.jpg", backdropPath = "/backdrop_7.jpg"))
            } else emptyList()

        override suspend fun details(ref: TitleRef): TitleDetails = TitleDetails(
            summary = TitleSummary(ref, "Tears of Steel", 2012, "/poster_7.jpg", "/backdrop_7.jpg", 6.0, "A group of warriors and scientists gather at the Oude Kerk in Amsterdam."),
            tagline = null, runtimeMin = 12, genres = listOf("Science Fiction"), certification = null, cast = emptyList(),
            seasons = emptyList(), imdbId = "tt2285752", providers = WatchProviders.NONE,
        )

        override suspend fun season(tvId: Int, season: Int): List<Episode> = emptyList()
    }
}
