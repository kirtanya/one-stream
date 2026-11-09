package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.AudioLanguage
import com.kirtanya.reel.core.sources.QuickStart
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioLanguageTest {
    private val hindiFirst = listOf("Hindi", "English")
    private val englishFirst = listOf("English", "Hindi")

    @Test fun originalLanguageFollowsTheTitle() {
        assertEquals(hindiFirst, AudioLanguage.ORIGINAL.names("hi"))
        assertEquals(hindiFirst, AudioLanguage.ORIGINAL.names("HI"))
        assertEquals(englishFirst, AudioLanguage.ORIGINAL.names("en"))
        assertEquals(englishFirst, AudioLanguage.ORIGINAL.names("ko"))
        assertEquals(englishFirst, AudioLanguage.ORIGINAL.names(null))
    }

    @Test fun hindiAndEnglishFirstIgnoreTheTitle() {
        for (original in listOf("hi", "en", "ko", null)) {
            assertEquals(hindiFirst, AudioLanguage.HINDI.names(original))
            assertEquals(englishFirst, AudioLanguage.ENGLISH.names(original))
        }
    }

    @Test fun codesMatchNames() {
        assertEquals(listOf("hi", "en"), AudioLanguage.HINDI.codes("en"))
        assertEquals(listOf("en", "hi"), AudioLanguage.ENGLISH.codes("hi"))
        assertEquals(listOf("hi", "en"), AudioLanguage.ORIGINAL.codes("hi"))
        assertEquals(listOf("en", "hi"), AudioLanguage.ORIGINAL.codes("ja"))
    }

    @Test fun storedValueDefaultsToOriginal() {
        assertEquals(AudioLanguage.ORIGINAL, AudioLanguage.of(null))
        assertEquals(AudioLanguage.ORIGINAL, AudioLanguage.of("garbage"))
        AudioLanguage.entries.forEach { assertEquals(it, AudioLanguage.of(it.name)) }
    }

    @Test fun quickStartUsesTheChoice() {
        assertEquals(englishFirst, QuickStart.wantedLanguages("hi", AudioLanguage.ENGLISH))
        assertEquals(hindiFirst, QuickStart.wantedLanguages("en", AudioLanguage.HINDI))
        assertEquals(hindiFirst, QuickStart.wantedLanguages("hi"))
    }
}
