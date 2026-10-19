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
                CastMember(co.str("name") ?: return@mapNotNull null, co.str("character")?.takeIf { it.isNotBlank() }, co.str("profile_path"))
            },
            seasons = seasons,
            imdbId = (o.obj("external_ids")?.str("imdb_id") ?: o.str("imdb_id"))?.takeIf { it.startsWith("tt") },
            providers = providers(o.obj("watch/providers"), region),
        )
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
