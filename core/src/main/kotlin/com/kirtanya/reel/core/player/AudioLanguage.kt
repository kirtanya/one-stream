package com.kirtanya.reel.core.player

/**
 * The app-wide "Audio language" setting (Settings → Audio & subtitles): which spoken language to look for first,
 * in source ranking, Quick start and the audio track that starts playing.
 *  - [ORIGINAL]: Hindi for a Hindi-original title, English for everything else.
 *  - [HINDI] / [ENGLISH]: that language first for every title.
 * The other of the two always follows as the second wish, so a release in only that one still ranks well.
 */
enum class AudioLanguage(val label: String) {
    ORIGINAL("Original language"), HINDI("Hindi first"), ENGLISH("English first");

    /** Ordered ISO 639-1 codes, best first ("hi", "en"). [originalLanguage] is TMDB's `original_language`; null = unknown. */
    fun codes(originalLanguage: String?): List<String> = when (this) {
        HINDI -> HINDI_FIRST
        ENGLISH -> ENGLISH_FIRST
        ORIGINAL -> if (originalLanguage.equals(HI, ignoreCase = true)) HINDI_FIRST else ENGLISH_FIRST
    }

    /** The same order as language names, as SourceFacts.languages() spells them ("Hindi", "English"). */
    fun names(originalLanguage: String?): List<String> = codes(originalLanguage).map(NAMES::getValue)

    companion object {
        private const val HI = "hi"
        private const val EN = "en"
        private val HINDI_FIRST = listOf(HI, EN)
        private val ENGLISH_FIRST = listOf(EN, HI)
        private val NAMES = mapOf(HI to "Hindi", EN to "English")

        /** A stored name → the choice; anything unknown or missing is the default, [ORIGINAL]. */
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}
