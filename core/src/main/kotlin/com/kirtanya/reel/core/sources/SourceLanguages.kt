package com.kirtanya.reel.core.sources

/**
 * Which spoken languages the source lists offer: English, Hindi and the title's original language. A release that
 * names only other languages (a French or Tamil dub of an English film) is left out everywhere sources are searched.
 *
 * Most releases name no language at all, and those are almost always in the original language: they stay. So do
 * "Multi" / "Dual audio" releases, which usually carry English or Hindi too.
 */
object SourceLanguages {
    private val ALWAYS = setOf("English", "Hindi")
    private val UNNAMED = setOf("Multi", "Dual audio")

    /** TMDB `original_language` (ISO 639-1) → the name SourceFacts.languages() uses. Unknown codes: null. */
    private val BY_CODE = mapOf(
        "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada",
        "bn" to "Bengali", "mr" to "Marathi", "pa" to "Punjabi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "cn" to "Chinese", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "ru" to "Russian",
        "pt" to "Portuguese", "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish", "nl" to "Dutch",
    )

    fun nameOf(code: String?): String? = code?.lowercase()?.let(BY_CODE::get)

    /** The languages a title's lists offer, e.g. [English, Hindi, Korean]. */
    fun offered(originalLanguage: String?): Set<String> = ALWAYS + listOfNotNull(nameOf(originalLanguage))

    /** Whether [s] stays in the lists for a title whose TMDB original language is [originalLanguage]. */
    fun keep(s: Source, originalLanguage: String?): Boolean {
        val named = SourceFacts.languages(s)
        if (named.isEmpty() || named.any { it in UNNAMED }) return true
        val offered = offered(originalLanguage)
        return named.any { it in offered }
    }
}
