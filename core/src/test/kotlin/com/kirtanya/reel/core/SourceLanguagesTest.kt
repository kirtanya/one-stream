package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source lists offer English, Hindi and the title's original language only. */
class SourceLanguagesTest {
    private fun src(title: String) = Source(SourceKind.DIRECT, title, "Addon", Quality(), url = "https://x.example/$title")

    @Test fun unnamedReleasesStay(): Unit {
        assertTrue(SourceLanguages.keep(src("Big.Buck.Bunny.2008.1080p.WEB"), "en"))
        assertTrue(SourceLanguages.keep(src("Big.Buck.Bunny.2008.1080p.WEB"), null))
    }

    @Test fun englishAndHindiAlwaysStay(): Unit {
        assertTrue(SourceLanguages.keep(src("Film 1080p Hindi"), "ko"))
        assertTrue(SourceLanguages.keep(src("Film 1080p English"), "ta"))
        assertTrue(SourceLanguages.keep(src("Film 1080p French + English"), "en"))
    }

    @Test fun otherDubsGo(): Unit {
        assertFalse(SourceLanguages.keep(src("Film 1080p French"), "en"))
        assertFalse(SourceLanguages.keep(src("Film 1080p Tamil Telugu"), "en"))
        assertFalse(SourceLanguages.keep(src("Film 1080p ITA"), "hi"))
        assertFalse(SourceLanguages.keep(src("Film 1080p Spanish"), null))
    }

    @Test fun originalLanguageStays(): Unit {
        assertTrue(SourceLanguages.keep(src("Film 1080p Korean"), "ko"))
        assertTrue(SourceLanguages.keep(src("Film 1080p Tamil"), "ta"))
        assertFalse(SourceLanguages.keep(src("Film 1080p Telugu"), "ta"))
    }

    @Test fun multiAudioStays(): Unit {
        assertTrue(SourceLanguages.keep(src("Film 1080p MULTI"), "en"))
        assertTrue(SourceLanguages.keep(src("Film 1080p Dual Audio"), "en"))
    }

    @Test fun offered(): Unit {
        assertEquals(setOf("English", "Hindi", "Korean"), SourceLanguages.offered("ko"))
        assertEquals(setOf("English", "Hindi"), SourceLanguages.offered("xx"))
    }
}
