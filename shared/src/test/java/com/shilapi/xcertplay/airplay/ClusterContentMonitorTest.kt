package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

/** Fake-clock checks: a parked static image must never become a reconnect watchdog. */
class ClusterContentMonitorTest {
    @Test fun retriesBeginOnlyAfterTheCurrentRequestWasWrittenAndEndAfterThreeAttempts() {
        val monitor = ClusterContentMonitor()
        monitor.begin(1, Any(), true)
        val request = monitor.request(1, true)!!
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, request.revision, 100 * SECOND))
        assertEquals(1, monitor.snapshot()!!.attempts)
        monitor.written(1, request.revision, SECOND)
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, request.revision, 6 * SECOND - 1))
        assertEquals(ClusterContentMonitor.Action.RETRY, monitor.check(1, request.revision, 6 * SECOND))
        monitor.written(1, request.revision, 6 * SECOND)
        assertEquals(ClusterContentMonitor.Action.RETRY, monitor.check(1, request.revision, 11 * SECOND))
        monitor.written(1, request.revision, 11 * SECOND)
        assertEquals(3, monitor.snapshot()!!.attempts)
        assertEquals(ClusterContentMonitor.Action.REPORT, monitor.check(1, request.revision, 16 * SECOND))
        assertEquals("no-valid-video", monitor.snapshot()!!.failure)
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, request.revision, 100 * SECOND))
    }

    @Test fun eitherAReceivedPictureOrARenderedFrameStopsRetriesEvenIfNoMoreFramesArrive() {
        for (rendered in listOf(false, true)) {
            val source = Any()
            val monitor = ClusterContentMonitor()
            monitor.begin(1, source, true)
            val request = monitor.request(1, true)!!
            assertNull(if (rendered) monitor.rendered(source) else monitor.received(source))
            monitor.written(1, request.revision, SECOND)
            val progress = if (rendered) monitor.rendered(source) else monitor.received(source)
            assertNotNull(progress)
            assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, request.revision, 1_000 * SECOND))
            assertNull(monitor.snapshot()!!.failure)
            assertEquals(1, monitor.snapshot()!!.attempts)
        }
    }

    @Test fun retiredSourceAndOldTimersCannotSatisfyOrRetryANewStream() {
        val oldSource = Any()
        val currentSource = Any()
        val monitor = ClusterContentMonitor()
        monitor.begin(1, oldSource, true)
        val old = monitor.request(1, true)!!
        monitor.written(1, old.revision, SECOND)
        monitor.begin(2, currentSource, true)
        val current = monitor.request(2, true)!!
        monitor.written(2, current.revision, SECOND)
        assertNull(monitor.received(oldSource))
        assertNull(monitor.rendered(oldSource))
        assertNull(monitor.written(1, old.revision, 2 * SECOND))
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, old.revision, 100 * SECOND))
        assertFalse(monitor.snapshot()!!.validVideoReceived)
        assertNotNull(monitor.received(currentSource))
    }

    @Test fun hidingOrClearingInvalidatesAllOutstandingChecks() {
        val source = Any()
        val monitor = ClusterContentMonitor()
        monitor.begin(1, source, true)
        val show = monitor.request(1, true)!!
        monitor.written(1, show.revision, SECOND)
        val hide = monitor.request(1, false)!!
        monitor.written(1, hide.revision, 2 * SECOND)
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, show.revision, 100 * SECOND))
        assertEquals(ClusterContentMonitor.Action.NONE, monitor.check(1, hide.revision, 100 * SECOND))
        assertNull(monitor.received(source))
        assertEquals(0, monitor.snapshot()!!.attempts)
        monitor.clear()
        assertNull(monitor.snapshot())
        assertNull(monitor.rendered(source))
    }

    @Test fun explicitNewRequestStartsAFreshFiniteBudgetWithoutClaimingRenderedPixels() {
        val source = Any()
        val monitor = ClusterContentMonitor()
        monitor.begin(1, source, true)
        val first = monitor.request(1, true)!!
        monitor.written(1, first.revision, SECOND)
        monitor.check(1, first.revision, 20 * SECOND)
        val next = monitor.request(1, true)!!
        assertTrue(next.revision > first.revision)
        assertNull(next.failure)
        assertFalse(next.requestWritten)
        monitor.written(1, next.revision, 20 * SECOND)
        val received = monitor.received(source)!!
        assertTrue(received.validVideoReceived)
        assertFalse(received.frameRendered)
    }

    companion object { private const val SECOND = 1_000_000_000L }
}
