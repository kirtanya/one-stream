package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.Genre
import com.kirtanya.reel.core.tmdb.MediaType

/**
 * One tile of the TV's Categories page: a genre (TMDB keeps separate id lists for films and shows, so one tile can
 * carry both ids) or an original language. Opening it lists films and shows together.
 */
data class Category(
    val label: String,
    val movieGenre: Int? = null,
    val tvGenre: Int? = null,
    /** Original language, ISO 639-1 ("hi", "ko"). */
    val language: String? = null,
) {
    val isLanguage: Boolean get() = language != null

    /** Which kinds of title the category can have: only these are asked of TMDB. */
    val types: List<MediaType>
        get() = if (isLanguage) listOf(MediaType.MOVIE, MediaType.TV) else listOfNotNull(
            MediaType.MOVIE.takeIf { movieGenre != null },
            MediaType.TV.takeIf { tvGenre != null },
        )

    /** Saved-state key of its page. */
    val key: String get() = if (language != null) "lang:$language" else "genre:${movieGenre ?: "-"}:${tvGenre ?: "-"}"

    /** What a tile says under the name. */
    val caption: String
        get() = when (types) {
            listOf(MediaType.MOVIE) -> "Films"
            listOf(MediaType.TV) -> "Shows"
            else -> "Films and shows"
        }

    /** Genre id for [type], null when this category has none for it (or is a language). */
    fun genreFor(type: MediaType): Int? = if (type == MediaType.MOVIE) movieGenre else tvGenre
}

/** The Categories page: genres merged from TMDB's film and show lists, and a fixed set of languages. */
object Categories {
    /** Original languages offered, in this order. */
    val LANGUAGES: List<Category> = listOf(
        "Hindi" to "hi", "English" to "en", "Tamil" to "ta", "Telugu" to "te", "Malayalam" to "ml", "Korean" to "ko", "Japanese" to "ja",
    ).map { (name, code) -> Category(name, language = code) }

    /** First on the page, in this order; every other genre follows alphabetically. */
    val GENRE_ORDER = listOf("Action", "Comedy", "Drama", "Thriller", "Romance", "Horror", "Sci-Fi", "Animation", "Crime", "Family", "Documentary")

    /** Genres with no counterpart worth a tile (news, talk shows and soaps, the made-for-TV film tag). */
    private val SKIPPED = setOf("tv movie", "news", "talk", "soap")

    /** One name for the same idea: TMDB's film and show lists spell several genres differently. */
    private val ALIASES = mapOf(
        "action & adventure" to "Action",
        "science fiction" to "Sci-Fi",
        "sci-fi & fantasy" to "Sci-Fi",
        "war & politics" to "War",
    )

    private fun canonical(name: String): String = ALIASES[name.trim().lowercase()] ?: name.trim()

    /**
     * One tile per idea: "Action" (films) and "Action & Adventure" (shows) become Action with both ids; a genre only
     * one list has (Horror, Kids) keeps just that id. Curated names first ([GENRE_ORDER]), the rest A to Z.
     */
    fun mergeGenres(movie: List<Genre>, tv: List<Genre>): List<Category> {
        val byName = linkedMapOf<String, Category>()
        fun add(g: Genre, type: MediaType) {
            if (g.name.trim().lowercase() in SKIPPED) return
            val name = canonical(g.name)
            val old = byName[name] ?: Category(name)
            // The first id wins if a list repeats a name (it shouldn't).
            byName[name] = if (type == MediaType.MOVIE) old.copy(movieGenre = old.movieGenre ?: g.id) else old.copy(tvGenre = old.tvGenre ?: g.id)
        }
        movie.forEach { add(it, MediaType.MOVIE) }
        tv.forEach { add(it, MediaType.TV) }
        val rank = GENRE_ORDER.withIndex().associate { (i, n) -> n to i }
        return byName.values.sortedWith(compareBy<Category> { rank[it.label] ?: Int.MAX_VALUE }.thenBy { it.label })
    }

    /** TMDB's own ids (stable for years): what the page shows when the lists can't be fetched. */
    val FALLBACK_MOVIE_GENRES = listOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        53 to "Thriller", 10752 to "War", 37 to "Western",
    ).map { (id, name) -> Genre(id, name) }
    val FALLBACK_TV_GENRES = listOf(
        10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        10762 to "Kids", 9648 to "Mystery", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10768 to "War & Politics", 37 to "Western",
    ).map { (id, name) -> Genre(id, name) }

    val FALLBACK_GENRES: List<Category> by lazy { mergeGenres(FALLBACK_MOVIE_GENRES, FALLBACK_TV_GENRES) }
}
