package com.kirtanya.reel.core

import com.kirtanya.reel.core.launcher.CastHint
import com.kirtanya.reel.core.launcher.CastRequest
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tvremote.CastOutcome
import com.kirtanya.reel.core.tvremote.RemoteKeys
import com.kirtanya.reel.core.tvremote.RemoteLink
import com.kirtanya.reel.core.tvremote.WakeThenPlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WakeThenPlayTest {
    private val fast = WakeThenPlay.Timing(powerKnownMs = 50, wakeMs = 200, settleMs = 10, retryWakeMs = 200)

    /** A TV that [reportsPower] (or stays silent, like the user's TV in standby) and wakes on WAKEUP. */
    private class FakeTv(var on: Boolean?, val reportsPower: Boolean = true, val wakes: Boolean = true, var dropLinkOnce: Boolean = false, val reachable: Boolean = true) : RemoteLink {
        override val power = MutableStateFlow(if (reportsPower) on else null)
        val keys = mutableListOf<Int>()
        val links = mutableListOf<String>()
        override suspend fun connect() = reachable
        override suspend fun key(code: Int): Boolean {
            keys += code
            if (code == RemoteKeys.WAKEUP && wakes) { on = true; power.value = true }
            return true
        }
        override suspend fun appLink(link: String): Boolean {
            if (dropLinkOnce) { dropLinkOnce = false; return false }
            links += link
            return true
        }
    }

    @Test fun anAsleepTvThatSaysNothingIsStillWokenFirst(): Unit = runBlocking {
        val tv = FakeTv(on = false, reportsPower = false)
        val r = WakeThenPlay.run(tv, "onestream://cast/play?x", fast)
        assertEquals(CastOutcome.Sent(wokeUp = true), r)
        assertEquals(RemoteKeys.WAKEUP, tv.keys.first())
        assertEquals(listOf("onestream://cast/play?x"), tv.links)
    }

    @Test fun aTvThatIsOnGetsTheHarmlessWakeAndTheLinkAtOnce(): Unit = runBlocking {
        val tv = FakeTv(on = true)
        assertEquals(CastOutcome.Sent(wokeUp = false), WakeThenPlay.run(tv, "l", fast))
        assertEquals(listOf(RemoteKeys.WAKEUP), tv.keys) // never POWER: it could switch the TV off
    }

    @Test fun theSessionDroppedByWakingIsReopened(): Unit = runBlocking {
        val tv = FakeTv(on = false, dropLinkOnce = true)
        assertEquals(CastOutcome.Sent(wokeUp = true), WakeThenPlay.run(tv, "l", fast))
        assertEquals(listOf("l"), tv.links)
    }

    @Test fun unreachableAndNotWaking(): Unit = runBlocking {
        assertEquals(CastOutcome.Unreachable, WakeThenPlay.run(FakeTv(on = false, reachable = false), "l", fast))
        val stubborn = FakeTv(on = false, wakes = false, dropLinkOnce = true)
        stubborn.dropLinkOnce = true
        val r = WakeThenPlay.run(object : RemoteLink by stubborn {
            override suspend fun appLink(link: String) = false
        }, "l", fast)
        assertEquals(CastOutcome.DidNotWake, r)
        assertEquals(2, stubborn.keys.count { it == RemoteKeys.WAKEUP }) // tried twice
    }

    @Test fun linkCarriesThePhonesPosition(): Unit {
        val link = CastRequest.link(false, TitleRef(MediaType.TV, 1399), 1, 3, CastHint(hash = "a".repeat(40), file = 2), startMs = 754_000)
        assertTrue(link.endsWith("&t=754000"))
        val back = CastRequest.parse("play", link.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') })!!
        assertEquals(754_000L, back.startMs)
        // A download link never carries a position.
        assertEquals(null, CastRequest.parse("download", mapOf("type" to "movie", "tmdb" to "550", "t" to "1000"))!!.startMs)
    }
}
