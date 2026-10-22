package com.kirtanya.reel.core.tvremote

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the cast flow needs from the TV remote connection (TvRemote on Android; a fake in tests). */
interface RemoteLink {
    /** Screen on (true), off / standby (false), not reported yet (null). */
    val power: StateFlow<Boolean?>
    /** Opens the remote session (no-op when already connected). False = TV not reachable. */
    suspend fun connect(): Boolean
    suspend fun key(code: Int): Boolean
    suspend fun appLink(link: String): Boolean
}

sealed interface CastOutcome {
    /** The link reached the TV; [wokeUp] = the TV was off / unknown and we woke it first. */
    data class Sent(val wokeUp: Boolean) : CastOutcome
    /** Couldn't open the remote session (TV unplugged, deep sleep without network, other Wi-Fi). */
    data object Unreachable : CastOutcome
    /** The TV took the wake key but never reported its screen on, and the link failed. */
    data object DidNotWake : CastOutcome
}

/**
 * Phone → TV: wake the TV, wait until its screen is on, then open [link] there.
 *
 * The user's TV in standby often doesn't report its power state in time, so "wake only when it says
 * it's off" (One Launcher's first version) sent the link to a sleeping TV, where it was lost. WAKEUP
 * never turns a TV off, so it's always sent: harmless when the TV is on, and the fix when it's asleep.
 */
object WakeThenPlay {
    data class Timing(
        /** How long to wait for the TV to say whether its screen is on. */
        val powerKnownMs: Long = 1_500,
        /** How long a waking TV gets to report its screen on. */
        val wakeMs: Long = 10_000,
        /** After waking, the launcher needs a moment before it takes app links. */
        val settleMs: Long = 2_000,
        /** A second wake key if the first didn't take, then this much longer. */
        val retryWakeMs: Long = 6_000,
    )

    suspend fun run(remote: RemoteLink, link: String, t: Timing = Timing()): CastOutcome {
        if (!remote.connect()) return CastOutcome.Unreachable
        withTimeoutOrNull(t.powerKnownMs) { remote.power.first { it != null } }
        val wasOn = remote.power.value == true
        remote.key(RemoteKeys.WAKEUP)
        if (!wasOn) {
            var on = withTimeoutOrNull(t.wakeMs) { remote.power.first { it == true } } != null
            if (!on) {
                // Some TVs drop the first key while the network wakes; the remote session may have closed too.
                remote.connect()
                remote.key(RemoteKeys.WAKEUP)
                on = withTimeoutOrNull(t.retryWakeMs) { remote.power.first { it == true } } != null
            }
            delay(t.settleMs)
            if (send(remote, link)) return CastOutcome.Sent(wokeUp = true)
            return if (on) CastOutcome.Unreachable else CastOutcome.DidNotWake
        }
        return if (send(remote, link)) CastOutcome.Sent(wokeUp = false) else CastOutcome.Unreachable
    }

    /** Waking can reset the TV's network and close our session: reconnect once and resend. */
    private suspend fun send(remote: RemoteLink, link: String): Boolean =
        remote.appLink(link) || (remote.connect() && remote.appLink(link))
}
