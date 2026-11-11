package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.AudioLanguage
import com.kirtanya.reel.core.player.TrackCandidate
import com.kirtanya.reel.core.player.TrackPick
import com.kirtanya.reel.core.playback.TitlePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackPickTest {
    private val EN = listOf("en")
    private fun t(lang: String?, label: String? = null, default: Boolean = false, forced: Boolean = false, commentary: Boolean = false) =
        TrackCandidate(lang, label, isDefault = default, isForced = forced, isCommentary = commentary)

    // ---- audio

    @Test fun englishBeatsTheFilesDefaultFlag() {
        assertEquals(1, TrackPick.audio(listOf(t("hin", default = true), t("eng")), null, EN))
    }

    @Test fun englishMainTrackNotCommentary() {
        // By label, and by role flag.
        assertEquals(2, TrackPick.audio(listOf(t("jpn"), t("eng", "English Commentary"), t("eng")), null, EN))
        assertEquals(2, TrackPick.audio(listOf(t("jpn"), t("eng", commentary = true), t("eng")), null, EN))
        assertEquals(2, TrackPick.audio(listOf(t("jpn", default = true), t("eng", "Audio Description"), t("en-US")), null, EN))
    }

    @Test fun onlyCommentaryInEnglishLeavesTheDefault() {
        assertNull(TrackPick.audio(listOf(t("jpn", default = true), t("eng", "Director's commentary")), null, EN))
    }

    @Test fun titleChoiceWins() {
        assertEquals(0, TrackPick.audio(listOf(t("hin"), t("eng", default = true)), "hi", EN))
        assertEquals(0, TrackPick.audio(listOf(t("hi"), t("en")), "hin", EN))
    }

    @Test fun titleLanguageMissingFallsBackToEnglish() {
        assertEquals(1, TrackPick.audio(listOf(t("jpn", default = true), t("eng")), "hi", EN))
    }

    @Test fun noEnglishAndNoChoiceLeavesIt() {
        assertNull(TrackPick.audio(listOf(t("hin", default = true), t("jpn")), null, EN))
    }

    @Test fun oneTrackNeedsNoPick() {
        assertNull(TrackPick.audio(listOf(t("eng")), null, EN))
        assertNull(TrackPick.audio(emptyList(), null, EN))
    }

    @Test fun twoEnglishTracksTakeTheDefaultOneElseTheFirst() {
        assertEquals(2, TrackPick.audio(listOf(t("hin"), t("eng", "English 2.0"), t("eng", "English 5.1", default = true)), null, EN))
        assertEquals(1, TrackPick.audio(listOf(t("hin", default = true), t("eng", "English 2.0"), t("eng", "English 5.1")), null, EN))
    }

    @Test fun undeterminedAndMissingLanguagesAreNeverEnglish() {
        assertNull(TrackPick.audio(listOf(t("und", default = true), t(null)), null, EN))
    }

    @Test fun languageNamesAndCodesMatch() {
        assertEquals(1, TrackPick.audio(listOf(t("fre"), t("English")), null, EN))
        assertEquals(0, TrackPick.audio(listOf(t("fre"), t("eng")), "fr", EN))
    }

    // The Settings audio language: a "Dual audio" release with a Hindi and an English track.
    private val dual = listOf(t("hin", default = true), t("eng"))

    @Test fun dualAudioUnderEachSetting() {
        assertEquals(1, TrackPick.audio(dual, null, AudioLanguage.ORIGINAL.codes("en")))
        assertEquals(1, TrackPick.audio(dual, null, AudioLanguage.ORIGINAL.codes(null)))
        assertEquals(0, TrackPick.audio(dual, null, AudioLanguage.ORIGINAL.codes("hi")))
        assertEquals(0, TrackPick.audio(dual, null, AudioLanguage.HINDI.codes("en")))
        assertEquals(1, TrackPick.audio(dual, null, AudioLanguage.ENGLISH.codes("hi")))
    }

    @Test fun hindiFirstFallsBackToEnglishAndBack() {
        assertEquals(1, TrackPick.audio(listOf(t("jpn", default = true), t("eng")), null, AudioLanguage.HINDI.codes("ja")))
        assertEquals(1, TrackPick.audio(listOf(t("jpn", default = true), t("hin")), null, AudioLanguage.ENGLISH.codes("ja")))
        assertNull(TrackPick.audio(listOf(t("jpn", default = true), t("kor")), null, AudioLanguage.HINDI.codes("ja")))
    }

    @Test fun theTitlesRememberedLanguageBeatsTheSetting() {
        assertEquals(1, TrackPick.audio(dual, "en", AudioLanguage.HINDI.codes(null)))
        assertEquals(0, TrackPick.audio(dual, "hi", AudioLanguage.ENGLISH.codes(null)))
        // Remembered language not in the file: the setting decides.
        assertEquals(0, TrackPick.audio(dual + t("jpn"), "fr", AudioLanguage.HINDI.codes(null)))
    }

    @Test fun hindiCommentaryIsNotPicked() {
        assertEquals(2, TrackPick.audio(listOf(t("eng"), t("hin", "Hindi Commentary"), t("hin")), null, AudioLanguage.HINDI.codes(null)))
    }

    // ---- subtitles

    @Test fun englishSubtitlesFromTheSettingBeatTheDefaultFlag() {
        val tracks = listOf(t("hin", default = true), t("eng"))
        assertEquals(1, TrackPick.subtitle(tracks, null, null, "en"))
    }

    @Test fun offStaysOff() {
        val tracks = listOf(t("eng"), t("hin"))
        assertNull(TrackPick.subtitle(tracks, TitlePrefs.SUBTITLES_OFF, null, "en"))
        assertNull(TrackPick.subtitle(tracks, null, null, null))
    }

    @Test fun titleLanguageBeatsTheSetting() {
        assertEquals(1, TrackPick.subtitle(listOf(t("eng"), t("hin")), "hi", null, "en"))
    }

    @Test fun settingLanguageIsHonouredWhenNotEnglish() {
        assertEquals(1, TrackPick.subtitle(listOf(t("eng"), t("fre")), null, null, "fr"))
    }

    @Test fun languageNotThereMeansNone() {
        assertNull(TrackPick.subtitle(listOf(t("hin"), t("fre")), null, null, "en"))
    }

    @Test fun fullBeforeSdhBeforeForced() {
        assertEquals(2, TrackPick.subtitle(listOf(t("eng", "English Forced", forced = true), t("eng", "English SDH"), t("eng", "English")), null, null, "en"))
        assertEquals(1, TrackPick.subtitle(listOf(t("eng", forced = true), t("eng", "English [SDH]")), null, null, "en"))
        assertEquals(1, TrackPick.subtitle(listOf(t("eng", "Forced"), t("eng", "Full")), null, null, "en"))
    }

    @Test fun savedLabelPicksItsTrack() {
        val tracks = listOf(t("eng", "English"), t("eng", "English SDH"))
        assertEquals(1, TrackPick.subtitle(tracks, "en", "English SDH", "en"))
        // A label with no language saved for the title is ignored.
        assertEquals(0, TrackPick.subtitle(tracks, null, "English SDH", "en"))
    }

    @Test fun tiesKeepThePlayersOrder() {
        assertEquals(0, TrackPick.subtitle(listOf(t("eng", "Provider A"), t("eng", "Provider B")), null, null, "en"))
    }
}
