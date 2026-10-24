package com.kirtanya.reel.core.tmdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A TMDB user review: [content] is already plain text (no markdown / HTML). [rating] is 0–10 or null. */
data class Review(val author: String, val rating: Double?, val content: String, val createdAt: String?) {
    /** The avatar letter: first letter or digit of the name, upper case; "?" for none. */
    val initial: String get() = author.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    /** "★ 8" / "★ 7.5" */
    val ratingLabel: String? get() = rating?.let { "★ " + if (it % 1.0 == 0.0) it.toInt().toString() else String.format(java.util.Locale.US, "%.1f", it) }
}

/** The first page of a title's reviews and how many there are in all ("Reviews · 12"). */
data class Reviews(val items: List<Review>, val total: Int) {
    companion object {
        val NONE = Reviews(emptyList(), 0)
    }
}

object ReviewParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** `/movie/{id}/reviews` or `/tv/{id}/reviews`. Reviews with no text are dropped. */
    fun parse(body: String): Reviews {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return Reviews.NONE
        val items = (o["results"] as? JsonArray).orEmpty().mapNotNull { r ->
            val ro = r as? JsonObject ?: return@mapNotNull null
            val details = ro["author_details"] as? JsonObject
            val author = ro.str("author")?.trim()?.ifEmpty { null } ?: details?.str("username")?.trim()?.ifEmpty { null } ?: "Anonymous"
            val text = ReviewText.plain(ro.str("content").orEmpty())
            if (text.isBlank()) return@mapNotNull null
            val rating = (details?.get("rating") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.takeIf { it in 0.0..10.0 }
            Review(author, rating, text, ro.str("created_at"))
        }
        val total = (o["total_results"] as? JsonPrimitive)?.intOrNull ?: items.size
        return Reviews(items, maxOf(total, items.size))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * TMDB review text arrives as markdown with stray HTML (`<em>`, `<br />`, `&amp;`). The app shows plain
 * text, so formatting marks go and links keep their words.
 */
object ReviewText {
    private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")

    fun plain(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        // HTML: line breaks and paragraphs become newlines; every other tag goes.
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p\\s*>"), "\n\n")
        s = s.replace(Regex("<[^>]{1,200}>"), "")
        s = s.replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        // Markdown: images go, links keep their text, emphasis / code marks go.
        s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        s = s.replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        s = s.replace(Regex("(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])"), "$1")
        s = s.replace(Regex("~~(.+?)~~"), "$1")
        s = s.replace(Regex("`+([^`]+)`+"), "$1")
        s = s.lines().joinToString("\n") { line ->
            line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
                .replace(Regex("^\\s{0,3}>\\s?"), "")
                .replace(Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$"), "") // --- rules
                .trimEnd()
        }
        return s.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

/** The quiet line above a title's name: "FILM · 2024 · 2h 46m · UA · SCIENCE FICTION, ADVENTURE". */
object TitleEyebrow {
    fun parts(d: TitleDetails, genres: Int = 2): List<String> = listOfNotNull(
        if (d.summary.ref.type == MediaType.TV) "SERIES" else "FILM",
        if (d.summary.ref.type == MediaType.TV) years(d) else d.summary.year?.toString(),
        if (d.summary.ref.type == MediaType.TV) d.seasons.count { it.number > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
        else d.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
        d.certification?.ifBlank { null },
        d.genres.take(genres).joinToString(", ").uppercase().ifEmpty { null },
    )

    /**
     * A show's run: "2016–2023" once it has ended (or was cancelled), "2016–present" while it's on,
     * just "2016" when it ended the year it started or nothing more is known.
     */
    fun years(d: TitleDetails): String? {
        val start = d.summary.year ?: return null
        val status = d.status?.lowercase().orEmpty()
        val ended = status == "ended" || status == "canceled" || status == "cancelled"
        val last = d.lastAiredEpisode?.airDate?.take(4)?.toIntOrNull()
        return when {
            ended && last != null && last > start -> "$start–$last"
            ended -> "$start"
            status.isNotEmpty() -> "$start–present"
            else -> "$start"
        }
    }
}

/** An episode card's lines: "E9 · Greatness Achieved" and "52m · 12 Mar 2016". */
object EpisodeFacts {
    private val date = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)

    fun title(e: Episode): String = "E${e.number} · ${e.name}"

    fun facts(e: Episode): String = listOfNotNull(
        e.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
        e.airDate?.let { d -> runCatching { java.time.LocalDate.parse(d).format(date) }.getOrNull() ?: d.take(4).takeIf { it.length == 4 } },
    ).joinToString(" · ")
}
