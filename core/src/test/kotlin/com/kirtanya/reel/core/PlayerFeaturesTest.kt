package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.PictureMode
import com.kirtanya.reel.core.playback.TitlePrefs
import com.kirtanya.reel.core.player.AutoNext
import com.kirtanya.reel.core.player.ChapterMark
import com.kirtanya.reel.core.player.ChapterSkips
import com.kirtanya.reel.core.player.Delay
import com.kirtanya.reel.core.player.IntroSkip
import com.kirtanya.reel.core.player.PlayerMemory
import com.kirtanya.reel.core.player.PlayerOptions
import com.kirtanya.reel.core.player.SeekBurst
import com.kirtanya.reel.core.player.SeekStep
import com.kirtanya.reel.core.player.ShowSkips
import com.kirtanya.reel.core.player.SkipKind
import com.kirtanya.reel.core.player.SkipLearning
import com.kirtanya.reel.core.player.SkipPlan
import com.kirtanya.reel.core.player.SkipWindow
import com.kirtanya.reel.core.player.SleepChoice
import com.kirtanya.reel.core.player.SleepTimer
import com.kirtanya.reel.core.player.Speeds
import com.kirtanya.reel.core.player.TitleStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFeaturesTest {
    // ------------------------------------------------------------ intro learning

    @Test fun introSkipIsAForwardJumpOf30To150sInTheFirst6Minutes(): Unit {
        assertTrue(SkipLearning.isIntroSkip(60_000, 150_000))
        assertTrue(SkipLearning.isIntroSkip(0, 30_000))
        assertFalse(SkipLearning.isIntroSkip(60_000, 80_000), "20 s is a normal skip")
        assertFalse(SkipLearning.isIntroSkip(60_000, 300_000), "4 min is jumping ahead, not an intro")
        assertFalse(SkipLearning.isIntroSkip(7 * 60_000L, 8 * 60_000L), "too late in the episode")
        assertFalse(SkipLearning.isIntroSkip(150_000, 60_000), "backwards")
    }

    @Test fun noChipBeforeTheFirstLearnedEpisodeThenTheMedian(): Unit {
        assertNull(SkipLearning.introWindow(null))
        assertNull(SkipLearning.introWindow(ShowSkips()))
        var show = SkipLearning.addIntro(ShowSkips(), IntroSkip("tv:1:s1e1", 62_000, 150_000))
        assertEquals(SkipWindow(62_000, 150_000), SkipLearning.introWindow(show), "from the 2nd episode on")
        show = SkipLearning.addIntro(show, IntroSkip("tv:1:s1e2", 58_000, 148_000))
        show = SkipLearning.addIntro(show, IntroSkip("tv:1:s1e3", 200_000, 330_000)) // one odd episode
        assertEquals(SkipWindow(62_000, 150_000), SkipLearning.introWindow(show), "median ignores the odd one")
    }

    @Test fun sameEpisodeReplacesItsObservationAndOnly8Kept(): Unit {
        var show = ShowSkips()
        show = SkipLearning.addIntro(show, IntroSkip("e1", 10_000, 60_000))
        show = SkipLearning.addIntro(show, IntroSkip("e1", 20_000, 70_000))
        assertEquals(listOf(IntroSkip("e1", 20_000, 70_000)), show.intros)
        repeat(12) { show = SkipLearning.addIntro(show, IntroSkip("x$it", 10_000, 60_000)) }
        assertEquals(SkipLearning.KEEP, show.intros.size)
        assertEquals("x11", show.intros.last().episodeKey)
    }

    @Test fun disagreeingSkipsGiveNoWindow(): Unit {
        // Median start after median end − 15 s: not a usable intro.
        val show = ShowSkips(intros = listOf(IntroSkip("a", 100_000, 110_000), IntroSkip("b", 100_000, 112_000)))
        assertNull(SkipLearning.introWindow(show))
    }

    @Test fun remotePressesInABurstAreOneJump(): Unit {
        val b = SeekBurst(quietMs = 2_500)
        // Six presses of → (10 s each) a moment apart, one back after overshooting.
        var t = 0L
        var pos = 60_000L
        repeat(6) { assertNull(b.seek(pos, pos + 10_000, t)); pos += 10_000; t += 400 }
        assertNull(b.seek(pos, pos - 10_000, t))
        assertNull(b.flush(t + 1_000), "still in the burst")
        assertEquals(60_000L to 110_000L, b.flush(t + 2_600))
        assertNull(b.flush(t + 10_000), "flushed once")
        // A new seek after a quiet gap closes the previous burst first.
        b.seek(0, 10_000, 100_000)
        assertEquals(0L to 10_000L, b.seek(20_000, 30_000, 110_000))
        assertEquals(20_000L to 30_000L, b.flush(110_001, force = true))
    }

    // ------------------------------------------------------------ chapters + plan

    @Test fun chapterTitlesSayWhatToSkip(): Unit {
        assertEquals(SkipKind.INTRO, ChapterSkips.kindOf("Intro"))
        assertEquals(SkipKind.INTRO, ChapterSkips.kindOf("Opening Credits"))
        assertEquals(SkipKind.INTRO, ChapterSkips.kindOf("OP"))
        assertEquals(SkipKind.RECAP, ChapterSkips.kindOf("Previously on…"))
        assertEquals(SkipKind.CREDITS, ChapterSkips.kindOf("End Credits"))
        assertEquals(SkipKind.CREDITS, ChapterSkips.kindOf("ED 2"))
        assertNull(ChapterSkips.kindOf("Ed's big day"))
        assertNull(ChapterSkips.kindOf("Chapter 3"))
        assertNull(ChapterSkips.kindOf(null))
    }

    @Test fun chaptersWithoutAnEndRunToTheNextOne(): Unit {
        val chapters = listOf(
            ChapterMark(0, null, "Cold open"),
            ChapterMark(90_000, null, "Opening"),
            ChapterMark(170_000, null, "Part 1"),
            ChapterMark(2_500_000, null, "Credits"),
        )
        assertEquals(
            listOf(SkipKind.INTRO to SkipWindow(90_000, 170_000), SkipKind.CREDITS to SkipWindow(2_500_000, 2_600_000)),
            ChapterSkips.windows(chapters, 2_600_000),
        )
    }

    @Test fun chipShowsInsideTheWindowChaptersFirst(): Unit {
        val learned = SkipWindow(60_000, 150_000)
        assertNull(SkipPlan.chipAt(59_000, emptyList(), learned))
        assertEquals(SkipKind.INTRO to learned, SkipPlan.chipAt(60_000, emptyList(), learned))
        assertNull(SkipPlan.chipAt(148_000, emptyList(), learned), "gone in the last 3 s")
        val chapters = listOf(SkipKind.RECAP to SkipWindow(0, 45_000))
        assertEquals(chapters[0], SkipPlan.chipAt(10_000, chapters, learned))
        assertNull(SkipPlan.chipAt(90_000, chapters, learned), "the file's chapters beat the guess")
    }

    // ------------------------------------------------------------ credits

    @Test fun creditsLearnedFromStartingTheNextEpisode(): Unit {
        var show = ShowSkips()
        show = SkipLearning.addCredits(show, "e1", 5_000)
        assertNull(SkipLearning.creditsRemainingMs(show), "5 s left is just the end")
        show = SkipLearning.addCredits(show, "e1", 20 * 60_000L)
        assertNull(SkipLearning.creditsRemainingMs(show), "20 min left is leaving, not credits")
        show = SkipLearning.addCredits(show, "e1", 90_000)
        assertEquals(90_000, SkipLearning.creditsRemainingMs(show))
        show = SkipLearning.addCredits(show, "e2", 70_000)
        assertEquals(80_000, SkipLearning.creditsRemainingMs(show))
    }

    @Test fun upNextMovesToTheCreditsButNeverLaterThan30sBeforeTheEnd(): Unit {
        val d = 2_600_000L
        assertEquals(d - 30_000, SkipPlan.upNextAtMs(d, null))
        assertEquals(d - 90_000, SkipPlan.upNextAtMs(d, SkipPlan.creditsStartMs(d, emptyList(), 90_000)))
        assertEquals(d - 30_000, SkipPlan.upNextAtMs(d, SkipPlan.creditsStartMs(d, emptyList(), 10_000)))
        val chapters = listOf(SkipKind.CREDITS to SkipWindow(2_450_000, d))
        assertEquals(2_450_000, SkipPlan.creditsStartMs(d, chapters, 90_000), "a credits chapter beats the learned time")
        assertNull(SkipPlan.creditsStartMs(d, emptyList(), d), "nonsense (credits from the start) ignored")
    }

    // ------------------------------------------------------------ auto-play next

    @Test fun stillWatchingAfterThreeUntouchedEpisodes(): Unit {
        var untouched = 0
        untouched = AutoNext.untouchedAfter(untouched, interacted = false)
        assertFalse(AutoNext.askStillWatching(untouched))
        untouched = AutoNext.untouchedAfter(untouched, interacted = false)
        assertFalse(AutoNext.askStillWatching(untouched))
        untouched = AutoNext.untouchedAfter(untouched, interacted = false)
        assertTrue(AutoNext.askStillWatching(untouched), "3rd episode in a row ended untouched")
        assertEquals(0, AutoNext.untouchedAfter(untouched, interacted = true), "any key or touch starts over")
    }

    @Test fun countdownAndSeekStepOnlyOfferedValues(): Unit {
        assertEquals(listOf(5, 10, 20), AutoNext.COUNTDOWNS)
        assertEquals(10, PlayerOptions(countdownS = 7, seekStepS = 12).valid().countdownS)
        assertEquals(10, PlayerOptions(countdownS = 7, seekStepS = 12).valid().seekStepS)
        assertEquals(PlayerOptions(countdownS = 20, seekStepS = 30), PlayerOptions(countdownS = 20, seekStepS = 30).valid())
    }

    @Test fun remoteStepFollowsTheSettingAndHoldingGoesTo30s(): Unit {
        assertEquals(15_000, SeekStep.remoteStepMs(15, 0))
        assertEquals(30_000, SeekStep.remoteStepMs(15, 2_500))
        assertEquals(30_000, SeekStep.remoteStepMs(30, 2_500))
        assertEquals(10_000, SeekStep.remoteStepMs(10, 1_999))
    }

    // ------------------------------------------------------------ speed, delays

    @Test fun speedsAreThreeQuartersToTwo(): Unit {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), Speeds.ALL)
        assertEquals(0.75f, Speeds.nearest(0.5f), "an old 0.5× snaps to the slowest offered")
        assertEquals(1.25f, Speeds.nearest(1.3f))
        assertEquals("Normal", Speeds.label(1f))
        assertEquals("2×", Speeds.label(2f))
        assertEquals("1.75×", Speeds.label(1.75f))
    }

    @Test fun delaysClampAndStep(): Unit {
        assertEquals(500, Delay.AUDIO.clamp(900))
        assertEquals(-500, Delay.AUDIO.clamp(-900))
        assertEquals(150, Delay.AUDIO.clamp(140))
        assertEquals(50, Delay.AUDIO.step(0, 1))
        assertEquals(500, Delay.AUDIO.step(500, 1))
        assertEquals(-250, Delay.SUBTITLES.step(0, -1))
        assertEquals(7_000, Delay.SUBTITLES.clamp(7_000))
        assertEquals(20_000, Delay.SUBTITLES.clamp(25_000))
        assertEquals(-20_000, Delay.SUBTITLES.clamp(-25_000))
        assertEquals(1_000, Delay.SUBTITLES.step(0, 4))
        assertEquals("0 ms", Delay.AUDIO.label(0))
        assertEquals("+150 ms", Delay.AUDIO.label(150))
        assertEquals("−1.25 s", Delay.SUBTITLES.label(-1_250))
        assertEquals("+5 s", Delay.SUBTITLES.label(5_000))
        assertEquals("+250 ms", Delay.SUBTITLES.label(250))
    }

    // ------------------------------------------------------------ sleep timer

    @Test fun sleepTimerFadesTheLast10sThenStops(): Unit {
        assertNull(SleepTimer.remainingMs(SleepChoice.OFF, 0, 1_000, 0, 100_000, 1f))
        assertEquals(15 * 60_000L - 60_000, SleepTimer.remainingMs(SleepChoice.MIN_15, 0, 60_000, 0, null, 1f))
        assertEquals(0, SleepTimer.remainingMs(SleepChoice.MIN_15, 0, 99 * 60_000L, 0, null, 1f), "never negative")
        assertEquals(50_000, SleepTimer.remainingMs(SleepChoice.END_OF_EPISODE, 0, 0, 1_000_000, 1_100_000, 2f), "at 2× the end comes sooner")
        assertNull(SleepTimer.remainingMs(SleepChoice.END_OF_EPISODE, 0, 0, 0, null, 1f), "length not known yet")
        val early = SleepTimer.state(5 * 60_000L)
        assertEquals(1f, early.volume); assertFalse(early.showChip); assertFalse(early.done)
        val chip = SleepTimer.state(45_000)
        assertTrue(chip.showChip); assertEquals(1f, chip.volume)
        assertEquals(0.5f, SleepTimer.state(5_000).volume)
        assertTrue(SleepTimer.state(0).done)
        assertEquals(0f, SleepTimer.state(0).volume)
    }

    // ------------------------------------------------------------ per-title memory

    @Test fun titleChoicesBeatTheGlobalSettings(): Unit {
        assertNull(TitleStart.subtitles(null))
        assertNull(TitleStart.subtitles(TitlePrefs("tv:1", speed = 1.5f)), "subtitles never chosen here")
        assertEquals(TitleStart.Subtitles(off = true, language = null), TitleStart.subtitles(TitlePrefs("tv:1", subtitleLanguage = TitlePrefs.SUBTITLES_OFF)))
        assertEquals(TitleStart.Subtitles(off = false, language = "hi"), TitleStart.subtitles(TitlePrefs("tv:1", subtitleLanguage = "hi")))
        assertEquals(1f, TitleStart.speed(null))
        assertEquals(0.75f, TitleStart.speed(TitlePrefs("tv:1", speed = 0.5f)))
        assertEquals(PictureMode.FIT, TitleStart.picture(null, PictureMode.FIT))
        assertEquals(PictureMode.FILL, TitleStart.picture(TitlePrefs("tv:1", picture = "FILL"), PictureMode.FIT))
        assertEquals(PictureMode.STRETCH, TitleStart.picture(TitlePrefs("tv:1", picture = "GONE"), PictureMode.STRETCH))
    }

    @Test fun memoryKeepsDelaysAndSkipsAndSurvivesARoundTrip(): Unit {
        var m = PlayerMemory()
        m = m.withDelays("tv:1", 10) { it.copy(audioDelayMs = 150) }
        m = m.withShow("tv:1", 10) { SkipLearning.addIntro(it, IntroSkip("tv:1:s1e1", 60_000, 150_000)) }
        val back = PlayerMemory.decode(m.encode())
        assertEquals(150, back.delays("tv:1").audioDelayMs)
        assertEquals(SkipWindow(60_000, 150_000), SkipLearning.introWindow(back.shows["tv:1"]))
        assertEquals(PlayerMemory(), PlayerMemory.decode("{broken"))
        assertEquals(PlayerMemory(), PlayerMemory.decode(null))
        // Both delays back to 0: the title is forgotten.
        assertTrue(back.withDelays("tv:1", 20) { it.copy(audioDelayMs = 0) }.delays.isEmpty())
    }

    @Test fun memoryDropsTheOldestTitlesAndShows(): Unit {
        var m = PlayerMemory()
        for (i in 0..PlayerMemory.MAX_TITLES) m = m.withDelays("t$i", i.toLong()) { it.copy(subtitleDelayMs = 250) }
        assertEquals(PlayerMemory.MAX_TITLES, m.delays.size)
        assertFalse("t0" in m.delays)
        for (i in 0..PlayerMemory.MAX_SHOWS) m = m.withShow("s$i", i.toLong()) { SkipLearning.addCredits(it, "e", 60_000) }
        assertEquals(PlayerMemory.MAX_SHOWS, m.shows.size)
        assertFalse("s0" in m.shows)
        assertEquals(m.shows.keys, m.showsUpdated.keys)
        // Nothing learned (out-of-range credits): no change, no stamp.
        assertEquals(m, m.withShow("new", 9_999) { SkipLearning.addCredits(it, "e", 1_000) })
    }
}
