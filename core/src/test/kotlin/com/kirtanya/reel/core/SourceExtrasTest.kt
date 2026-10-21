package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceBrowser
import com.kirtanya.reel.core.sources.SourceExtras
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRanker
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.tmdb.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceExtrasTest {
    private val dune = SourceRequest(438631, "tt1160419", MediaType.MOVIE, "Dune", 2021)
    private fun direct(title: String, res: Int? = 1080, url: String = "https://cdn.example/${title.hashCode()}.mp4", filename: String? = null) =
        Source(SourceKind.DIRECT, title, "Addon", Quality(resolution = res), url = url, filename = filename)
    private fun kind(title: String, req: SourceRequest = dune, filename: String? = null, url: String = "https://cdn.example/x.mp4") =
        SourceExtras.classify(direct(title, url = url, filename = filename), req)

    @Test fun trailersAndOtherExtrasAreRecognised(): Unit {
        assertEquals("Trailer", kind("Dune Official Trailer 4K"))
        assertEquals("Trailer", kind("Dune.2021.Trailer.2.1080p.mp4"))
        assertEquals("Teaser", kind("Dune (2021) - Teaser"))
        assertEquals("Behind the scenes", kind("Dune.Making.Of.720p"))
        assertEquals("Featurette", kind("Dune Featurette: The Sandworm"))
        assertEquals("Sample", kind("Dune 2021 2160p", filename = "dune.2021.2160p-sample.mkv"))
        assertEquals("TV spot", kind("Dune TV Spot #3"))
        assertEquals("Deleted scenes", kind("Dune Deleted Scenes"))
        assertEquals("Video", kind("Dune", url = "https://www.youtube.com/watch?v=n9xhJrPXop4"))
    }

    @Test fun theFilmItselfIsNot(): Unit {
        assertNull(kind("Dune.2021.2160p.UHD.BluRay.x265.HDR.DTS-HD.MA.5.1"))
        assertNull(kind("Dune 2021 1080p WEB-DL\n👤 120 💾 2.3 GB ⚙️ Torrentio"))
        assertNull(kind("Dune.2021.Extended.Cut.1080p")) // an extended cut is the film
        assertNull(kind("Dune.2021.1080p.Sampled.Audio")) // "sample" only as a whole word
    }

    @Test fun wordsInTheTitleDontCount(): Unit {
        val tpb = SourceRequest(1, null, MediaType.TV, "Trailer Park Boys", 2001, 1, 1)
        assertNull(kind("Trailer.Park.Boys.S01E01.1080p.WEB", tpb))
        assertEquals("Trailer", kind("Trailer Park Boys Season 12 Official Trailer", tpb))
        val extras = SourceRequest(2, null, MediaType.TV, "Extras", 2005, 1, 2)
        assertNull(kind("Extras.S01E02.720p.BluRay", extras))
    }

    @Test fun extrasRankLastAndAreNeverPicked(): Unit {
        val trailer = SourceExtras.mark(direct("Dune Official Trailer 2160p", res = 2160), dune)
        val film = SourceExtras.mark(direct("Dune.2021.720p.WEB", res = 720), dune)
        val ranked = SourceRanker.rank(listOf(trailer, film))
        assertEquals(listOf(film, trailer), ranked)
        assertEquals(film, SourceBrowser.recommended(ranked))
        assertNull(SourceBrowser.recommended(listOf(trailer))) // only a trailer → nothing to recommend
        assertEquals(film, pickNextSource(null, listOf(trailer, film)))
        assertEquals(listOf("Trailer"), SourceBrowser.view(trailer).tags.take(1))
    }
}
