package com.kirtanya.reel.core.report

/**
 * Turns accelerometer readings into "the phone was shaken on purpose": two firm jolts (above
 * [thresholdG]) at least [minGapMs] apart and within [windowMs] of each other. One bump, walking or
 * putting the phone down makes a single jolt and doesn't count. After a shake, [cooldownMs] of quiet.
 */
class ShakeGate(
    private val thresholdG: Float = 2.6f,
    private val minGapMs: Long = 120,
    private val windowMs: Long = 800,
    private val cooldownMs: Long = 3_000,
) {
    private var firstJoltAt = -1L
    private var lastJoltAt = -1L
    private var shakenAt = Long.MIN_VALUE / 2

    /** Feed one reading ([g] = total acceleration in g, 1.0 at rest) at [timeMs]; true once per shake. */
    fun feed(g: Float, timeMs: Long): Boolean {
        if (timeMs - shakenAt < cooldownMs) return false
        if (g < thresholdG) return false
        // Readings above the threshold within minGapMs belong to the same jolt.
        if (lastJoltAt >= 0 && timeMs - lastJoltAt < minGapMs) {
            lastJoltAt = timeMs
            return false
        }
        if (firstJoltAt < 0 || timeMs - firstJoltAt > windowMs) {
            firstJoltAt = timeMs
            lastJoltAt = timeMs
            return false
        }
        firstJoltAt = -1
        lastJoltAt = -1
        shakenAt = timeMs
        return true
    }
}
