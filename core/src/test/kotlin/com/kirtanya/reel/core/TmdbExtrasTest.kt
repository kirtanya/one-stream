package com.kirtanya.reel.core

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.TmdbPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbExtrasTest {
    private val details = """
    {"id":550,"title":"Fight Club","release_date":"1999-10-15","genres":[{"id":18,"name":"Drama"}],
     "images":{
       "logos":[
         {"file_path":"/fr.png","iso_639_1":"fr","vote_average":9},
         {"file_path":"/plain.png","iso_639_1":null,"vote_average":8},
         {"file_path":"/en.svg","iso_639_1":"en","vote_average":9},
         {"file_path":"/en.png","iso_639_1":"en","vote_average":5}],
       "backdrops":[
         {"file_path":"/text.jpg","iso_639_1":"en","vote_average":9},
         {"file_path":"/b1.jpg","iso_639_1":null,"vote_average":5},
         {"file_path":"/b2.jpg","iso_639_1":null,"vote_average":7}]},
     "videos":{"results":[
       {"key":"clip","name":"Clip","site":"YouTube","type":"Clip","official":true},
       {"key":"vimeo","name":"Trailer","site":"Vimeo","type":"Trailer","official":true},
       {"key":"fan","name":"Fan trailer","site":"YouTube","type":"Trailer","official":false,"published_at":"2020"},
       {"key":"off","name":"Official trailer","site":"YouTube","type":"Trailer","official":true,"published_at":"2019"}]},
     "recommendations":{"results":[
       {"id":680,"media_type":"movie","title":"Pulp Fiction","poster_path":"/p.jpg"},
       {"id":681,"media_type":"movie","title":"No poster","poster_path":null}]},
     "similar":{"results":[{"id":1,"title":"Unused","poster_path":"/s.jpg"}]}
    }"""

    @Test fun extrasFromAppendedDetails(): Unit {
        val d = TmdbParser.parseDetails(details, MediaType.MOVIE)
        assertEquals("/en.png", d.logoPath) // English, PNG before SVG
        assertEquals(listOf("off", "fan", "clip"), d.videos.map { it.key }) // YouTube only, official trailer first
        assertEquals("off", d.trailer?.key)
        assertEquals(listOf(TitleRef(MediaType.MOVIE, 680)), d.recommendations.map { it.ref }) // no-poster items dropped
        assertEquals(18, d.genreList.single().id)
        assertEquals(listOf("/b2.jpg", "/b1.jpg"), d.backdrops) // textless, best first
    }

    @Test fun similarFillsInForMissingRecommendations(): Unit {
        val d = TmdbParser.parseDetails("""{"id":2,"name":"Show","recommendations":{"results":[]},"similar":{"results":[{"id":7,"name":"Other","poster_path":"/o.jpg"}]}}""", MediaType.TV)
        assertEquals(listOf(TitleRef(MediaType.TV, 7)), d.recommendations.map { it.ref })
        assertNull(d.logoPath)
    }

    @Test fun textlessLogoWhenNoEnglishOne(): Unit {
        assertEquals("/x.png", TmdbParser.parseLogo("""{"logos":[{"file_path":"/de.png","iso_639_1":"de"},{"file_path":"/x.png","iso_639_1":null}]}"""))
        assertNull(TmdbParser.parseLogo("""{"logos":[]}"""))
    }

    @Test fun genresAndPerson(): Unit {
        assertEquals(listOf(28, 35), TmdbParser.parseGenres("""{"genres":[{"id":28,"name":"Action"},{"id":35,"name":"Comedy"}]}""").map { it.id })
        val p = TmdbParser.parsePerson("""{"id":287,"name":"Brad Pitt","biography":"Actor.","known_for_department":"Acting",
          "combined_credits":{"cast":[
            {"id":550,"media_type":"movie","title":"Fight Club","poster_path":"/f.jpg","popularity":10},
            {"id":550,"media_type":"movie","title":"Fight Club","poster_path":"/f.jpg","popularity":9},
            {"id":1,"media_type":"tv","name":"Friends","poster_path":"/t.jpg","popularity":50},
            {"id":2,"media_type":"movie","title":"No art","popularity":99}]}}""")
        assertEquals("Brad Pitt", p.name)
        assertEquals(listOf(TitleRef(MediaType.TV, 1), TitleRef(MediaType.MOVIE, 550)), p.credits.map { it.ref })
    }

    @Test fun detailsAskForExtras(): Unit {
        val path = TmdbPaths.details(TitleRef(MediaType.MOVIE, 550))
        assertTrue("images,videos,recommendations,similar" in path && "include_image_language=en,null" in path)
    }
}
