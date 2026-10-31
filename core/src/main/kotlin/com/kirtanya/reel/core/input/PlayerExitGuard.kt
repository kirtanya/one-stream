package com.kirtanya.reel.core.input

/**
 * TV player: one Back never leaves, because it's easy to hit by accident. With the controls hidden, Back shows
 * "Press Back again to exit"; a second Back while that shows leaves. Open controls still close on Back first,
 * and an error screen still leaves on Back.
 *
 * The guard takes a whole press: the key-down, its auto-repeats and the key-up, so nothing else acts on it
 * (a held Back can't leave by itself either: its repeats are swallowed).
 */
class PlayerExitGuard {
    enum class Verdict { Pass, Swallow, ShowChip, Exit }

    private var held = false
    private var leaving = false

    /** A Back key-down. [repeatCount] is the Android key event's: 0 for the real press, > 0 for auto-repeat. */
    fun onBackDown(repeatCount: Int, controlsOpen: Boolean, errorShown: Boolean, chipShown: Boolean): Verdict {
        if (repeatCount > 0 && held) return Verdict.Swallow
        if (controlsOpen || errorShown) return Verdict.Pass
        held = true
        leaving = chipShown
        return if (chipShown) Verdict.Swallow else Verdict.ShowChip
    }

    /** A Back key-up: leaves when its press began while the chip showed. */
    fun onBackUp(): Verdict {
        if (!held) return Verdict.Pass
        held = false
        return if (leaving) Verdict.Exit else Verdict.Swallow
    }
}
