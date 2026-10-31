package com.kirtanya.reel.core.input

/**
 * TV player: Back never leaves on its own, because it's easy to hit by accident. With the controls hidden,
 * Back shows "Press OK to exit" (however often it's pressed); OK while that shows leaves. Open controls still
 * close on Back first, and an error screen still leaves on Back.
 *
 * The guard takes a whole press: the key-down, its auto-repeats and the key-up, so nothing else acts on the
 * key (OK would otherwise also pause or open the controls; Back would leave).
 */
class PlayerExitGuard {
    enum class Key { Back, Ok, Other }
    enum class Verdict { Pass, Swallow, ShowChip, Exit }

    private var held: Key? = null

    /** [repeatCount] is the Android key event's: 0 for the real press, > 0 for auto-repeat. */
    fun onDown(key: Key, repeatCount: Int, controlsOpen: Boolean, errorShown: Boolean, chipShown: Boolean): Verdict {
        if (repeatCount > 0 && held == key) return Verdict.Swallow
        if (key == Key.Ok && chipShown) {
            held = key
            return Verdict.Swallow
        }
        if (key == Key.Back && !controlsOpen && !errorShown) {
            held = key
            return Verdict.ShowChip
        }
        return Verdict.Pass
    }

    fun onUp(key: Key): Verdict {
        if (held != key) return Verdict.Pass
        held = null
        return if (key == Key.Ok) Verdict.Exit else Verdict.Swallow
    }
}
