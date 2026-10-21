package com.kirtanya.reel.core.sources

/**
 * Tells the film or episode itself apart from trailers, teasers, samples and other extras that
 * addons sometimes return as sources. Extras are never recommended or auto-played; the Sources
 * page lists them separately.
 */
object SourceExtras {
    /** Label → pattern, most specific first. Matched on the release text with separators turned into spaces. */
    private val kinds = listOf(
        "Trailer" to Regex("""\b(official\s+)?(trailers?|tráiler|bande[\s-]annonce)\b"""),
        "Teaser" to Regex("""\bteasers?\b"""),
        "TV spot" to Regex("""\btv\s+spots?\b"""),
        "Sneak peek" to Regex("""\b(sneak\s+peek|first\s+look)\b"""),
        "Behind the scenes" to Regex("""\b(behind\s+the\s+scenes|making\s+of|bts)\b"""),
        "Featurette" to Regex("""\bfeaturettes?\b"""),
        "Deleted scenes" to Regex("""\b(deleted|extended|alternate)\s+scenes?\b"""),
        "Bloopers" to Regex("""\b(bloopers?|gag\s+reel|outtakes?)\b"""),
        "Interview" to Regex("""\binterviews?\b"""),
        "Promo" to Regex("""\bpromos?\b"""),
        "Clip" to Regex("""\bclips?\b"""),
        "Sample" to Regex("""\bsample\b"""),
        "Extras" to Regex("""\b(bonus\s+(features?|content|material)|special\s+features?|extras)\b"""),
    )

    /**
     * The kind of extra [s] is (e.g. "Trailer"), or null for the real thing. Words that are part
     * of the title being searched for don't count ("Trailer Park Boys", "Extras").
     */
    fun classify(s: Source, req: SourceRequest): String? {
        val text = normalise(listOfNotNull(s.title, s.filename).joinToString(" "))
            .replace(normalise(req.title), " ")
        return kinds.firstOrNull { (_, re) -> re.containsMatchIn(text) }?.first
            // YouTube links are trailers or clips, whatever they're called.
            ?: if (isYouTube(s.url)) "Video" else null
    }

    /** [s] with [Source.extra] filled in for [req]. */
    fun mark(s: Source, req: SourceRequest): Source = s.copy(extra = classify(s, req))

    private fun isYouTube(url: String?) = url != null &&
        Regex("""^https?://(www\.|m\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun normalise(t: String) = " " + t.lowercase().replace(Regex("""[._\-\[\]()]+"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
}
