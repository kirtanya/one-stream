package com.kirtanya.reel.core.input

import com.kirtanya.reel.core.input.PlayerExitGuard.Key
import com.kirtanya.reel.core.input.PlayerExitGuard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExitGuardTest {
    private fun PlayerExitGuard.down(key: Key, repeat: Int = 0, controls: Boolean = false, error: Boolean = false, chip: Boolean = false) =
        onDown(key, repeat, controls, error, chip)

    @Test fun backWithControlsHiddenShowsTheChipAndNeverLeaves() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.ShowChip, g.down(Key.Back))
        assertEquals(Verdict.Swallow, g.onUp(Key.Back))
    }

    @Test fun backAgainAndAgainKeepsShowingTheChip() {
        val g = PlayerExitGuard()
        repeat(5) {
            assertEquals(Verdict.ShowChip, g.down(Key.Back, chip = it > 0))
            assertEquals(Verdict.Swallow, g.onUp(Key.Back))
        }
    }

    @Test fun okWhileTheChipShowsLeavesOnRelease() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Swallow, g.down(Key.Ok, chip = true))
        assertEquals(Verdict.Exit, g.onUp(Key.Ok))
    }

    @Test fun okWithoutTheChipIsLeftToThePlayer() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(Key.Ok))
        assertEquals(Verdict.Pass, g.onUp(Key.Ok))
    }

    @Test fun backWithControlsOpenIsLeftToTheControls() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(Key.Back, controls = true))
        // The controls hid on the key-down; the key-up still isn't ours (the controls swallow it).
        assertEquals(Verdict.Pass, g.onUp(Key.Back))
    }

    @Test fun backOnAnErrorScreenLeavesAsBefore() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(Key.Back, error = true))
        assertEquals(Verdict.Pass, g.onUp(Key.Back))
    }

    @Test fun heldKeysRepeatsAreSwallowed() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.ShowChip, g.down(Key.Back))
        assertEquals(Verdict.Swallow, g.down(Key.Back, repeat = 1, chip = true))
        assertEquals(Verdict.Swallow, g.onUp(Key.Back))
        assertEquals(Verdict.Swallow, g.down(Key.Ok, chip = true))
        assertEquals(Verdict.Swallow, g.down(Key.Ok, repeat = 3, chip = true))
        assertEquals(Verdict.Exit, g.onUp(Key.Ok))
    }

    @Test fun otherKeysPass() {
        val g = PlayerExitGuard()
        assertEquals(Verdict.Pass, g.down(Key.Other, chip = true))
        assertEquals(Verdict.Pass, g.onUp(Key.Other))
    }
}
