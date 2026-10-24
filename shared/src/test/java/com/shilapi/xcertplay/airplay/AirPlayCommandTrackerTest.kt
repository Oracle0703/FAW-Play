package com.shilapi.xcertplay.airplay

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class AirPlayCommandTrackerTest {
    @Test fun responsesAreMatchedByCseqAndWritesDoNotClaimPeerAcceptance() {
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker { lines.add(it) }
        tracker.queued(1, mapOf("type" to "forceKeyFrame"), 1)
        tracker.queued(2, mapOf("type" to "showUI", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
            "url" to "private-ui-url")), 1)
        tracker.written(1); tracker.written(2)
        assertTrue(lines.all { "response=pending" in it })
        tracker.response(response(2, 400), 10)
        tracker.response(response(1, 200), 11)
        assertTrue(lines[2].contains("cseq=2 type=showUI screen=alternate"))
        assertTrue(lines[2].contains("response=rejected"))
        assertTrue(lines[3].contains("cseq=1 type=forceKeyFrame"))
        assertTrue(lines[3].contains("response=acknowledged"))
        assertTrue(lines.none { "private-ui-url" in it })
        assertTrue(lines.takeLast(2).all { "effect=unmeasured" in it })
        tracker.close()
        assertEquals(4, lines.size)
    }

    @Test fun missingAckIsExpiredAndNonzeroBodyStatusIsARejection() {
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker { lines.add(it) }
        tracker.queued(1, mapOf("type" to "forceKeyFrame"), 1)
        tracker.written(1)
        tracker.queued(2, mapOf("type" to "showUI"), 10_000_000_001)
        assertTrue(lines.last().contains("response=response-timeout"))
        tracker.response(response(2, 200).copy(body = BplistCodec.encode(mapOf("status" to 7))), 10_000_000_002)
        assertTrue(lines.last().contains("response=rejected status=200 result=7"))
    }

    @Test fun replacingAnEventChannelRetiresPendingResponsesWithoutReusingTheirCseq() {
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker { lines.add(it) }
        tracker.queued(1, mapOf("type" to "showUI"), 1)
        tracker.written(1)
        tracker.reset("event-replaced")
        assertTrue(lines.last().contains("cseq=1"))
        assertTrue(lines.last().contains("response=event-replaced"))
        tracker.queued(2, mapOf("type" to "forceKeyFrame"), 2)
        tracker.written(2)
        val beforeOldResponse = lines.size
        tracker.response(response(1, 200), 3)
        assertEquals(beforeOldResponse, lines.size)
        tracker.response(response(2, 200), 4)
        assertTrue(lines.last().contains("cseq=2 type=forceKeyFrame"))
        assertTrue(lines.last().contains("response=acknowledged"))
        tracker.close()
    }

    @Test fun aMissingResponseExpiresWithoutAnyFurtherMessagesOrCommands() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker(clock::now, clock::schedule) { lines.add(it) }
        tracker.queued(1, mapOf("type" to "showUI"))
        tracker.written(1)
        assertEquals(1, clock.active.size)
        clock.nanos = 10 * SECOND
        clock.runDue()
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("cseq=1"))
        assertTrue(lines.last().contains("response=response-timeout"))
        assertTrue(clock.active.isEmpty())
        tracker.response(response(1, 200))
        assertEquals("A late ACK cannot revive an expired command", 2, lines.size)
        tracker.close()
    }

    @Test fun anEarlyWakeKeepsTheOriginalDeadlineAndOnlyTheEarliestPendingTimerIsArmed() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker(clock::now, clock::schedule) { lines.add(it) }
        tracker.queued(1, mapOf("type" to "showUI"))
        clock.nanos = 2 * SECOND
        tracker.queued(2, mapOf("type" to "forceKeyFrame"))
        assertEquals(1, clock.active.size)
        assertEquals(10 * SECOND, clock.active.single().deadline)
        clock.nanos = 9 * SECOND
        clock.active.single().fire() // Simulate a scheduler that wakes before its deadline.
        assertTrue(lines.isEmpty())
        assertEquals(1, clock.active.size)
        assertEquals(10 * SECOND, clock.active.single().deadline)
        clock.nanos = 10 * SECOND
        clock.runDue()
        assertTrue(lines.single().contains("cseq=1"))
        assertEquals(12 * SECOND, clock.active.single().deadline)
        clock.nanos = 11 * SECOND
        tracker.response(response(2, 200))
        assertTrue(lines.last().contains("cseq=2"))
        assertTrue(lines.last().contains("response=acknowledged"))
        assertTrue(clock.active.isEmpty())
        tracker.close()
    }

    @Test fun resetAndCloseInvalidateOldScheduledCallbacksAndCloseRejectsNewPendingCommands() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val tracker = AirPlayCommandTracker(clock::now, clock::schedule) { lines.add(it) }
        tracker.queued(1, mapOf("type" to "showUI"))
        val retired = clock.active.single()
        tracker.reset("event-replaced")
        assertTrue(clock.active.isEmpty())
        clock.nanos = SECOND
        tracker.queued(2, mapOf("type" to "showUI"))
        clock.nanos = 10 * SECOND
        retired.fire() // Cancellation can race an already dispatched callback.
        assertEquals(1, lines.size)
        assertEquals(11 * SECOND, clock.active.single().deadline)
        tracker.close()
        val afterClose = lines.size
        clock.nanos = 30 * SECOND
        clock.tasks.forEach { it.fire() }
        tracker.queued(3, mapOf("type" to "showUI"))
        tracker.written(3)
        tracker.response(response(3, 200))
        assertEquals(afterClose, lines.size)
        assertTrue(clock.active.isEmpty())
    }

    @Test fun theCapacityStaysBoundedAndExpiryReportsRunOutsideTheTrackerLock() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val reportHeldLock = mutableListOf<Boolean>()
        lateinit var tracker: AirPlayCommandTracker
        tracker = AirPlayCommandTracker(clock::now, clock::schedule) {
            val lock = tracker.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(tracker)
            reportHeldLock.add(Thread.holdsLock(lock))
            lines.add(it)
        }
        repeat(65) { tracker.queued(it, mapOf("type" to "showUI")) }
        assertTrue(lines.single().contains("cseq=0"))
        assertTrue(lines.single().contains("response=evicted"))
        assertEquals(1, clock.active.size)
        clock.nanos = 10 * SECOND
        clock.runDue()
        assertEquals(64, lines.count { "response=response-timeout" in it })
        assertTrue(clock.active.isEmpty())
        assertTrue(reportHeldLock.none { it })
        tracker.close()
    }

    private class FakeClock {
        var nanos = 0L
        val tasks = mutableListOf<Task>()
        val active: List<Task> get() = tasks.filter { !it.cancelled && !it.completed }
        fun now(): Long = nanos
        fun schedule(delay: Long, runnable: Runnable): Closeable {
            val task = Task(nanos + delay, runnable)
            tasks.add(task)
            return Closeable { task.cancelled = true }
        }
        fun runDue() {
            while (true) {
                val task = active.firstOrNull { it.deadline <= nanos } ?: return
                task.fire()
            }
        }
        class Task(val deadline: Long, private val runnable: Runnable) {
            var cancelled = false
            var completed = false
            fun fire() { completed = true; runnable.run() }
        }
    }

    private fun response(cseq: Int, status: Int) = RtspMessage.Request("RTSP/1.0", status.toString(), "OK",
        mapOf("cseq" to cseq.toString()), ByteArray(0))

    companion object { private const val SECOND = 1_000_000_000L }
}
