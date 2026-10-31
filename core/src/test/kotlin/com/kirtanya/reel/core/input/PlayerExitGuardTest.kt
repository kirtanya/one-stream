package com.kirtanya.reel.core.input

import com.kirtanya.reel.core.input.PlayerExitGuard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExitGuardTest {
    private fun PlayerExitGuard.down(repeat: Int = 0, controls: Boolean = false, error: Boolean = false, chip: Boolean = false) =
        onBackDown(repeat, controls, error, chip)

    @Test fun firstBackShowsTheChipAndStays() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.ShowChip, g.down())
        assertEquals(Verdict.Swallow, g.onBackUp())
    }

    @Test fun secondBackWhileTheChipShowsLeaves() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.ShowChip, g.down())
        assertEquals(Verdict.Swallow, g.onBackUp())
        assertEquals(Verdict.Swallow, g.down(chip = true))
        assertEquals(Verdict.Exit, g.onBackUp())
    }

    @Test fun backAfterTheChipWentAwayAsksAgain() {
        val g = PlayerExitGuard()
        g.down(); g.onBackUp()
        assertEquals(Verdict.ShowChip, g.down(chip = false))
        assertEquals(Verdict.Swallow, g.onBackUp())
    }

    @Test fun holdingBackNeverLeaves() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.ShowChip, g.down())
        repeat(5) { assertEquals(Verdict.Swallow, g.down(repeat = it + 1, chip = true)) }
        assertEquals(Verdict.Swallow, g.onBackUp())
    }

    @Test fun backWithControlsOpenIsLeftToTheControls() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(controls = true))
        assertEquals(Verdict.Pass, g.onBackUp())
    }

    @Test fun backOnAnErrorScreenLeavesAsBefore() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(error = true))
        assertEquals(Verdict.Pass, g.onBackUp())
    }
}
