package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.playback.TitlePrefs
import com.kirtanya.reel.core.sources.Subtitles

/** One audio or subtitle track as the player reports it (the app maps ExoPlayer's Format onto this). */
data class TrackCandidate(
    /** As the file says it: "eng", "en", "en-US", "und" or null. */
    val language: String?,
    val label: String? = null,
    /** The file's own "default" flag. Never decides by itself (a Hindi release flags Hindi). */
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** Role flags: commentary track / audio description for the blind. */
    val isCommentary: Boolean = false,
    val isDescriptive: Boolean = false,
) {
    /** Director's commentary, audio description: never what anyone wants playing by itself. */
    val isExtra: Boolean
        get() = isCommentary || isDescriptive || label.orEmpty().lowercase().let { l -> EXTRA_WORDS.any { it in l } }

    /** Subtitles for the deaf (sound cues too): fine, but plain dialogue subtitles come first. */
    val isSdh: Boolean
        get() = label.orEmpty().lowercase().let { l -> SDH_WORDS.any { it in l } }

    /** Only translates foreign speech: not a full set of subtitles. */
    val isForcedOnly: Boolean
        get() = isForced || label.orEmpty().lowercase().let { l -> "forced" in l || "foreign" in l }

    /** Two-letter language, or null when the file doesn't say. */
    val code: String?
        get() = language?.takeIf { it.isNotBlank() }?.let(Subtitles::normalizeLanguage)?.takeIf { it != "und" }

    private companion object {
        val EXTRA_WORDS = listOf("commentary", "director", "audio description", "descriptive", "described")
        val SDH_WORDS = listOf("sdh", "hearing", "deaf", "[cc]", "(cc)")
    }
}

/**
 * Which track plays by itself: English first, unless this title remembers another language
 * (TitlePrefs). The pick overrides the file's "default" flag, which usually just marks the
 * release's original language. Null = no pick: leave what the player chose (one track, or the
 * wanted language isn't there).
 */
object TrackPick {
    const val ENGLISH = "en"

    /**
     * The audio track to start on, as an index into [tracks]. The title's remembered language
     * ([titleLanguage]) first, then English; commentary and audio description never count.
     * Among several in the language: the file's default one, else the first.
     */
    fun audio(tracks: List<TrackCandidate>, titleLanguage: String?): Int? {
        if (tracks.size < 2) return null
        val wanted = listOfNotNull(titleLanguage?.let(Subtitles::normalizeLanguage), ENGLISH).distinct()
        for (lang in wanted) {
            val inLanguage = tracks.withIndex().filter { (_, t) -> t.code == lang && !t.isExtra }
            if (inLanguage.isEmpty()) continue
            return (inLanguage.firstOrNull { it.value.isDefault } ?: inLanguage.first()).index
        }
        return null
    }

    /**
     * The subtitle track to show, as an index into [tracks], or null for none.
     *  - [titleLanguage] (TitlePrefs.subtitleLanguage) = [TitlePrefs.SUBTITLES_OFF] → none: Off stays off.
     *  - Otherwise this title's language, else the Settings language ([settingLanguage], null = Off → none).
     *  - That language not among the tracks → none (no guessing a language the user didn't ask for).
     *  - Several in the language: the remembered label ([titleLabel]), else a full set before SDH
     *    before forced-only; ties keep the player's order (the file's own tracks come first).
     *  The file's "default" flag plays no part.
     */
    fun subtitle(tracks: List<TrackCandidate>, titleLanguage: String?, titleLabel: String?, settingLanguage: String?): Int? {
        if (titleLanguage == TitlePrefs.SUBTITLES_OFF) return null
        val wanted = (titleLanguage ?: settingLanguage)?.let(Subtitles::normalizeLanguage) ?: return null
        val inLanguage = tracks.withIndex().filter { (_, t) -> t.code == wanted }
        if (inLanguage.isEmpty()) return null
        if (titleLanguage != null && titleLabel != null) inLanguage.firstOrNull { it.value.label == titleLabel }?.let { return it.index }
        return inLanguage.minByOrNull { (_, t) -> if (t.isForcedOnly) 2 else if (t.isSdh) 1 else 0 }?.index
    }
}
