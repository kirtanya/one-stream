package com.kirtanya.reel.core.sources

/** The four quality chips on the Sources page. Unknown resolution counts as SD (its badge reads "SD?"). */
enum class QualityBucket(val label: String) {
    UHD("4K"), FHD("1080p"), HD("720p"), SD("SD");

    companion object {
        fun of(q: Quality): QualityBucket {
            val r = q.resolution ?: return SD
            return when {
                r >= 2160 -> UHD
                r >= 1080 -> FHD
                r >= 720 -> HD
                else -> SD
            }
        }
    }
}

/** How likely a torrent is to start quickly, from its seeder count. Direct links and services have none. */
enum class SeedHealth(val label: String, val bars: Int) {
    STRONG("Strong", 3), OK("OK", 2), WEAK("Weak", 1), NONE("No seeds", 0), UNKNOWN("Seeds unknown", 0);

    companion object {
        /**
         * 50+ seeders start at full speed in practice; 10–49 usually do; 1–9 often crawl.
         * Null for non-torrents (no peers to count).
         */
        fun of(s: Source): SeedHealth? {
            if (s.kind != SourceKind.TORRENT) return null
            val seeds = s.seeders ?: return UNKNOWN
            return when {
                seeds >= 50 -> STRONG
                seeds >= 10 -> OK
                seeds >= 1 -> WEAK
                else -> NONE
            }
        }
    }
}

/** Where the bytes come from, as the user sees it (the "type" filter). */
enum class SourceType(val label: String) {
    TORRENT("Torrent"), DIRECT("Direct"), SERVICE("Service");

    companion object {
        fun of(s: Source) = when (s.kind) {
            SourceKind.TORRENT -> TORRENT
            SourceKind.DIRECT -> DIRECT
            SourceKind.EXTERNAL -> SERVICE
        }
    }
}

/**
 * What a release name says beyond resolution: picture (Dolby Vision, HDR), codec, audio, rip type,
 * and spoken languages. Only the release text is read, never the provider name: an addon called
 * "MediaFusion 🇮🇳 (Hindi, Tamil)" lists what it covers, not what this file contains.
 */
object SourceFacts {
    private data class Tag(val label: String, val regex: Regex)

    private fun tag(label: String, pattern: String) = Tag(label, Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z0-9])"))

    /** In display order: picture first, then codec, audio, rip. The first match per group wins where noted. */
    private val picture = listOf(
        tag("Dolby Vision", """dv|dovi|dolby[ -]?vision"""),
        tag("HDR10+", """hdr10\+|hdr10[ -]?plus"""),
        tag("HDR", """hdr10|hdr|hlg"""),
    )
    private val tags = listOf(
        tag("10-bit", """10[ -]?bit|hi10p?"""),
        tag("HEVC", """hevc|x265|h ?265"""),
        tag("AV1", """av1"""),
        tag("Atmos", """atmos"""),
        tag("TrueHD", """truehd"""),
        tag("DTS-HD", """dts[ -]?hd(?:[ -]?ma)?|dts[ -:]?x"""),
        tag("DTS", """dts"""),
        tag("DD+", """ddp|dd\+|e[ -]?ac[ -]?3"""),
        tag("7.1", """7 1"""),
        tag("5.1", """5 1"""),
        tag("IMAX", """imax"""),
        tag("REMUX", """remux"""),
        tag("BluRay", """blu[ -]?ray|bdrip|brrip"""),
        tag("WEB-DL", """web[ -]?dl"""),
        tag("WEBRip", """web[ -]?rip"""),
        tag("CAM", """cam|camrip|hdcam|ts|telesync|hdts|tc|telecine"""),
    )

    /** Tags for a release, most important first, without duplicates ("DTS-HD" hides "DTS", "HDR10+" hides "HDR"). */
    fun tags(s: Source): List<String> = tags(s.title, s.filename)

    fun tags(vararg texts: String?): List<String> {
        val text = normalise(texts)
        val out = ArrayList<String>()
        // Dolby Vision releases usually carry an HDR10 base layer too: say both. HDR10+ hides plain HDR.
        val (dv, hdrPlus, hdr) = picture.map { it.regex.containsMatchIn(text) }
        if (dv) out += "Dolby Vision"
        if (hdrPlus) out += "HDR10+" else if (hdr) out += "HDR"
        for (t in tags) {
            if (!t.regex.containsMatchIn(text)) continue
            if (t.label == "DTS" && "DTS-HD" in out) continue
            out += t.label
        }
        return out
    }

    private val LANGUAGE_WORDS = listOf(
        "English", "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Punjabi",
        "Japanese", "Korean", "Chinese", "Spanish", "French", "German", "Italian", "Russian", "Portuguese", "Arabic",
        "Turkish", "Polish", "Dutch",
    )

    /** Upper case only: "ITA" is Italian in a release name, "Ita" / "por" / "tel" are just words. */
    private val ABBREVIATIONS = mapOf(
        "ENG" to "English", "HIN" to "Hindi", "TAM" to "Tamil", "TEL" to "Telugu", "MAL" to "Malayalam", "KAN" to "Kannada",
        "ITA" to "Italian", "FRE" to "French", "GER" to "German", "SPA" to "Spanish", "RUS" to "Russian", "JPN" to "Japanese",
        "KOR" to "Korean", "VFF" to "French", "TRUEFRENCH" to "French",
    )

    /** Flag emoji the big addons put in descriptions for the audio language (🇬🇧 🇮🇳 …). */
    private val FLAGS = mapOf(
        "GB" to "English", "US" to "English", "IN" to "Hindi", "ES" to "Spanish", "MX" to "Spanish", "FR" to "French",
        "DE" to "German", "IT" to "Italian", "JP" to "Japanese", "KR" to "Korean", "CN" to "Chinese", "RU" to "Russian",
        "PT" to "Portuguese", "BR" to "Portuguese", "TR" to "Turkish", "PL" to "Polish", "NL" to "Dutch",
    )

    /** Spoken languages named in the release (words, upper-case codes, flags), in first-seen order. */
    fun languages(s: Source): List<String> = languages(s.title, s.filename)

    /**
     * "Dual audio" / "Multi" only when no language is named: "Hindi + English" already says it.
     * Shared with the launcher API (LauncherPage.languages).
     */
    fun languages(vararg texts: String?): List<String> {
        val text = texts.filterNotNull().joinToString(" ")
        val found = LinkedHashSet<String>()
        for (w in Regex("[A-Za-z]+").findAll(text).map { it.value }) {
            LANGUAGE_WORDS.firstOrNull { it.equals(w, ignoreCase = true) }?.let(found::add)
            ABBREVIATIONS[w]?.let(found::add)
        }
        // Regional-indicator pairs → country code.
        val cps = text.codePoints().toArray()
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i] - 0x1F1E6
            val b = cps[i + 1] - 0x1F1E6
            if (a in 0..25 && b in 0..25) {
                FLAGS["${'A' + a}${'A' + b}"]?.let(found::add)
                i += 2
            } else i++
        }
        if (found.isEmpty()) {
            if (Regex("(?i)\\bdual[ ._-]?audio\\b").containsMatchIn(text)) found += "Dual audio"
            else if (Regex("(?i)\\bmulti\\b").containsMatchIn(text)) found += "Multi"
        }
        return found.toList()
    }

    /**
     * A provider's short name for chips and rows: "Comet | Real-Debrid" → "Comet",
     * "MediaFusion 🇮🇳 (Hindi, Tamil)" → "MediaFusion", "Built-in: Internet Archive" → "Internet Archive".
     */
    fun providerLabel(provider: String): String {
        var p = provider.substringAfter("Built-in:").trim()
        p = p.substringBefore(" | ").substringBefore(" (").substringBefore(" - ")
        p = p.filter { it.isLetterOrDigit() || it == ' ' || it == '.' || it == '+' || it == '-' || it == '\'' }
        return p.trim().replace(Regex("\\s+"), " ").ifEmpty { provider.trim() }
    }

    /** Dots, underscores and brackets read as spaces, so "DDP5.1" and "5.1" both match "5 1". */
    private fun normalise(texts: Array<out String?>): String =
        texts.filterNotNull().joinToString(" ")
            .replace(Regex("[._\\[\\]()]"), " ")
            .replace(Regex("(?i)(ddp|dd|aac|ac3|truehd|atmos|dts)(\\d)"), "$1 $2")
}
