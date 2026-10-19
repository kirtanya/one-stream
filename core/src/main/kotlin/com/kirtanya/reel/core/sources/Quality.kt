package com.kirtanya.reel.core.sources

/** What a release name tells us about picture quality. */
data class Quality(
    /** Vertical resolution: 2160, 1440, 1080, 720, 576, 480 — or null if unknown. */
    val resolution: Int? = null,
    val hdr: Boolean = false,
    val dolbyVision: Boolean = false,
    val hevc: Boolean = false,
    val av1: Boolean = false,
    /** Cam / telesync / screener rips. */
    val lowQualityRip: Boolean = false,
) {
    /** Short badge text: "4K HDR", "1080p", "720p"… */
    val label: String
        get() = buildString {
            append(when (resolution) {
                null -> "SD?"
                2160 -> "4K"
                else -> "${resolution}p"
            })
            if (dolbyVision) append(" DV") else if (hdr) append(" HDR")
            if (lowQualityRip) append(" CAM")
        }
}

/** Reads quality, size and seeders out of free-form source text (Torrentio-style descriptions included). */
object QualityParser {
    private val res = listOf(
        2160 to Regex("""(?i)\b(2160p|4k|uhd)\b"""),
        1440 to Regex("""(?i)\b1440p\b"""),
        1080 to Regex("""(?i)\b(1080p|1080i|fhd)\b"""),
        720 to Regex("""(?i)\b(720p|hd)\b"""),
        576 to Regex("""(?i)\b576p\b"""),
        480 to Regex("""(?i)\b(480p|sd|dvdrip)\b"""),
    )
    private val dv = Regex("""(?i)\b(dv|dovi|dolby[ ._-]?vision)\b""")
    private val hdr = Regex("""(?i)\b(hdr|hdr10\+?|hlg)\b""")
    private val hevc = Regex("""(?i)\b(hevc|x265|h\.?265)\b""")
    private val av1 = Regex("""(?i)\bav1\b""")
    private val cam = Regex("""(?i)\b(cam|camrip|hdcam|ts|telesync|hdts|tc|telecine|scr|screener)\b""")
    private val size = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(tb|gb|mb|kb|tib|gib|mib|kib)\b""")
    private val seeders = Regex("""(?:👤|seeders?:?|seeds?:?|\bS:)\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(vararg texts: String?): Quality {
        val text = texts.filterNotNull().joinToString(" ").replace('.', ' ').replace('_', ' ')
        return Quality(
            resolution = res.firstOrNull { it.second.containsMatchIn(text) }?.first,
            hdr = hdr.containsMatchIn(text) || dv.containsMatchIn(text),
            dolbyVision = dv.containsMatchIn(text),
            hevc = hevc.containsMatchIn(text),
            av1 = av1.containsMatchIn(text),
            lowQualityRip = cam.containsMatchIn(text),
        )
    }

    /** "💾 2.35 GB" → bytes. Takes the first size mentioned. */
    fun parseSize(vararg texts: String?): Long? {
        val m = size.find(texts.filterNotNull().joinToString(" ")) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase().take(1)) {
            "t" -> 1L shl 40
            "g" -> 1L shl 30
            "m" -> 1L shl 20
            else -> 1L shl 10
        }
        return (value * mult).toLong()
    }

    fun parseSeeders(vararg texts: String?): Int? =
        seeders.find(texts.filterNotNull().joinToString(" "))?.groupValues?.get(1)?.toIntOrNull()
}
