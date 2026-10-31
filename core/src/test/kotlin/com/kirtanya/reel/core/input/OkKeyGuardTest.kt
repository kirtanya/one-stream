package com.kirtanya.reel.core.input

import com.kirtanya.reel.core.input.OkKeyGuard.Verdict.Pass
import com.kirtanya.reel.core.input.OkKeyGuard.Verdict.Swallow
import kotlin.test.Test
import kotlin.test.assertEquals

class OkKeyGuardTest {
    @Test fun aQuickPressPassesDownAndUp() {
        val g = OkKeyGuard()
        assertEquals(Pass, g.onDown(0, hasLongClick = false))
        assertEquals(Pass, g.onUp())
    }

    @Test fun repeatsNeverReachAnElementWithoutLongPress() {
        val g = OkKeyGuard()
        g.onDown(0, hasLongClick = false)
        assertEquals(Swallow, g.onDown(1, hasLongClick = false))
        assertEquals(Swallow, g.onDown(7, hasLongClick = false))
        assertEquals(Pass, g.onUp(), "the one click still happens on release")
    }

    @Test fun repeatsReachTheLongPressTimerOnlyForThePressItStarted() {
        val g = OkKeyGuard()
        g.onDown(0, hasLongClick = true)
        assertEquals(Pass, g.onDown(1, hasLongClick = true))
        assertEquals(Pass, g.onUp())
    }

    @Test fun anElementFocusedMidHoldIgnoresRepeatsAndTheRelease() {
        // OK opened this page; the key is still down and its repeats + key-up arrive at the new focus.
        val g = OkKeyGuard()
        assertEquals(Swallow, g.onDown(1, hasLongClick = true))
        assertEquals(Swallow, g.onDown(2, hasLongClick = false))
        assertEquals(Swallow, g.onUp())
    }

    @Test fun theNextRealPressWorksAfterAStrayRelease() {
        val g = OkKeyGuard()
        g.onUp()
        assertEquals(Pass, g.onDown(0, hasLongClick = false))
        assertEquals(Pass, g.onUp())
    }

    @Test fun losingFocusMidPressDisarms() {
        val g = OkKeyGuard()
        g.onDown(0, hasLongClick = true)
        g.reset()
        assertEquals(Swallow, g.onDown(1, hasLongClick = true))
        assertEquals(Swallow, g.onUp())
    }
}
