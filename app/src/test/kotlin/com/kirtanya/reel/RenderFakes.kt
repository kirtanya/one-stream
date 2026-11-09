package com.kirtanya.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.core.sources.SubtitleTrack
import com.kirtanya.reel.core.store.StoreAddon
import com.kirtanya.reel.core.tmdb.CastMember
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.PersonDetails
import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.core.tmdb.SeasonSummary
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.Video
import com.kirtanya.reel.core.tmdb.WatchProviders
import com.kirtanya.reel.data.HomeRow
import com.kirtanya.reel.data.InstalledAddon
import com.kirtanya.reel.data.InstalledPlugin
import com.kirtanya.reel.data.InstalledRepo
import com.kirtanya.reel.data.SourcesState
import com.kirtanya.reel.data.StoreCatalog
import com.kirtanya.reel.data.StoreState
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import com.kirtanya.reel.ui.common.Catalog
import kotlinx.coroutines.awaitCancellation
import kotlin.math.absoluteValue

/** How a [FakeCatalog] answers: with data, never (spinner), with an error, or with nothing. */
enum class Answer { Data, Loading, Fail, Empty }

/**
 * Fixed TMDB answers for screen renders. Paths are fake but tell [FakeArt] what to draw
 * ("/poster_3.jpg", "/logo_Dune_Part_Two.png"…).
 */
class FakeCatalog(private val answer: Answer = Answer.Data, private val logos: Boolean = true) : Catalog {
    private suspend fun <T> give(data: T, empty: T): T = when (answer) {
        Answer.Data -> data
        Answer.Empty -> empty
        Answer.Loading -> awaitCancellation()
        Answer.Fail -> throw Exception("Can't reach TMDB. Check your connection.")
    }

    override suspend fun home() = give(Fake.home, emptyList())
    override suspend fun onService(providerId: Int) = give(Fake.titles(30, seed = providerId), emptyList())
    override suspend fun search(query: String) = give(if (query.startsWith("zz")) emptyList() else Fake.titles(20, seed = 7), emptyList())
    override suspend fun details(ref: TitleRef) = give(Fake.details(ref), Fake.bare(ref))
    override suspend fun season(tvId: Int, season: Int) = give(Fake.episodes(season, if (tvId == Fake.LONG_ID) 3 else 24), emptyList())
    override suspend fun logo(ref: TitleRef) = if (!logos || answer != Answer.Data) null else Fake.logoFor(ref)
    override suspend fun genres(type: MediaType) = give(if (type == MediaType.MOVIE) Fake.movieGenres else Fake.tvGenres, emptyList())
    override suspend fun byGenre(type: MediaType, genreId: Int, page: Int) = give(Fake.titles(20, seed = genreId * 31 + page), emptyList())
    override suspend fun browseRows(type: MediaType) = give(Fake.browseRows(type), emptyList())
    override suspend fun byLanguage(type: MediaType, code: String, page: Int) = give(Fake.titles(20, seed = 40 + (code.hashCode().absoluteValue % 30) + page, type = type), emptyList())
    override suspend fun person(id: Int) = give(Fake.person, Fake.person.copy(biography = "", credits = emptyList()))
    override suspend fun findByImdb(imdbId: String, type: MediaType) = give(Fake.details(if (type == MediaType.TV) Fake.show else Fake.film).summary, null)
    override suspend fun collection(id: Int) = give(
        com.kirtanya.reel.core.tmdb.CollectionDetails(Fake.franchise, "Two films.", listOf(Fake.details(Fake.film).summary.copy(title = "Dune", year = 2021)) + Fake.details(Fake.film).summary + Fake.titles(1, seed = 70)),
        com.kirtanya.reel.core.tmdb.CollectionDetails(Fake.franchise, "", emptyList()),
    )
    override suspend fun reviews(ref: TitleRef) = give(if (ref.id == Fake.LONG_ID) com.kirtanya.reel.core.tmdb.Reviews.NONE else Fake.reviews, com.kirtanya.reel.core.tmdb.Reviews.NONE)
    override suspend fun titleRatings(d: TitleDetails) = if (answer == Answer.Data) Fake.ratings(d.summary.ref) else null
    override fun cachedImdb(ref: TitleRef) = if (answer == Answer.Data) Fake.imdbFor(ref) else null
    override suspend fun posterImdb(ref: TitleRef) = cachedImdb(ref)
    override suspend fun episodeImdb(d: TitleDetails, season: Int) =
        if (answer == Answer.Data) (1..24).filter { it % 6 != 4 }.associateWith { 7.2 + (it * 7 % 25) / 10.0 } else emptyMap()
    override suspend fun discover(filter: com.kirtanya.reel.core.tmdb.DiscoverFilter, page: Int) = give(Fake.titles(20, seed = filter.genres.firstOrNull() ?: 0 + page), emptyList())
    /** "New episodes": one aired yesterday, one today, two coming. */
    override suspend fun calendar(shows: List<TitleRef>, from: java.time.LocalDate, to: java.time.LocalDate) = give(
        Fake.titles(4, seed = 5, type = MediaType.TV).mapIndexed { i, t ->
            com.kirtanya.reel.core.tmdb.CalendarEntry(
                t, com.kirtanya.reel.core.tmdb.AiringEpisode(2, 3 + i, if (i == 1) "The One Where Everything That Could Possibly Go Wrong Goes Wrong" else "Lord Snow", null, "/still_${40 + i}.jpg"),
                java.time.LocalDate.now().plusDays((i - 1).toLong()),
            )
        },
        emptyList(),
    )
}

object Fake {
    const val FILM_ID = 1001
    /** A film with poor scores: the Rotten splat, a red Metascore. */
    const val ROTTEN_ID = 1004
    val rotten = TitleRef(MediaType.MOVIE, ROTTEN_ID)

    /** IMDb on posters: most titles have one, every fifth has none (no pill). */
    fun imdbFor(ref: TitleRef): Double? = (ref.id % 5).takeIf { it != 2 }?.let { 5.8 + (ref.id * 7 % 33) / 10.0 }

    fun ratings(ref: TitleRef): com.kirtanya.reel.core.ratings.TitleRatings? = when (ref.id) {
        SHOW_ID -> com.kirtanya.reel.core.ratings.TitleRatings(imdb = 8.0)
        ROTTEN_ID -> com.kirtanya.reel.core.ratings.TitleRatings(4.9, 23, 35)
        LONG_ID -> null
        else -> com.kirtanya.reel.core.ratings.TitleRatings(8.5, 92, 79)
    }

    val reviews = com.kirtanya.reel.core.tmdb.Reviews(
        listOf(
            com.kirtanya.reel.core.tmdb.Review("John Chard", 9.0, "Your mind is the scene of the crime. Villeneuve builds a desert epic that rewards every second of attention, anchored by a score that refuses to let go. The sandworm ride alone is worth the ticket, and the final act lands every thread the first film set up.\n\nA second paragraph that only the full review shows, to check the sheet scrolls.", "2024-03-02T10:00:00.000Z"),
            com.kirtanya.reel.core.tmdb.Review("msbreviews", 8.0, "An original blockbuster is rare; one this ambitious and coherent even rarer.", "2024-03-05T10:00:00.000Z"),
            com.kirtanya.reel.core.tmdb.Review("Reader With A Very Long Display Name Indeed", null, "I've watched it five times and still catch new details in the layers of sound and sand.", null),
            com.kirtanya.reel.core.tmdb.Review("tmdb44006625", 6.5, "Gorgeous, loud and a little long.", "2024-04-01T10:00:00.000Z"),
        ),
        total = 12,
    )
    val franchise = com.kirtanya.reel.core.tmdb.CollectionRef(77, "Dune Collection", "/poster_0.jpg", "/backdrop_0.jpg")
    const val SHOW_ID = 2002
    const val LONG_ID = 3003
    val film = TitleRef(MediaType.MOVIE, FILM_ID)
    val show = TitleRef(MediaType.TV, SHOW_ID)
    val long = TitleRef(MediaType.MOVIE, LONG_ID)

    private val names = listOf(
        "Dune: Part Two", "The Night Manager", "Ran", "Kalki 2898 AD", "Interstellar", "The Long Monsoon Nights of Kalimpong and the Tea Garden Ghosts",
        "Shōgun", "Oppenheimer", "Panchayat", "Past Lives", "Andor", "Everything Everywhere All at Once", "Laapataa Ladies", "Blade Runner 2049",
        "The Bear", "Arrival", "Severance", "Jawan", "Fallout", "Perfect Days", "Paatal Lok", "Spirited Away", "Mirzapur", "Aftersun",
    )
    private val overviews = listOf(
        "Paul Atreides unites with the Fremen while on a path of revenge against the conspirators who destroyed his family.",
        "A hotel night manager is recruited to infiltrate the inner circle of an arms dealer.",
        "",
        "In a dystopian future, a bounty hunter crosses paths with a pregnant woman whose child is said to be the long-awaited saviour, and a war breaks out over the last city on Earth as the rivers run dry.",
    )

    fun titles(n: Int, seed: Int = 0, type: MediaType? = null): List<TitleSummary> = (0 until n).map { i ->
        val k = (i + seed).absoluteValue
        val t = type ?: if (k % 3 == 1) MediaType.TV else MediaType.MOVIE
        TitleSummary(
            ref = TitleRef(t, 10_000 + seed * 100 + i),
            title = names[k % names.size],
            year = if (k % 11 == 5) null else 1985 + (k * 7) % 40,
            posterPath = if (k % 13 == 9) null else "/poster_$k.jpg",
            backdropPath = if (k % 17 == 3) null else "/backdrop_$k.jpg",
            rating = if (k % 9 == 4) null else 5.5 + (k % 40) / 10.0,
            overview = overviews[k % overviews.size],
        )
    }

    val home: List<HomeRow> = listOf(
        HomeRow("Trending this week", titles(14, seed = 0)),
        HomeRow("Popular movies", titles(12, seed = 3, type = MediaType.MOVIE)),
        HomeRow("Trending shows", titles(12, seed = 5, type = MediaType.TV)),
        HomeRow("Popular in India", titles(10, seed = 12)),
        HomeRow("Top rated shows", titles(1, seed = 20, type = MediaType.TV)),
    )

    /** The TV's Movies / Shows pages: every row of [com.kirtanya.reel.core.browse.BrowseRows], one kind only. */
    fun browseRows(type: MediaType): List<HomeRow> =
        com.kirtanya.reel.core.browse.BrowseRows.forType(type).mapIndexed { i, spec -> HomeRow(spec.title, titles(12, seed = 60 + i * 5, type = type)) }

    fun logoFor(ref: TitleRef): String? = (ref.id % 4).takeIf { it != 2 }?.let { "/logo_${(names.getOrNull((ref.id - 10_000).absoluteValue % names.size) ?: "Title").replace(' ', '_')}.png" }

    val movieGenres = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "TV Movie", "Thriller", "War", "Western")
        .mapIndexed { i, n -> Genre(i + 1, n) }
    val tvGenres = listOf("Action & Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Kids", "Mystery", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics", "Western")
        .mapIndexed { i, n -> Genre(100 + i, n) }

    private val cast = listOf(
        CastMember("Timothée Chalamet", "Paul Atreides", "/profile_1.jpg", 1),
        CastMember("Zendaya", "Chani", "/profile_2.jpg", 2),
        CastMember("Rebecca Ferguson", "Lady Jessica Atreides, Bene Gesserit Reverend Mother", "/profile_3.jpg", 3),
        CastMember("Javier Bardem", "Stilgar", null, 4),
        CastMember("Austin Butler", "Feyd-Rautha Harkonnen", "/profile_5.jpg", 5),
        CastMember("Florence Pugh", null, "/profile_6.jpg", 6),
        CastMember("Dave Bautista", "Glossu Rabban", "/profile_7.jpg", 7),
        CastMember("Christopher Walken", "Emperor Shaddam IV", "/profile_8.jpg", 8),
        CastMember("Léa Seydoux", "Lady Margot Fenring", "/profile_9.jpg", 9),
    )

    private val providers = WatchProviders(
        link = "https://www.themoviedb.org/movie/1001/watch?locale=IN",
        stream = listOf(Provider.FEATURED[0], Provider.FEATURED[1]),
        rent = listOf(Provider.FEATURED[3]),
        buy = listOf(Provider.FEATURED[3], Provider(999, "Google Play Movies & TV", "/provider_play.png")),
    )

    private val videos = listOf(
        Video("trailer1", "Official Trailer 3", "Trailer", true),
        Video("teaser1", "Official Teaser", "Teaser", true),
        Video("clip1", "Paul rides the sandworm — a very long featurette name that keeps going", "Featurette", true),
        Video("bts1", "Behind the Scenes", "Behind the Scenes", false),
    )

    fun details(ref: TitleRef): TitleDetails = when (ref.id) {
        SHOW_ID -> TitleDetails(
            summary = TitleSummary(ref, "The Night Manager", 2016, "/poster_1.jpg", "/backdrop_1.jpg", 8.1, overviews[1]),
            tagline = null,
            runtimeMin = 58,
            genres = listOf("Drama", "Crime", "Mystery"),
            certification = "A",
            cast = cast.take(6),
            seasons = (1..8).map { SeasonSummary(it, "Season $it", 10 + it, "/poster_${it + 4}.jpg", 2015 + it) } + SeasonSummary(0, "Specials", 3, null, null),
            imdbId = "tt1399664",
            providers = WatchProviders.NONE,
            lastAired = 8 to 6,
            status = "Ended",
            lastAiredEpisode = com.kirtanya.reel.core.tmdb.AiringEpisode(8, 6, "Finale", "2023-05-14"),
            logoPath = null,
            videos = videos.take(1),
            recommendations = titles(10, seed = 40, type = MediaType.TV),
            genreList = listOf(Genre(18, "Drama"), Genre(80, "Crime"), Genre(9648, "Mystery")),
            backdrops = listOf("/backdrop_1.jpg", "/backdrop_8.jpg"),
        )
        LONG_ID -> bare(ref).copy(
            summary = TitleSummary(ref, "The Extraordinarily Long and Winding Story of the Monsoon Nights of Kalimpong: Part Two", null, null, null, null, ""),
        )
        else -> TitleDetails(
            summary = TitleSummary(ref, "Dune: Part Two", 2024, "/poster_0.jpg", "/backdrop_0.jpg", 8.2, overviews[0]),
            tagline = "Long live the fighters.",
            runtimeMin = 166,
            genres = listOf("Science Fiction", "Adventure", "Drama"),
            certification = "UA",
            cast = cast,
            seasons = emptyList(),
            imdbId = "tt15239678",
            providers = providers,
            logoPath = "/logo_Dune:_Part_Two.png",
            videos = videos,
            recommendations = titles(12, seed = 30),
            collection = franchise,
            genreList = listOf(Genre(878, "Science Fiction"), Genre(12, "Adventure"), Genre(18, "Drama")),
            backdrops = listOf("/backdrop_0.jpg", "/backdrop_6.jpg", "/backdrop_9.jpg"),
        )
    }

    /** Only the essentials: no art, story, cast, providers or extras. */
    fun bare(ref: TitleRef) = TitleDetails(
        summary = TitleSummary(ref, "Untitled", null, null, null, null, ""),
        tagline = null, runtimeMin = null, genres = emptyList(), certification = null, cast = emptyList(),
        seasons = emptyList(), imdbId = null, providers = WatchProviders.NONE,
    )

    fun episodes(season: Int, n: Int): List<Episode> = (1..n).map { e ->
        Episode(
            season = season,
            number = e,
            name = when (e % 5) {
                0 -> "Episode $e"
                1 -> "The One Where Everything That Could Possibly Go Wrong Goes Wrong at Once"
                else -> listOf("Pilot", "Lord Snow", "The Kingsroad", "Winter Is Coming")[e % 4]
            },
            overview = if (e % 4 == 0) "" else overviews[e % 2],
            stillPath = if (e % 6 == 5) null else "/still_${season * 30 + e}.jpg",
            airDate = if (e > 20) null else "20${16 + season}-0${1 + e % 9}-1$e",
            runtimeMin = if (e % 7 == 3) null else 42 + e,
        )
    }

    val person = PersonDetails(
        id = 1, name = "Timothée Chalamet", profilePath = "/profile_1.jpg",
        biography = "Timothée Hal Chalamet is an American and French actor. He has received various accolades, including nominations for three Academy Awards, five Golden Globe Awards, and four BAFTA Awards. Born and raised in New York City, he began his career on television and in short films.",
        knownFor = "Acting", credits = titles(16, seed = 50),
    )

    // ─── Sources ─────────────────────────────────────────────────────────────────────────────

    private fun source(i: Int): Source {
        val res = listOf(2160, 1080, 1080, 720, 480, null)[i % 6]
        val kind = when (i % 7) { 3 -> SourceKind.DIRECT; 6 -> SourceKind.EXTERNAL; else -> SourceKind.TORRENT }
        val name = listOf(
            "Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FraMeSToR",
            "Dune Part Two (2024) 1080p WEB-DL",
            "Dune.Part.Two.2024.1080p.AMZN.WEB-DL.DDP5.1.Atmos.H.264-FLUX\n💾 9.8 GB 👤 412 ⚙️ TorrentGalaxy",
            "Dune Part Two 720p",
            "Dune.Part.Two.2024.HDCAM.x264",
            "Watch on Prime Video",
        )[i % 6]
        return Source(
            kind = kind,
            title = name,
            provider = listOf("Torrentio", "Comet | Real-Debrid", "MediaFusion 🇮🇳 (Hindi, Tamil, Telugu)", "Built-in: Internet Archive")[i % 4],
            quality = Quality(resolution = res, dolbyVision = i % 6 == 0, hdr = i % 6 == 0, lowQualityRip = i % 6 == 4),
            infoHash = if (kind == SourceKind.TORRENT) "%040x".format(i + 1) else null,
            url = if (kind == SourceKind.DIRECT) "https://example.org/v/$i.mp4" else null,
            externalUrl = if (kind == SourceKind.EXTERNAL) "https://www.primevideo.com/detail/$i" else null,
            fileIdx = i,
            // Languages ride on the file name so the release names other screens show stay the same.
            filename = when (i % 5) { 1 -> "release.[Hindi + English].mkv"; 2 -> "release.MULTi.mkv"; 3 -> "release.ITA.ENG.mkv"; else -> null },
            sizeBytes = if (i % 5 == 4) null else (1L + i % 9) * 1_700_000_000L,
            seeders = if (kind != SourceKind.TORRENT) null else listOf(412, 0, 9, 1203, 37)[i % 5],
            subtitles = if (i % 3 == 0) List(i % 4 + 1) { SubtitleTrack("https://example.org/s/$it.srt", "en") } else emptyList(),
        )
    }

    fun sources(n: Int): List<Source> = (0 until n).map(::source)

    val searching = SourceSearch(sources(4), pending = listOf("Torrentio", "Comet", "MediaFusion"), failed = emptyMap(), total = 7)
    val manySources = SourceSearch(
        sources(26), pending = emptyList(),
        failed = mapOf("Peerflix" to "Timed out after 30 seconds while waiting for a response from the server", "Orion" to "HTTP 403"),
        total = 9,
    )
    val noProviders = SourceSearch(emptyList(), pending = emptyList(), failed = emptyMap(), total = 0)
    val allFailed = SourceSearch(emptyList(), pending = emptyList(), failed = mapOf("Torrentio" to "HTTP 502", "Internet Archive" to "Timed out"), total = 2)
    val nothingFound = SourceSearch(emptyList(), pending = emptyList(), failed = mapOf("Torrentio" to "HTTP 502"), total = 5)

    // ─── Downloads ───────────────────────────────────────────────────────────────────────────

    private fun torrent(name: String, mode: TorrentMode, phase: TorrentPhase, progress: Float, error: String? = null, stalled: Int = 0, i: Int = 0) = TorrentSnapshot(
        infoHash = "%040x".format(name.hashCode().toLong().absoluteValue + i), name = name, mode = mode, phase = phase, progress = progress,
        wantedBytes = 14_200_000_000L, doneBytes = (14_200_000_000L * progress).toLong(), downBps = if (phase == TorrentPhase.DOWNLOADING) 6_400_000 else 0,
        upBps = 0, peers = if (phase == TorrentPhase.DOWNLOADING) 23 else 0, seeds = 4, hasMetadata = phase != TorrentPhase.METADATA,
        savePath = "/storage/emulated/0/Download/Reel", error = error, stalledForSec = stalled,
    )

    val torrents = listOf(
        torrent("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FraMeSToR", TorrentMode.DOWNLOAD, TorrentPhase.DOWNLOADING, 0.42f),
        torrent("magnet link", TorrentMode.DOWNLOAD, TorrentPhase.METADATA, 0f),
        torrent("Big Buck Bunny", TorrentMode.DOWNLOAD, TorrentPhase.PAUSED, 0.67f),
        torrent("Sintel", TorrentMode.DOWNLOAD, TorrentPhase.FINISHED, 1f),
        torrent("Tears of Steel (2012) [1080p] [Blender Open Movie] [Creative Commons]", TorrentMode.DOWNLOAD, TorrentPhase.SEEDING, 1f),
        torrent("The.Night.Manager.S01E03.1080p.WEB.h264", TorrentMode.DOWNLOAD, TorrentPhase.ERROR, 0.12f, error = "Not enough space on this device: 3.1 GB free, 9.8 GB needed"),
        torrent("Panchayat.S03.COMPLETE.720p.AMZN.WEB-DL", TorrentMode.DOWNLOAD, TorrentPhase.DOWNLOADING, 0.08f, stalled = 300, i = 1),
        torrent("Interstellar.2014.IMAX.1080p.BluRay.x265", TorrentMode.STREAM, TorrentPhase.DOWNLOADING, 0.31f, i = 2),
        torrent("Big.Buck.Bunny.2008.1080p.Blender", TorrentMode.STREAM, TorrentPhase.PAUSED, 0.86f, i = 3),
    )

    /** Downloads started from a title's sources know their title (Change source); magnet links don't. */
    val origins = mapOf(
        torrents[0].infoHash to com.kirtanya.reel.core.library.DownloadOrigin("movie", FILM_ID, label = "Dune: Part Two"),
        torrents[5].infoHash to com.kirtanya.reel.core.library.DownloadOrigin("tv", SHOW_ID, 1, 3, "The Night Manager · S1 E3"),
    )

    // ─── Settings ────────────────────────────────────────────────────────────────────────────

    private fun manifest(id: String, name: String, description: String, streams: Boolean = true, subtitles: Boolean = false) =
        """{"id":"$id","name":"$name","version":"1.4.2","description":"$description","types":["movie","series"],"resources":[${listOfNotNull(if (streams) "\"stream\"" else null, if (subtitles) "\"subtitles\"" else null).joinToString(",")}]}"""

    val installed = SourcesState(
        addons = listOf(
            InstalledAddon("https://torrentio.strem.fun/manifest.json", manifest("org.torrentio", "Torrentio", "Provides torrent streams from scraped torrent providers.")),
            InstalledAddon("https://opensubtitles-v3.strem.io/manifest.json", manifest("org.opensubtitles", "OpenSubtitles v3", "Subtitles from OpenSubtitles", streams = false, subtitles = true)),
            InstalledAddon(
                "https://mediafusion.elfhosted.com/eyJlbmFibGVkX3Byb3ZpZGVycyI6WyJyZCJdfQ/manifest.json",
                manifest("stremio.addons.mediafusion", "MediaFusion | ElfHosted 🇮🇳 Hindi · Tamil · Telugu · Malayalam · Kannada · Bengali", "Universal addon for Indian movies, series, live TV and sports with debrid support and a really long description that goes on."),
                enabled = false,
            ),
            InstalledAddon("https://broken.example/manifest.json", "not json", enabled = true),
        ),
        repos = listOf(
            InstalledRepo("builtin://legal", "Open movies", "1.2", builtIn = true),
            InstalledRepo("https://raw.githubusercontent.com/someone/a-very-long-repository-name-for-nuvio-plugins/refs/heads/main/manifest.json", "Community providers with a long repository name", "3.0.11"),
        ),
        plugins = listOf(
            InstalledPlugin("archive", "builtin://legal", "Internet Archive", "1.2", "Public-domain films from archive.org", "archive.js", listOf("movie")),
            InstalledPlugin("blender", "builtin://legal", "Blender open movies", "1.0", null, "blender.js", listOf("movie")),
            InstalledPlugin(
                "longone", "https://raw.githubusercontent.com/someone/a-very-long-repository-name-for-nuvio-plugins/refs/heads/main/manifest.json",
                "A Provider With An Unusually Long Plugin Name That Wraps", "3.0.11", "Finds streams for films and shows from a mirror network with several fallbacks.",
                "long.js", listOf("movie", "tv"), enabled = false,
            ),
        ),
    )

    val store = StoreState(
        catalogs = listOf(StoreCatalog("https://api.strem.io/addonscollection.json", "Stremio official", builtIn = true), StoreCatalog("https://stremio-addons.net/catalog.json", "stremio-addons.net community catalog with a long name")),
        addons = (0 until 14).map { i ->
            StoreAddon(
                manifestUrl = "https://addon$i.example/manifest.json",
                id = if (i == 0) "org.torrentio" else "addon.$i",
                name = listOf("Torrentio", "OpenSubtitles v3", "MediaFusion | ElfHosted 🇮🇳 Hindi · Tamil · Telugu · Malayalam", "Comet", "Peerflix", "WatchHub")[i % 6],
                description = listOf("Provides torrent streams from scraped torrent providers.", "", "Universal addon for Indian movies, series, live TV and sports with debrid support, catalogs, and a long list of languages and providers.")[i % 3],
                logo = if (i % 4 == 3) null else "https://addon$i.example/logo.png",
                version = "1.$i.0",
                hasStreams = i % 6 != 1,
                hasSubtitles = i % 6 == 1,
                configurable = i % 3 == 2,
                needsSetup = i % 5 == 3,
                catalog = if (i % 7 == 6) "https://stremio-addons.net/catalog.json" else "https://api.strem.io/addonscollection.json",
                types = listOf(listOf("movie", "series"), listOf("movie"), listOf("series", "anime"), listOf("tv", "channel"))[i % 4],
                hasCatalogs = i % 3 == 2,
            )
        },
        failed = listOf("stremio-addons.net community catalog with a long name"),
    )

    // ─── History ─────────────────────────────────────────────────────────────────────────────

    /** Continue watching: a film half-way, a show with its next episode up, a raw torrent with no artwork. */
    fun seedHistory(app: ReelApp) {
        val src = sources(1).first()
        app.history.save(
            Playback("movie:$FILM_ID", "movie:$FILM_ID", "Dune: Part Two", tmdbType = "movie", tmdbId = FILM_ID, posterPath = "/poster_0.jpg", backdropPath = "/backdrop_0.jpg", source = src),
            positionMs = 70 * 60_000L, durationMs = 166 * 60_000L,
        )
        app.history.save(
            Playback(
                "tv:$SHOW_ID:s2e5", "tv:$SHOW_ID", "The Night Manager", subtitle = "S2 E5 · Lord Snow", tmdbType = "tv", tmdbId = SHOW_ID,
                episode = EpisodeNumber(2, 5), posterPath = "/poster_1.jpg", backdropPath = "/backdrop_1.jpg", stillPath = "/still_65.jpg",
                next = EpisodeNumber(2, 6), source = src,
            ),
            positionMs = 57 * 60_000L, durationMs = 58 * 60_000L,
        )
        app.history.save(Playback.forTorrentFile("%040x".format(77), 0, "Tears.of.Steel.2012.1080p.BluRay.x264.Blender.Open.Movie"), positionMs = 4 * 60_000L, durationMs = 12 * 60_000L)
        // My list: a few titles, one of them the film page's.
        (titles(4, seed = 60) + details(film).summary).forEach(app.watchlist::add)
    }

    fun clearHistory(app: ReelApp) {
        app.history.entries.value.map { it.playback.titleKey }.distinct().forEach(app.history::removeTitle)
        app.watchlist.all.value.filterNot { it.removed }.mapNotNull { it.ref }.forEach(app.watchlist::remove)
    }
}

/**
 * Paints stand-in artwork for any image URL, since network images don't load in renders:
 * posters and backdrops get a "scene" (some bright, to test scrims), logos get the title in
 * white on transparent, avatars and service logos a flat tile.
 */
object FakeArt : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data.toString()
        return SuccessResult(image = draw(url).asImage(), request = chain.request, dataSource = DataSource.MEMORY)
    }

    private val palettes = listOf(
        intArrayOf(0xFF0B1E3F.toInt(), 0xFFE07A2E.toInt(), 0xFFFFD27A.toInt()), // dusk: navy → orange
        intArrayOf(0xFFF3E3C3.toInt(), 0xFFD9A066.toInt(), 0xFFFFFFFF.toInt()), // bright desert (tests scrims)
        intArrayOf(0xFF06251F.toInt(), 0xFF1F8A70.toInt(), 0xFFBEE3B6.toInt()), // forest
        intArrayOf(0xFF2A0A12.toInt(), 0xFFB3122E.toInt(), 0xFFFF9AA2.toInt()), // red
        intArrayOf(0xFF10101A.toInt(), 0xFF3B3B58.toInt(), 0xFF9AA0C3.toInt()), // night, almost grey
        intArrayOf(0xFFE8F1FF.toInt(), 0xFF7FB2FF.toInt(), 0xFFFFFFFF.toInt()), // pale sky (bright)
    )

    fun draw(url: String): Bitmap {
        val key = url.substringAfterLast('/')
        val seed = key.hashCode().absoluteValue
        return when {
            key.startsWith("logo_") -> logo(key.removePrefix("logo_").substringBeforeLast('.').replace('_', ' '))
            key.startsWith("poster_") -> scene(300, 450, seed)
            key.startsWith("backdrop_") -> scene(640, 360, seed)
            key.startsWith("still_") || "youtube" in url -> scene(400, 225, seed)
            key.startsWith("profile_") -> tile(200, 200, seed, round = false)
            else -> tile(120, 120, seed, round = true)
        }
    }

    private fun scene(w: Int, h: Int, seed: Int): Bitmap {
        val p = palettes[seed % palettes.size]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // A sun / moon, and a horizon with a couple of figures.
        val cx = w * (0.3f + (seed % 40) / 100f)
        val cy = h * 0.38f
        paint.shader = RadialGradient(cx, cy, w * 0.22f, p[2], p[2] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, w * 0.22f, paint)
        paint.shader = null
        paint.color = (p[0] and 0x00FFFFFF) or (0xD0 shl 24)
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), paint)
        paint.color = (0xFF000000).toInt()
        c.drawRect(w * 0.55f, h * 0.52f, w * 0.6f, h * 0.74f, paint)
        c.drawCircle(w * 0.575f, h * 0.5f, w * 0.03f, paint)
        return bmp
    }

    private fun logo(title: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 96f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val text = title.uppercase()
        val w = (paint.measureText(text) + 40).toInt().coerceIn(200, 1400)
        val bmp = Bitmap.createBitmap(w, 160, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 20f, 116f, paint)
        return bmp
    }

    private fun tile(w: Int, h: Int, seed: Int, round: Boolean): Bitmap {
        val p = palettes[seed % palettes.size]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p[1], p[0], Shader.TileMode.CLAMP)
        if (round) c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), w * 0.2f, h * 0.2f, paint) else c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = p[2]
        c.drawCircle(w / 2f, h * 0.42f, w * 0.18f, paint)
        return bmp
    }
}
