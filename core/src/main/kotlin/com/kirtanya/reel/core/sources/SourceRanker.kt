package com.kirtanya.reel.core.sources

/** What this device prefers. TVs set [maxBytes] from free storage. */
data class RankPrefs(
    val preferredResolution: Int = 1080,
    /** Torrents bigger than this can't be streamed here (TV storage). Null = no limit. */
    val maxBytes: Long? = null,
    val avoidDolbyVision: Boolean = false,
    val avoidAv1: Boolean = false,
    /** The user's cap for this device (e.g. 1080 on the TV): anything sharper ranks below everything else. Null = no cap. */
    val maxResolution: Int? = null,
    /** Leave out cam / telesync / screener rips entirely. */
    val neverCam: Boolean = false,
    /** Provider labels (SourceFacts.providerLabel) the user prefers: ranked first among equals. */
    val pinned: Set<String> = emptySet(),
    /** Provider labels the user never wants: left out. */
    val blocked: Set<String> = emptySet(),
    /** Spoken language to put first (SourceFacts.languages names, e.g. "English"). Null = no preference. */
    val language: String? = "English",
)

/**
 * Orders sources best-first: playable here → right quality → healthy torrent → sensible size.
 * Direct links don't depend on peers, so they get a steady health score.
 */
object SourceRanker {
    fun rank(sources: List<Source>, prefs: RankPrefs = RankPrefs()): List<Source> =
        sources.filter { allowed(it, prefs) }.sortedWith(
            compareByDescending<Source> { score(it, prefs) }
                // Same score: a smaller direct file starts sooner (a 1.7 GB MP4 beats a 10.7 GB MKV).
                .thenBy { if (it.kind == SourceKind.DIRECT) it.sizeBytes ?: Long.MAX_VALUE else 0L }
                .thenBy { it.title },
        )

    /** What the user's source preferences leave out (blocked providers, cam rips when "never CAM"). */
    fun allowed(s: Source, prefs: RankPrefs): Boolean =
        (prefs.blocked.isEmpty() || SourceFacts.providerLabel(s.provider) !in prefs.blocked) &&
            !(prefs.neverCam && s.quality.lowQualityRip)

    fun fits(source: Source, prefs: RankPrefs): Boolean =
        source.kind != SourceKind.TORRENT || prefs.maxBytes == null || (source.sizeBytes ?: 0) <= prefs.maxBytes

    /**
     * The user hears the wrong language too often (dubbed releases ranked first). A release that names
     * the wanted language wins among equals; "Multi" / "Dual audio" usually carry it too; one that names
     * only other languages drops below the rest. Unnamed (most English releases) stays neutral.
     */
    fun languageScore(s: Source, language: String?): Double {
        if (language == null) return 0.0
        val langs = SourceFacts.languages(s)
        return when {
            langs.isEmpty() -> 0.0
            language in langs -> 30.0
            langs.all { it == "Multi" || it == "Dual audio" } -> 10.0
            else -> -80.0
        }
    }

    fun score(s: Source, prefs: RankPrefs): Double {
        // Trailers and other extras go last: below everything, services included.
        if (s.isExtra) return -2000.0
        if (s.kind == SourceKind.EXTERNAL) return -1000.0
        var score = 0.0
        if (!fits(s, prefs)) score -= 500
        if (s.quality.lowQualityRip) score -= 300
        if (prefs.avoidDolbyVision && s.quality.dolbyVision && !s.quality.hdr) score -= 200
        if (prefs.avoidDolbyVision && s.quality.dolbyVision) score -= 80
        if (prefs.avoidAv1 && s.quality.av1) score -= 200
        // A debrid link the addon marks not cached usually plays a "downloading" clip first: below cached links of
        // the same resolution, still above the next lower resolution's cached link (a step is worth more than 50).
        if (DebridCache.of(s) == CacheState.UNCACHED) score -= UNCACHED_PENALTY

        // Above the user's cap: still listed, never first.
        val r = s.quality.resolution
        if (prefs.maxResolution != null && r != null && r > prefs.maxResolution) score -= 400
        if (prefs.pinned.isNotEmpty() && SourceFacts.providerLabel(s.provider) in prefs.pinned) score += 120
        score += languageScore(s, prefs.language)

        // Resolution: exact match best, one step either side next, unknown in the middle.
        score += when {
            r == null -> 40.0
            r == prefs.preferredResolution -> 100.0
            r > prefs.preferredResolution -> 70.0 - (r - prefs.preferredResolution) / 100.0
            else -> 60.0 - (prefs.preferredResolution - r) / 20.0
        }

        // Health: torrents by seeders (log scale), direct links steady.
        score += when (s.kind) {
            SourceKind.TORRENT -> when (val seeds = s.seeders) {
                null -> 20.0
                0 -> -150.0
                else -> 20.0 * kotlin.math.ln(1.0 + seeds)
            }
            else -> 60.0
        }
        return score
    }

    /** See [score]: uncached debrid links (DebridCache). */
    const val UNCACHED_PENALTY = 50.0
}
