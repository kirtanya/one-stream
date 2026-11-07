package com.kirtanya.reel.core.tmdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Turns TMDB v3 JSON into Reel's models. Tolerant: missing fields become null/empty. */
object TmdbParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A list response (`results`). Items without `media_type` get [defaultType]; people are dropped.
     */
    fun parseList(body: String, defaultType: MediaType? = null): List<TitleSummary> {
        val results = root(body)["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val type = when (o.str("media_type")) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                null -> defaultType
                else -> null
            } ?: return@mapNotNull null
            summary(o, type)
        }
    }

    /**
     * A search (or discover) page as ranking hits: films and shows with popularity and genres, plus the known-for
     * titles of any person found ("tom cruise" finds his films). [defaultType] for discover pages (no media_type).
     */
    fun parseSearchHits(body: String, defaultType: MediaType? = null): List<com.kirtanya.reel.core.browse.SearchRank.Hit> {
        val results = root(body)["results"] as? JsonArray ?: return emptyList()
        fun hit(o: JsonObject, type: MediaType, popularity: Double?): com.kirtanya.reel.core.browse.SearchRank.Hit? {
            val s = summary(o, type) ?: return null
            val genres = (o["genre_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            return com.kirtanya.reel.core.browse.SearchRank.Hit(s, popularity ?: 0.0, genres)
        }
        fun typeOf(o: JsonObject) = when (o.str("media_type")) {
            "movie" -> MediaType.MOVIE
            "tv" -> MediaType.TV
            null -> defaultType
            else -> null
        }
        return results.mapNotNull { it as? JsonObject }.flatMap { o ->
            if (o.str("media_type") == "person") {
                // A person's titles rank on their own popularity, like any other hit.
                (o["known_for"] as? JsonArray).orEmpty().mapNotNull { k ->
                    val ko = k as? JsonObject ?: return@mapNotNull null
                    hit(ko, typeOf(ko) ?: return@mapNotNull null, ko.double("popularity"))
                }
            } else {
                listOfNotNull(typeOf(o)?.let { hit(o, it, o.double("popularity")) })
            }
        }
    }

    /**
     * A `find` response: the [type] match first, else the other kind (a launcher catalog can file a
     * mini-series as a film). Null when TMDB doesn't know the id.
     */
    fun parseFind(body: String, type: MediaType): TitleSummary? {
        val o = root(body)
        fun first(t: MediaType) = (o[if (t == MediaType.MOVIE) "movie_results" else "tv_results"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.let { item -> summary(item, t) } }
        return first(type) ?: first(if (type == MediaType.MOVIE) MediaType.TV else MediaType.MOVIE)
    }

    fun parseDetails(body: String, type: MediaType, region: String = "IN"): TitleDetails {
        val o = root(body)
        val summary = summary(o, type) ?: error("No id in details")
        val runtime = when (type) {
            MediaType.MOVIE -> o.int("runtime")
            MediaType.TV -> (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.asInt()
                ?: (o.obj("last_episode_to_air"))?.int("runtime")
        }?.takeIf { it > 0 }
        val seasons = (o["seasons"] as? JsonArray).orEmpty().mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            SeasonSummary(
                number = so.int("season_number") ?: return@mapNotNull null,
                name = so.str("name") ?: "Season ${so.int("season_number")}",
                episodeCount = so.int("episode_count") ?: 0,
                posterPath = so.str("poster_path"),
                year = so.str("air_date")?.take(4)?.toIntOrNull(),
            )
        }.filter { it.episodeCount > 0 }.sortedWith(compareBy({ it.number == 0 }, { it.number }))
        return TitleDetails(
            summary = summary,
            tagline = o.str("tagline")?.takeIf { it.isNotBlank() },
            runtimeMin = runtime,
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            certification = certification(o, type, region),
            cast = (o.obj("credits")?.get("cast") as? JsonArray).orEmpty().take(20).mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                CastMember(co.str("name") ?: return@mapNotNull null, co.str("character")?.takeIf { it.isNotBlank() }, co.str("profile_path"), co.int("id"))
            },
            seasons = seasons,
            status = o.str("status")?.takeIf { it.isNotBlank() },
            imdbId = (o.obj("external_ids")?.str("imdb_id") ?: o.str("imdb_id"))?.takeIf { it.startsWith("tt") },
            providers = providers(o.obj("watch/providers"), region),
            lastAired = lastAiredRegular(o),
            logoPath = o.obj("images")?.let(::bestLogo),
            videos = videos(o.obj("videos")),
            recommendations = (o.obj("recommendations")?.let { parseList(it.toString(), type) }.orEmpty())
                .ifEmpty { o.obj("similar")?.let { parseList(it.toString(), type) }.orEmpty() }
                .filter { it.ref != summary.ref && it.posterPath != null }
                .take(20),
            genreList = (o["genres"] as? JsonArray).orEmpty().mapNotNull { g ->
                val go = g as? JsonObject ?: return@mapNotNull null
                Genre(go.int("id") ?: return@mapNotNull null, go.str("name") ?: return@mapNotNull null)
            },
            collection = o.obj("belongs_to_collection")?.let { c ->
                c.int("id")?.let { CollectionRef(it, c.str("name").orEmpty(), c.str("poster_path"), c.str("backdrop_path")) }
            },
            nextAiring = o.obj("next_episode_to_air")?.let(::airing),
            lastAiredEpisode = o.obj("last_episode_to_air")?.let(::airing),
            originalLanguage = o.str("original_language")?.takeIf { it.isNotBlank() },
            backdrops = (o.obj("images")?.get("backdrops") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.str("iso_639_1") == null } // textless
                .sortedByDescending { it.double("vote_average") ?: 0.0 }
                .mapNotNull { it.str("file_path") }
                .take(8),
        )
    }

    /** A title's `images` response (or the appended `images` object) → the best logo path. */
    fun parseLogo(body: String): String? = bestLogo(root(body))

    /** English logos first, then textless; the best voted; PNG (transparent) before SVG. */
    private fun bestLogo(images: JsonObject): String? =
        (images["logos"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("file_path") != null && it.str("iso_639_1").let { l -> l == "en" || l == null } }
            .sortedWith(
                compareBy<JsonObject>({ if (it.str("iso_639_1") == "en") 0 else 1 })
                    .thenBy { if (it.str("file_path")!!.endsWith(".svg")) 1 else 0 }
                    .thenByDescending { it.double("vote_average") ?: 0.0 },
            )
            .firstOrNull()?.str("file_path")

    /** YouTube only; trailers, then teasers, then the rest; official first, newest first. */
    private fun videos(obj: JsonObject?): List<Video> {
        val order = listOf("Trailer", "Teaser", "Clip", "Featurette", "Behind the Scenes")
        return (obj?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("site") == "YouTube" && it.str("key") != null }
            .sortedWith(
                compareBy<JsonObject>({ order.indexOf(it.str("type")).let { i -> if (i < 0) order.size else i } })
                    .thenBy { if ((it["official"] as? JsonPrimitive)?.content == "true") 0 else 1 }
                    .thenByDescending { it.str("published_at").orEmpty() },
            )
            .map { Video(it.str("key")!!, it.str("name").orEmpty(), it.str("type").orEmpty(), (it["official"] as? JsonPrimitive)?.content == "true") }
            .take(12)
    }

    private fun airing(e: JsonObject): AiringEpisode? = AiringEpisode(
        season = e.int("season_number") ?: return null,
        episode = e.int("episode_number") ?: return null,
        name = e.str("name").orEmpty(),
        airDate = e.str("air_date"),
        stillPath = e.str("still_path"),
    )

    /** A collection: its films oldest first (unreleased ones without a date last). */
    fun parseCollection(body: String): CollectionDetails {
        val o = root(body)
        val ref = CollectionRef(o.int("id") ?: error("No id in collection"), o.str("name").orEmpty(), o.str("poster_path"), o.str("backdrop_path"))
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .sortedWith(compareBy({ it.str("release_date").isNullOrBlank() }, { it.str("release_date").orEmpty() }))
            .mapNotNull { summary(it, MediaType.MOVIE) }
        return CollectionDetails(ref, o.str("overview").orEmpty(), parts)
    }

    fun parseGenres(body: String): List<Genre> =
        (root(body)["genres"] as? JsonArray).orEmpty().mapNotNull { g ->
            val go = g as? JsonObject ?: return@mapNotNull null
            Genre(go.int("id") ?: return@mapNotNull null, go.str("name") ?: return@mapNotNull null)
        }

    /** A person with `combined_credits`: acting credits, most popular first, one entry per title. */
    fun parsePerson(body: String): PersonDetails {
        val o = root(body)
        val credits = (o.obj("combined_credits")?.get("cast") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .sortedByDescending { it.double("popularity") ?: 0.0 }
            .mapNotNull { c ->
                val type = when (c.str("media_type")) { "movie" -> MediaType.MOVIE; "tv" -> MediaType.TV; else -> null } ?: return@mapNotNull null
                summary(c, type)
            }
            .filter { it.posterPath != null }
            .distinctBy { it.ref }
            .take(40)
        return PersonDetails(
            id = o.int("id") ?: error("No id in person"),
            name = o.str("name").orEmpty(),
            profilePath = o.str("profile_path"),
            biography = o.str("biography").orEmpty(),
            knownFor = o.str("known_for_department"),
            credits = credits,
        )
    }

    /**
     * The latest aired regular episode (season, episode). A special (season 0) says nothing about
     * regular seasons, so then the episode before `next_episode_to_air` is used; null if unknown.
     */
    private fun lastAiredRegular(o: JsonObject): Pair<Int, Int>? {
        fun JsonObject.num(): Pair<Int, Int>? {
            val s = int("season_number") ?: return null
            val n = int("episode_number") ?: return null
            return s to n
        }
        o.obj("last_episode_to_air")?.num()?.takeIf { it.first > 0 }?.let { return it }
        val (s, n) = o.obj("next_episode_to_air")?.num()?.takeIf { it.first > 0 } ?: return null
        return when {
            n > 1 -> s to n - 1
            s > 1 -> s - 1 to Int.MAX_VALUE // everything in the previous season has aired
            else -> null
        }
    }

    fun parseSeason(body: String): List<Episode> {
        val o = root(body)
        val seasonNumber = o.int("season_number") ?: 0
        return (o["episodes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            Episode(
                season = eo.int("season_number") ?: seasonNumber,
                number = eo.int("episode_number") ?: return@mapNotNull null,
                name = eo.str("name") ?: "Episode ${eo.int("episode_number")}",
                overview = eo.str("overview").orEmpty(),
                stillPath = eo.str("still_path"),
                airDate = eo.str("air_date"),
                runtimeMin = eo.int("runtime"),
            )
        }
    }

    /** Parses a `watch/providers` object (the one with `results` keyed by country). */
    fun providers(obj: JsonObject?, region: String): WatchProviders {
        val r = obj?.obj("results")?.obj(region) ?: return WatchProviders.NONE
        fun list(key: String) = (r[key] as? JsonArray).orEmpty().mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            Provider(po.int("provider_id") ?: return@mapNotNull null, po.str("provider_name") ?: return@mapNotNull null, po.str("logo_path"))
        }
        return WatchProviders(
            link = r.str("link"),
            stream = list("flatrate"),
            free = list("free"),
            ads = list("ads"),
            rent = list("rent"),
            buy = list("buy"),
        )
    }

    private fun summary(o: JsonObject, type: MediaType): TitleSummary? {
        val id = o.int("id") ?: return null
        val title = (if (type == MediaType.MOVIE) o.str("title") else o.str("name")) ?: o.str("title") ?: o.str("name") ?: return null
        val date = if (type == MediaType.MOVIE) o.str("release_date") else o.str("first_air_date")
        return TitleSummary(
            ref = TitleRef(type, id),
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            posterPath = o.str("poster_path"),
            backdropPath = o.str("backdrop_path"),
            rating = o.double("vote_average")?.takeIf { it > 0 },
            overview = o.str("overview").orEmpty(),
        )
    }

    private fun certification(o: JsonObject, type: MediaType, region: String): String? {
        fun pick(country: String): String? = when (type) {
            MediaType.MOVIE -> (o.obj("release_dates")?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("iso_3166_1") == country }
                ?.let { (it["release_dates"] as? JsonArray).orEmpty() }
                ?.mapNotNull { (it as? JsonObject)?.str("certification")?.takeIf { c -> c.isNotBlank() } }
                ?.firstOrNull()
            MediaType.TV -> (o.obj("content_ratings")?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("iso_3166_1") == country }
                ?.str("rating")?.takeIf { it.isNotBlank() }
        }
        return pick(region) ?: pick("US")
    }

    private fun root(body: String): JsonObject = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
}
