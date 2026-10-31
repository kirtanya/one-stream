package com.kirtanya.reel.core.artmatch

/**
 * What a torrent / file name says about the film or episode in it: "Game.of.Thrones.S01E01.1080p…" →
 * title "Game of Thrones", season 1, episode 1. Used to find the title on TMDB for files played
 * without one (docs/DECISIONS.md 2026-10-24 "Artwork for raw files").
 */
data class ReleaseName(
    /** Clean title as written in the name ("Tears of Steel"); TMDB's own name replaces it once matched. */
    val title: String,
    val year: Int? = null,
    /** Set for anything that names a season (an episode or a season pack): it's a show. */
    val season: Int? = null,
    val episode: Int? = null,
    /** "The.Office.US.S02E03" → "US": tells same-named shows apart. */
    val country: String? = null,
) {
    val isTv: Boolean get() = season != null

    /** One TMDB lookup per title: episodes of the same show share it (it's also the cache key). */
    val lookupKey: String
        get() = listOf(if (isTv) "tv" else "movie", TitleMatcher.normalize(title), year?.toString().orEmpty(), country.orEmpty()).joinToString("|")
}

object ReleaseNames {
    private val VIDEO_EXT = Regex(
        """(?i)\.(mkv|mp4|avi|m4v|mov|wmv|ts|m2ts|webm|flv|mpe?g|vob|3gp|ogv|torrent)$""",
    )

    // A leading [Group] / (Site) / 【…】 tag, then any separators.
    private val LEADING_TAG = Regex("""^\s*(\[[^\]]*]|\([^)]*\)|【[^】]*】)\s*[-–_.]*\s*""")

    // "www.Site.xyz - Title…" (needs www.), or "Site.org - Title…" (needs the spaced dash: dotted names never have one).
    private val LEADING_SITE = Regex(
        """(?i)^\s*(?:www\.[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,6}\s*[-–_]*|[a-z0-9-]+\.(?:com|org|net|xyz|io|to|mx|am|lol|cc|me|in|co|tv|ws|info|biz|club|site|se|nz|li|ru|pw|ag|is|bz|vip|one|cx)\s+[-–]\s*)\s*""",
    )

    private val SXXEYY = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?![0-9])""")
    private val SEASON_EPISODE_WORDS = Regex("""(?i)(?<![a-z0-9])season[ ._-]?(\d{1,2})[ ._,-]*(?:episode|ep)[ ._-]?(\d{1,3})(?![0-9])""")
    private val NXNN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9a-z])""")
    private val SEASON_ONLY = Regex("""(?i)(?<![a-z0-9])(?:s(\d{1,2})|season[ ._-]?(\d{1,2}))(?![0-9a-z])""")

    // "(2018)" / "[2018]" → a bare year; "(US)" → a bare country, so they survive the cut at the first bracket.
    private val BRACKET_YEAR = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val BRACKET_COUNTRY = Regex("""\(\s*(US|UK|AU|NZ|CA)\s*\)""")
    private val HEX_ONLY = Regex("""(?i)^[0-9a-f]{8}$|^[0-9a-f]{40}$|^[0-9a-f]{64}$""")

    private val COUNTRIES = setOf("US", "UK", "AU", "NZ", "CA")

    /** Tokens that end the title wherever they appear (quality, source, codec, audio, release flags, groups). */
    private val STRONG_JUNK = setOf(
        "4k", "8k", "uhd", "fhd", "bluray", "blu-ray", "brrip", "bdrip", "bdremux", "remux", "webrip", "web-rip", "web-dl", "webdl",
        "hdtv", "pdtv", "hdrip", "dvdrip", "dvdscr", "dvd", "dvd5", "dvd9", "hdcam", "camrip", "telesync", "hdts", "hdtc", "predvd",
        "xvid", "divx", "hevc", "avc", "av1", "x264", "x265", "h264", "h265", "10bit", "8bit", "hdr", "hdr10", "hdr10+", "dv", "dovi", "sdr",
        "aac", "aac2", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "ddp", "flac", "mp3", "opus",
        "proper", "repack", "rerip", "internal", "limited", "unrated", "remastered", "imax", "hybrid", "multi", "dual", "dubbed",
        "subbed", "esub", "esubs", "msubs", "nf", "amzn", "dsnp", "hmax", "atvp", "criterion", "yts", "yify", "rarbg", "galaxyrg", "tgx",
        "rip", "hq", "dl",
    )
    private val STRONG_JUNK_PATTERN = Regex("""(?i)^(\d{3,4}[pi]|[xh]\.?26[45]|ddp?\d.*|dd\+?\d.*|aac\d.*|\d{1,2}ch)$""")

    /** Trimmed off the end of a title only when no year separates it (they can be real words: "Charlotte's Web"). */
    private val WEAK_JUNK = setOf("web", "hd", "ts", "cam", "tc", "scr", "extended", "uncut", "complete", "theatrical", "edition", "cut", "directors")

    /**
     * The release name in [raw] (a torrent name, file name, path or link). Null when there's no title
     * to search for (an info-hash stand-in, an empty name). [thisYear] bounds what counts as a year,
     * so "Blade Runner 2049" stays a title.
     */
    fun parse(raw: String?, thisYear: Int = java.time.Year.now().value): ReleaseName? {
        var name = raw?.trim().orEmpty()
        name = name.substringAfterLast('/').substringAfterLast('\\')
        if ('%' in name) name = runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        name = name.replace(VIDEO_EXT, "").trim()
        repeat(3) {
            name = name.replaceFirst(LEADING_TAG, "").replaceFirst(LEADING_SITE, "")
        }
        if (name.isBlank() || HEX_ONLY.matches(name)) return null

        // The episode marker ends the title; the earliest form wins.
        var season: Int? = null
        var episode: Int? = null
        var head = name
        val marker = listOfNotNull(SXXEYY.find(name), SEASON_EPISODE_WORDS.find(name), NXNN.find(name)).minByOrNull { it.range.first }
        if (marker != null) {
            season = marker.groupValues[1].toInt()
            episode = marker.groupValues[2].toInt()
            head = name.substring(0, marker.range.first)
        } else {
            SEASON_ONLY.find(name)?.takeIf { it.range.first > 0 }?.let { m ->
                season = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
                head = name.substring(0, m.range.first)
            }
        }

        head = head.replace(BRACKET_YEAR, " $1 ").replace(BRACKET_COUNTRY, " $1 ")
        // Whatever bracket is left is a tag ("[BluRay]", "(Extended)"): the title ends there.
        head.indexOfFirst { it == '[' || it == '(' || it == '{' }.takeIf { it > 0 }?.let { head = head.substring(0, it) }

        var tokens = head.replace('.', ' ').replace('_', ' ').replace(" - ", " ").split(Regex("\\s+"))
            .map { it.trim('-', '–', ' ', ',', ':', '+') }
            .filter { it.isNotEmpty() }
        tokens = joinInitials(tokens)
        if (tokens.isEmpty()) return null

        val cut = (1 until tokens.size).firstOrNull { isStrongJunk(tokens[it]) } ?: tokens.size
        val yearIdx = (1 until cut).lastOrNull { i -> tokens[i].toIntOrNull()?.let { it in 1900..(thisYear + 1) } == true }
        val year = yearIdx?.let { tokens[it].toInt() }
        var title = tokens.subList(0, yearIdx ?: cut).toMutableList()
        if (yearIdx == null) while (title.size > 1 && title.last().lowercase() in WEAK_JUNK) title.removeAt(title.lastIndex)
        var country: String? = null
        if (title.size > 1 && title.last() in COUNTRIES) country = title.removeAt(title.lastIndex)
        // "Office, The" → "The Office"
        val text = title.joinToString(" ").let { t ->
            Regex("""(?i)^(.+?),?\s+(the|a|an)$""").find(t)?.takeIf { head.contains(", ") }?.let { "${it.groupValues[2]} ${it.groupValues[1]}" } ?: t
        }.trim()
        if (text.isEmpty() || HEX_ONLY.matches(text)) return null
        return ReleaseName(text, year, season, episode, country)
    }

    private fun isStrongJunk(token: String): Boolean = token.lowercase() in STRONG_JUNK || STRONG_JUNK_PATTERN.matches(token)

    /** "S W A T" (from "S.W.A.T.") → "SWAT"; a lone letter ("A Quiet Place") stays. */
    private fun joinInitials(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var run = StringBuilder()
        var runLen = 0
        fun flush() {
            if (runLen > 0) out += run.toString()
            run = StringBuilder(); runLen = 0
        }
        for (t in tokens) {
            if (t.length == 1 && t[0].isLetter()) { run.append(t); runLen++ } else { flush(); out += t }
        }
        flush()
        return out
    }
}
