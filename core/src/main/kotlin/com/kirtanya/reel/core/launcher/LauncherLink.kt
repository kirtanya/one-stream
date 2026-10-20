package com.kirtanya.reel.core.launcher

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** `onestream://…` deep links (docs/LAUNCHER_API.md); MainActivity acts on what [parse] returns. */
sealed interface LauncherLink {
    data class Title(val ref: TitleRef) : LauncherLink
    data class Imdb(val imdb: String, val type: MediaType) : LauncherLink
    data class Resume(val key: String) : LauncherLink
    data class Search(val query: String) : LauncherLink

    companion object {
        const val SCHEME = "onestream"

        fun title(ref: TitleRef) = "$SCHEME://title/${ref.type.path}/${ref.id}"
        fun resume(key: String) = "$SCHEME://resume/$key"
        fun search(q: String) = "$SCHEME://search?q=" + URLEncoder.encode(q, Charsets.UTF_8).replace("+", "%20")

        /** Null for anything that isn't a well-formed One Stream link. */
        fun parse(link: String?): LauncherLink? {
            val uri = runCatching { URI(link?.trim() ?: return null) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val segments = uri.rawPath.orEmpty().split("/").filter { it.isNotEmpty() }.map(::decode)
            val params = query(uri.rawQuery)
            return when (uri.host?.lowercase()) {
                "title" -> {
                    val type = LauncherApi.mediaType(segments.getOrNull(0)) ?: return null
                    val id = segments.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    if (segments.size != 2) null else Title(TitleRef(type, id))
                }
                "imdb" -> {
                    val id = segments.singleOrNull()?.takeIf(LauncherApi::isImdbId) ?: return null
                    // A launcher catalog row might lack its type: films are the safer guess (TMDB find tries both anyway).
                    Imdb(id, LauncherApi.mediaType(params["type"]) ?: MediaType.MOVIE)
                }
                "resume" -> segments.singleOrNull()?.takeIf { LauncherApi.titleOf(it) != null }?.let(::Resume)
                "search" -> params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::Search)
                else -> null
            }
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

        private fun query(raw: String?): Map<String, String> =
            raw.orEmpty().split("&").filter { it.isNotEmpty() }.associate { pair ->
                val k = pair.substringBefore("=")
                val v = pair.substringAfter("=", "")
                runCatching { URLDecoder.decode(k, Charsets.UTF_8) }.getOrDefault(k) to
                    runCatching { URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v)
            }
    }
}
