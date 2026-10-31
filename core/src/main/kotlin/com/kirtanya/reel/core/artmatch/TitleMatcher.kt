package com.kirtanya.reel.core.artmatch

import com.kirtanya.reel.core.tmdb.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One TMDB search result, with what scoring needs (popularity, original title, countries). */
data class MatchCandidate(
    val type: MediaType,
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val popularity: Double = 0.0,
    /** Shows: TMDB `origin_country` ("US", "GB"…). */
    val countries: List<String> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
)

/** [candidate] won with [score]; null [candidate] = no confident match ([reason] says why, for telemetry). */
data class MatchOutcome(val candidate: MatchCandidate?, val score: Double?, val reason: String)

/**
 * Picks the TMDB title a release name means, or none. A wrong poster on Continue watching (and wrong
 * subtitles / Up next behind it) is worse than the letter tile, so it only accepts confident matches:
 *
 *  score = 0.70 × title similarity + 0.20 × year agreement + 0.10 × popularity (+ country hint)
 *
 * - similarity: 1 − edit distance / length on normalised titles (lower case, accents and punctuation
 *   gone, "&" = "and", a leading "The / A / An" ignored), the better of TMDB's title and original title.
 *   A name that is the start of TMDB's title ("Mission Impossible Dead Reckoning" for "… Part One")
 *   counts 0.90 when the year agrees exactly. Below [MIN_SIMILARITY] a candidate is out.
 * - year: same 1.0, one year apart 0.6 (festival vs release dates), unknown on either side 0.4, further
 *   apart → out.
 * - popularity: log-scaled, 100+ on TMDB's scale = full marks; it only breaks near-ties.
 * - country ("The.Office.US"): +0.05 when the show is from there, −0.10 when it's from elsewhere.
 *
 * Accepted when the best score ≥ [MIN_SCORE] and no other title is within [AMBIGUITY_MARGIN] of it
 * (two same-named films with no year in the name: "The Lion King" → no match rather than a guess).
 * Worked numbers: an exact title + year scores ≥ 0.90; an exact title without a year needs some
 * popularity (TMDB popularity ≥ ~2 reaches 0.80); a 0.85-similar title needs the exact year.
 */
object TitleMatcher {
    const val MIN_SIMILARITY = 0.85
    const val MIN_SCORE = 0.80
    const val AMBIGUITY_MARGIN = 0.03
    private const val PREFIX_SIMILARITY = 0.90

    private val json = Json { ignoreUnknownKeys = true }
    private val ARTICLE = Regex("^(the|a|an) ")

    /** Lower case, no accents, no punctuation, "&" → "and", single spaces. */
    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("['’`]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun core(s: String) = normalize(s).replaceFirst(ARTICLE, "")

    /** 1.0 = the same title once normalised; 0 = nothing alike. */
    fun similarity(a: String, b: String): Double {
        val x = core(a)
        val y = core(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    fun titleSimilarity(name: ReleaseName, c: MatchCandidate): Double {
        val plain = listOfNotNull(c.title, c.originalTitle).maxOf { similarity(name.title, it) }
        val n = core(name.title)
        val prefix = name.year != null && c.year == name.year && n.contains(' ') &&
            listOfNotNull(c.title, c.originalTitle).any { core(it).startsWith("$n ") }
        return if (prefix) maxOf(plain, PREFIX_SIMILARITY) else plain
    }

    /** The candidate's score, or null when it's ruled out (title too different, years too far apart). */
    fun score(name: ReleaseName, c: MatchCandidate): Double? {
        val sim = titleSimilarity(name, c)
        if (sim < MIN_SIMILARITY) return null
        val year = when {
            name.year == null || c.year == null -> 0.4
            name.year == c.year -> 1.0
            kotlin.math.abs(name.year - c.year) == 1 -> 0.6
            else -> return null
        }
        val pop = (kotlin.math.log10(1 + c.popularity.coerceAtLeast(0.0)) / 2.0).coerceIn(0.0, 1.0)
        val country = when {
            name.country == null || c.countries.isEmpty() -> 0.0
            c.countries.any { it.equals(name.country, true) || (name.country == "UK" && it == "GB") } -> 0.05
            else -> -0.10
        }
        return 0.70 * sim + 0.20 * year + 0.10 * pop + country
    }

    fun choose(name: ReleaseName, candidates: List<MatchCandidate>): MatchOutcome {
        val scored = candidates.distinctBy { it.type to it.id }
            .mapNotNull { c -> score(name, c)?.let { c to it } }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return MatchOutcome(null, null, if (candidates.isEmpty()) "noResults" else "noSimilarTitle")
        if (best.second < MIN_SCORE) return MatchOutcome(null, best.second, "lowScore")
        val rival = scored.getOrNull(1)
        if (rival != null && best.second - rival.second < AMBIGUITY_MARGIN) return MatchOutcome(null, best.second, "ambiguous")
        return MatchOutcome(best.first, best.second, "matched")
    }

    /** A `search/movie` or `search/tv` answer as candidates (people and broken items dropped). */
    fun parseSearch(body: String, type: MediaType): List<MatchCandidate> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val movie = type == MediaType.MOVIE
            val title = (if (movie) o.str("title") else o.str("name")) ?: return@mapNotNull null
            MatchCandidate(
                type = type,
                id = id,
                title = title,
                originalTitle = if (movie) o.str("original_title") else o.str("original_name"),
                year = (if (movie) o.str("release_date") else o.str("first_air_date"))?.take(4)?.toIntOrNull(),
                popularity = (o["popularity"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                countries = (o["origin_country"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                posterPath = o.str("poster_path"),
                backdropPath = o.str("backdrop_path"),
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
