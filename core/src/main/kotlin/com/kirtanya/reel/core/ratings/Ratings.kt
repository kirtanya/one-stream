package com.kirtanya.reel.core.ratings

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Outside ratings for a title: IMDb (0–10), Rotten Tomatoes (critics %, 0–100) and Metascore (0–100).
 * Null = that source has no score. Series usually only have IMDb.
 */
@Serializable
data class TitleRatings(val imdb: Double? = null, val rottenTomatoes: Int? = null, val metascore: Int? = null) {
    val isEmpty get() = imdb == null && rottenTomatoes == null && metascore == null
}

/** Rotten Tomatoes' two looks: the red tomato (Fresh, 60 % and up) or the green splat (Rotten). */
enum class Tomato { FRESH, ROTTEN }

/** Metacritic's colour bands: green 61–100, yellow 40–60, red 0–39. */
enum class MetaBand { FAVORABLE, MIXED, UNFAVORABLE }

object RatingRules {
    const val FRESH_FROM = 60

    fun tomato(percent: Int): Tomato = if (percent >= FRESH_FROM) Tomato.FRESH else Tomato.ROTTEN

    fun metaBand(score: Int): MetaBand = when {
        score >= 61 -> MetaBand.FAVORABLE
        score >= 40 -> MetaBand.MIXED
        else -> MetaBand.UNFAVORABLE
    }

    /** "8.8" (always one decimal, a dot whatever the phone's language). */
    fun imdbLabel(rating: Double): String = String.format(Locale.US, "%.1f", rating)
}

/** OMDb, Cinemeta and TMDB external-id answers → ratings. Tolerant: "N/A", junk and errors become null. */
object RatingsParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * An OMDb `?i=tt…` answer. Null when OMDb doesn't know the title (`"Response":"False"`) or the body
     * isn't JSON; otherwise whatever scores it has (all may be null).
     */
    fun omdb(body: String): TitleRatings? {
        val o = root(body) ?: return null
        if (o.str("Response")?.equals("True", ignoreCase = true) != true) return null
        val sources = (o["Ratings"] as? JsonArray).orEmpty().mapNotNull { r ->
            val ro = r as? JsonObject ?: return@mapNotNull null
            (ro.str("Source") ?: return@mapNotNull null) to (ro.str("Value") ?: return@mapNotNull null)
        }.toMap()
        val imdb = imdbNumber(o.str("imdbRating")) ?: imdbNumber(sources["Internet Movie Database"]?.substringBefore('/'))
        val rt = sources["Rotten Tomatoes"]?.trim()?.removeSuffix("%")?.toIntOrNull()?.takeIf { it in 0..100 }
        val mc = (o.str("Metascore")?.trim()?.toIntOrNull() ?: sources["Metacritic"]?.substringBefore('/')?.trim()?.toIntOrNull())
            ?.takeIf { it in 0..100 }
        return TitleRatings(imdb, rt, mc)
    }

    /**
     * OMDb's `?i=<show tt>&Season=n`: episode number → that episode's IMDb rating. Episodes without a
     * rating ("N/A") and junk numbers are left out; an error answer gives an empty map.
     */
    fun omdbSeason(body: String): Map<Int, Double> {
        val o = root(body) ?: return emptyMap()
        if (o.str("Response")?.equals("True", ignoreCase = true) != true) return emptyMap()
        return (o["Episodes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            val n = eo.str("Episode")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val r = imdbNumber(eo.str("imdbRating")) ?: return@mapNotNull null
            n to r
        }.toMap()
    }

    /** Cinemeta's `meta.imdbRating` ("8.8"; sometimes a number, often missing for new titles). */
    fun cinemetaImdb(body: String): Double? {
        val meta = root(body)?.get("meta") as? JsonObject ?: return null
        val p = meta["imdbRating"] as? JsonPrimitive ?: return null
        return imdbNumber(p.content)
    }

    /** TMDB `/external_ids`: the IMDb id when it looks like one ("tt" + digits). */
    fun imdbId(body: String): String? = root(body)?.str("imdb_id")?.trim()?.takeIf { IMDB_ID.matches(it) }

    private val IMDB_ID = Regex("tt\\d{5,10}")

    /** "8.8" → 8.8; "N/A", "", "0", out of range → null (a 0.0 is "no votes", not a score). */
    private fun imdbNumber(s: String?): Double? = s?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }

    private fun root(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
