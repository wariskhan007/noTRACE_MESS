package com.notrace.messenger.network

import com.notrace.messenger.network.signaling.ReconnectBackoff
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure math, no Android/network dependency - a plain JUnit test, unlike almost everything else touching SignalingClient. */
class ReconnectBackoffTest {

    @Test
    fun delayDoublesEachAttempt_untilCapped() {
        assertEquals(2_000L, ReconnectBackoff.delayForAttempt(0))
        assertEquals(4_000L, ReconnectBackoff.delayForAttempt(1))
        assertEquals(8_000L, ReconnectBackoff.delayForAttempt(2))
        assertEquals(16_000L, ReconnectBackoff.delayForAttempt(3))
    }

    @Test
    fun delayNeverExceedsMax() {
        assertEquals(30_000L, ReconnectBackoff.delayForAttempt(4)) // would be 32s uncapped
        assertEquals(30_000L, ReconnectBackoff.delayForAttempt(10))
        assertEquals(30_000L, ReconnectBackoff.delayForAttempt(20))
    }
}
