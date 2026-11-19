package com.kirtanya.reel.core.magnet

/**
 * Merges a torrent's own trackers with [DEFAULT_TRACKERS].
 *
 * Why not a plain `+ distinct()`: addon/magnet lists often carry 30-60 trackers, many of them the same host spelled
 * twice (trailing slash, upper-case host, `/announce` vs none) or schemes libtorrent can't speak (ws/wss). Every extra
 * entry is a DNS lookup, a connect and an announce on a 32-bit TV with one slow CPU, and with announce-to-all they all
 * fire at once. Torrent trackers keep priority (they know their swarm), the bundled list is always kept, the rest is capped.
 */
object TrackerMerge {
    const val MAX_TRACKERS = 32
    private val SCHEMES = setOf("udp", "http", "https")

    /** Lower-cased scheme and host, trailing slash dropped; null for anything libtorrent can't announce to. */
    fun normalize(url: String): String? {
        val t = url.trim()
        val sep = t.indexOf("://")
        if (sep <= 0) return null
        val scheme = t.substring(0, sep).lowercase()
        if (scheme !in SCHEMES) return null
        val rest = t.substring(sep + 3)
        val slash = rest.indexOf('/')
        val authority = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
        if (authority.isEmpty() || authority.startsWith(":")) return null
        val path = if (slash < 0) "" else rest.substring(slash)
        return "$scheme://$authority" + path.trimEnd('/')
    }

    /**
     * @param own trackers already on the torrent/magnet, in their order
     * @param defaults the bundled list; pass empty for private torrents (never leak their hash to public trackers)
     */
    fun merge(own: List<String>, defaults: List<String> = DEFAULT_TRACKERS, max: Int = MAX_TRACKERS): List<String> {
        val seen = HashSet<String>()
        val defs = LinkedHashMap<String, String>()
        for (d in defaults) normalize(d)?.let { k -> if (k !in defs) defs[k] = d.trim() }
        val out = ArrayList<String>()
        val ownRoom = (max - defs.size).coerceAtLeast(0)
        for (u in own) {
            val k = normalize(u) ?: continue
            if (!seen.add(k)) continue
            if (out.size < ownRoom || k in defs) out += u.trim()
        }
        for ((k, d) in defs) if (seen.add(k)) out += d
        return out
    }
}
