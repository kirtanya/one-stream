package com.kirtanya.reel.core.sources

import kotlinx.serialization.Serializable

/** Does the user's debrid service already hold a stream's file (plays at once) or does it still have to fetch it? */
@Serializable
enum class CacheState { CACHED, UNCACHED, UNKNOWN }

/**
 * Reads the cached / not-cached markers that debrid addons (Torrentio, Comet, MediaFusion, Debridio…) put in a
 * Stremio stream's name and description. An uncached link usually plays the addon's short "downloading to your
 * provider" clip instead of the film (PlaceholderCheck catches that in the player); here it only ranks the row
 * lower and tags it "Not cached".
 *
 * Markers: ⚡, "RD+", "[AD+]", "Instant", "Cached" = cached; ⏳, ⬇️, "[RD download]", "Download", "Uncached",
 * "Not cached" = uncached. Both kinds at once → unknown.
 */
object DebridCache {
    const val NOT_CACHED_TAG = "Not cached"

    /** Debrid service codes the addons print next to "+": Real-Debrid, AllDebrid, Premiumize, Debrid-Link, TorBox… */
    private const val SERVICES = "RD|AD|PM|DL|TB|ED|OC|PK|DB|EN|PP"

    private val cachedMarks = listOf(
        Regex("⚡"), // ⚡ (with or without the emoji variation selector)
        Regex("\\[[A-Z]{2,3}\\+]"),
        // "RD+" without brackets; "DD+" (Dolby Digital Plus) and "HDR10+" are not services, so they never match.
        Regex("(?<![A-Za-z0-9])(?:$SERVICES)\\+"),
        Regex("(?i)(?<![a-z])cached(?![a-z])"),
    )

    /** Removed from the text once found, so "Not cached" doesn't also read as "cached". */
    private val uncachedMarks = listOf(
        Regex("[⏳⬇]"), // ⏳ ⬇
        Regex("(?i)(?<![a-z])(?:un[ -]?cached|not[ -]cached)(?![a-z])"),
        // "[RD download]", "Download to debrid"; "Downloaded" and "WEB-DL" are something else.
        Regex("(?i)(?<![a-z-])download(?![a-z-])"),
    )

    /** "Instant" only counts in the addon's stream name: in a release name it's a film ("Instant Family"). */
    private val instant = Regex("(?i)(?<![a-z])instant(?![a-z])")

    /** [name]: the stream's short name (the addon's label column); [description]: its title / description text. */
    fun classify(name: String?, description: String?): CacheState {
        var text = listOfNotNull(name, description).joinToString("\n")
        var uncached = false
        for (r in uncachedMarks) {
            if (r.containsMatchIn(text)) {
                uncached = true
                text = r.replace(text, " ")
            }
        }
        val cached = cachedMarks.any { it.containsMatchIn(text) } || (name != null && instant.containsMatchIn(name))
        return when {
            cached && uncached -> CacheState.UNKNOWN
            cached -> CacheState.CACHED
            uncached -> CacheState.UNCACHED
            else -> CacheState.UNKNOWN
        }
    }

    /**
     * A source's state: only direct links have a debrid cache. Stremio sources carry it from parsing (name +
     * description, [Source.cache]); others (plugins, older saved sources) are read from their title.
     */
    fun of(s: Source): CacheState {
        if (s.kind != SourceKind.DIRECT) return CacheState.UNKNOWN
        return s.cache ?: classify(null, s.title)
    }

    /** The row tag for an uncached link, else null. */
    fun tag(s: Source): String? = NOT_CACHED_TAG.takeIf { of(s) == CacheState.UNCACHED }
}
