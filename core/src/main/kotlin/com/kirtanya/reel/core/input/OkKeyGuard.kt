package com.kirtanya.reel.core.input

/**
 * Decides which remote OK (centre / enter) key events an element may see, so a held OK fires exactly one
 * click (or the long-press action with no click after it) and auto-repeat never clicks.
 *
 * Why: pressing OK on a poster opens the title page; while the key is still held, the repeats and the
 * final key-up land on whatever is now focused over there (Compose clicks on key-up) and press it too.
 * An element only takes part in a press whose first key-down (repeatCount 0) it saw itself.
 */
class OkKeyGuard {
    enum class Verdict { Pass, Swallow }

    private var armed = false

    /** [repeatCount] is the Android key event's: 0 for the real press, > 0 for auto-repeat. */
    fun onDown(repeatCount: Int, hasLongClick: Boolean): Verdict {
        if (repeatCount == 0) {
            armed = true
            return Verdict.Pass
        }
        // Repeats are only useful as the long-press trigger, and only to the element that saw the press begin.
        return if (armed && hasLongClick) Verdict.Pass else Verdict.Swallow
    }

    fun onUp(): Verdict {
        if (!armed) return Verdict.Swallow
        armed = false
        return Verdict.Pass
    }

    /** Focus left the element mid-press: whatever comes next is not ours. */
    fun reset() {
        armed = false
    }
}
