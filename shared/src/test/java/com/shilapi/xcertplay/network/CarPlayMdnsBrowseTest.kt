package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarPlayMdnsBrowseTest {
    @Test fun refreshWaitsAtLeastOneSecondAndNeverStacksRoundsOnRepeatedTicks() {
        var now = 0L
        var calls = 0
        val browse = CarPlayMdnsBrowse({ calls++ }, { now }, { 250 })
        now = TimeUnit.MILLISECONDS.toNanos(1_249)
        assertFalse(browse.refreshIfDue())
        now += TimeUnit.MILLISECONDS.toNanos(1)
        assertTrue(browse.refreshIfDue())
        repeat(10) { assertFalse(browse.refreshIfDue()) }
        assertEquals(1, calls)
    }

    @Test fun intervalsDoubleWithJitterAndRemainBoundedAtSixtySeconds() {
        var now = 0L
        var calls = 0
        val browse = CarPlayMdnsBrowse({ calls++ }, { now }, { 250 })
        for (delay in listOf(1_250L, 2_250L, 4_250L, 8_250L, 16_250L, 32_250L, 60_000L, 60_000L)) {
            now += TimeUnit.MILLISECONDS.toNanos(delay - 1)
            assertFalse(browse.refreshIfDue())
            now += TimeUnit.MILLISECONDS.toNanos(1)
            assertTrue(browse.refreshIfDue())
        }
        assertEquals(8, calls)
    }

    @Test fun stoppingOldAddressGenerationDoesNotStopOrRefreshTheNewOne() {
        var now = 0L
        var oldCalls = 0
        var newCalls = 0
        val old = CarPlayMdnsBrowse({ oldCalls++ }, { now }, { 0 })
        old.stop()
        val current = CarPlayMdnsBrowse({ newCalls++ }, { now }, { 0 })
        now += TimeUnit.SECONDS.toNanos(1)
        assertFalse(old.refreshIfDue())
        assertTrue(current.refreshIfDue())
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test fun synchronousLibraryFailureStillBacksOff() {
        var now = 0L
        var calls = 0
        val browse = CarPlayMdnsBrowse({ calls++; error("registry rejected browse") }, { now }, { 0 })
        now = TimeUnit.SECONDS.toNanos(1)
        assertThrows(IllegalStateException::class.java) { browse.refreshIfDue() }
        assertFalse(browse.refreshIfDue())
        now += TimeUnit.MILLISECONDS.toNanos(1_999)
        assertFalse(browse.refreshIfDue())
        assertEquals(1, calls)
    }

    @Test fun recoveryAfterAResolvedEndpointHasThreeRoundsAcrossRepeatedPauseAndResume() {
        var now = 0L
        var calls = 0
        val browse = CarPlayMdnsBrowse({ calls++ }, { now }, { 0 })
        // A long initial browse must not postpone post-failure recovery by sixty seconds.
        repeat(8) { now += TimeUnit.SECONDS.toNanos(60); assertTrue(browse.refreshIfDue()) }
        browse.stop()
        browse.resume(3)
        for (delay in listOf(1L, 2L, 4L)) {
            now += TimeUnit.SECONDS.toNanos(delay)
            assertTrue(browse.refreshIfDue())
            browse.stop()
            browse.resume(3) // A subsequent failed endpoint cannot refill spent rounds.
        }
        now += TimeUnit.SECONDS.toNanos(60)
        assertFalse(browse.refreshIfDue())
        assertEquals(11, calls)
    }

    @Test fun monotonicClockWrapDoesNotTriggerAnEarlyRound() {
        var now = Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(500)
        var calls = 0
        val browse = CarPlayMdnsBrowse({ calls++ }, { now }, { 0 })
        assertFalse(browse.refreshIfDue())
        now += TimeUnit.MILLISECONDS.toNanos(1_000)
        assertTrue(browse.refreshIfDue())
        assertEquals(1, calls)
    }
}
