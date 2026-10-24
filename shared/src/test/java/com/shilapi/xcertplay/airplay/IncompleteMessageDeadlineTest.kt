package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IncompleteMessageDeadlineTest {
    @Test fun idleHasNoTimerAndDrippingBytesCannotRenewTheFirstByteDeadline() {
        val clock = TestClock()
        val deadline = clock.deadline()
        deadline.update(null)
        clock.advance(10_000)
        deadline.check()
        assertTrue(clock.jobs.isEmpty())
        deadline.update(clock.now)
        val first = clock.now
        repeat(3) { clock.advance(25); deadline.update(first) }
        assertEquals(1, clock.jobs.size)
        clock.advance(25)
        clock.jobs.single().task.run()
        assertEquals(1, clock.closed)
        assertThrows(SocketTimeoutException::class.java) { deadline.check() }
        assertTrue(deadline.failureOr(IOException("socket closed")) is SocketTimeoutException)
        deadline.close()
    }

    @Test fun aCancelledOldTaskCannotCloseTheNextMessageOrAnIdleConnection() {
        val clock = TestClock()
        val deadline = clock.deadline()
        deadline.update(clock.now)
        val oldTask = clock.jobs.single()
        clock.advance(50)
        deadline.update(null)
        deadline.update(clock.now)
        assertTrue(oldTask.cancelled)
        clock.advance(50)
        oldTask.task.run() // Force execution even though the executor would normally cancel it.
        assertEquals(0, clock.closed)
        deadline.update(null)
        clock.advance(500)
        clock.jobs.forEach { it.task.run() }
        assertEquals(0, clock.closed)
        deadline.close()
    }

    @Test fun delayedWatchdogCannotAcceptAnOverdueMessageCompletion() {
        val clock = TestClock()
        val deadline = clock.deadline()
        deadline.update(clock.now)
        clock.advance(101)
        assertThrows(SocketTimeoutException::class.java) { deadline.update(null) }
        assertEquals(1, clock.closed)
        deadline.close()
    }

    @Test fun movingPayloadToTheInnerParserCannotResetItsAlreadyElapsedLifetime() {
        val clock = TestClock()
        val deadline = clock.deadline()
        clock.advance(101)
        assertThrows(SocketTimeoutException::class.java) { deadline.update(0) }
        assertEquals(1, clock.closed)
        assertTrue(clock.jobs.isEmpty())
        deadline.close()
    }

    @Test fun earlySchedulerWakeKeepsTheOriginalAbsoluteDeadline() {
        val clock = TestClock()
        val deadline = clock.deadline()
        deadline.update(clock.now)
        clock.advance(20)
        clock.jobs[0].task.run()
        assertEquals(TimeUnit.MILLISECONDS.toNanos(80), clock.jobs[1].delay)
        assertEquals(0, clock.closed)
        clock.advance(80)
        clock.jobs[1].task.run()
        assertEquals(1, clock.closed)
        deadline.close()
    }

    @Test fun ordinaryFailuresAndExplicitCloseKeepTheirOwnMeaning() {
        val clock = TestClock()
        val deadline = clock.deadline()
        val failure = IOException("authentication failed")
        assertSame(failure, deadline.failureOr(failure))
        deadline.update(clock.now)
        deadline.close()
        clock.advance(500)
        clock.jobs.forEach { it.task.run() }
        assertEquals(0, clock.closed)
        assertSame(failure, deadline.failureOr(failure))
    }

    @Test fun authenticatedFrameMetadataDropsHeadersAndTagsButPreservesPayloadArrival() {
        val frame = frame(4)
        val wireTimes = MessageByteTimes()
        wireTimes.append(1, 10_000_000)
        wireTimes.append(2, 20_000_000) // Second header byte and first payload byte.
        wireTimes.append(frame.size - 3, 30_000_000)
        val messageTimes = MessageByteTimes()
        wireTimes.transferAuthenticatedFrames(frame, frame.size, messageTimes)
        assertNull(wireTimes.firstNanos)
        assertEquals(20_000_000L, messageTimes.firstNanos)
        messageTimes.consume(1)
        assertEquals(30_000_000L, messageTimes.firstNanos)
        messageTimes.consume(3)
        assertNull(messageTimes.firstNanos)
    }

    @Test fun coalescedMessagesAndIncompleteNextFrameKeepTheirOwnFirstByteTimes() {
        val first = frame(5)
        val second = frame(6)
        val wire = first + second
        val wireTimes = MessageByteTimes()
        wireTimes.append(first.size + 4, 10_000_000)
        wireTimes.append(second.size - 4, 20_000_000)
        val messageTimes = MessageByteTimes()
        wireTimes.transferAuthenticatedFrames(wire, first.size, messageTimes)
        messageTimes.consume(5)
        assertNull(messageTimes.firstNanos)
        assertEquals(10_000_000L, wireTimes.firstNanos)
        wireTimes.transferAuthenticatedFrames(second, second.size, messageTimes)
        assertEquals(10_000_000L, messageTimes.firstNanos)
        messageTimes.consume(2)
        assertEquals(20_000_000L, messageTimes.firstNanos)
    }

    @Test fun millionsOfLegalTinyFragmentsDoNotExhaustArrivalMetadata() {
        val times = MessageByteTimes()
        repeat(8 * 1024 * 1024) { times.append(1, it.toLong()) }
        times.consume(8 * 1024 * 1024 - 1)
        val actualFinalArrival = 8 * 1024 * 1024 - 1L
        val retained = times.firstNanos!!
        assertTrue(actualFinalArrival - retained in 0L until 1_000_000L)
        times.consume(1)
        assertNull(times.firstNanos)
    }

    @Test fun consumingAnOldMessageCannotTransferItsAgeToALaterTimeBucket() {
        val times = MessageByteTimes()
        times.append(100, 0)
        times.append(100, 500_000) // Same bucket, bounded conservative age.
        times.append(1, 59_999_000_000)
        times.consume(200)
        assertEquals(59_999_000_000L, times.firstNanos)
    }

    @Test fun timeRoundingDoesNotAccumulateAcrossFramesAndParserLayers() {
        val wireTimes = MessageByteTimes()
        val messageTimes = MessageByteTimes()
        val frame = frame(1)
        wireTimes.append(frame.size, 900_000)
        wireTimes.transferAuthenticatedFrames(frame, frame.size, messageTimes)
        wireTimes.append(frame.size, 1_800_000)
        wireTimes.transferAuthenticatedFrames(frame, frame.size, messageTimes)
        messageTimes.consume(1)
        assertEquals(1_000_000L, messageTimes.firstNanos)
        val negativeTimes = MessageByteTimes()
        negativeTimes.append(1, -1)
        assertEquals(-1_000_000L, negativeTimes.firstNanos)
    }

    @Test fun extremeNegativeTimeBucketsNeverOverflowOrRoundTwice() {
        for (arrival in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 999_999)) {
            val wireTimes = MessageByteTimes()
            val messageTimes = MessageByteTimes()
            val frame = frame(1)
            wireTimes.append(frame.size, arrival)
            val first = wireTimes.firstNanos!!
            assertTrue(arrival - first in 0L until 1_000_000L)
            wireTimes.transferAuthenticatedFrames(frame, frame.size, messageTimes)
            assertEquals(first, messageTimes.firstNanos)
        }
    }

    private fun frame(payloadBytes: Int) = ByteArray(2 + payloadBytes + 16).apply {
        this[0] = payloadBytes.toByte(); this[1] = (payloadBytes ushr 8).toByte()
    }

    private class TestClock {
        data class Job(val delay: Long, val task: Runnable, var cancelled: Boolean = false)
        var now = 0L
        var closed = 0
        val jobs = mutableListOf<Job>()
        fun advance(millis: Long) { now += TimeUnit.MILLISECONDS.toNanos(millis) }
        fun deadline() = IncompleteMessageDeadline("test", 100, { now }, { delay, task ->
            val job = Job(delay, task).also(jobs::add)
            Closeable { job.cancelled = true }
        }) { closed++ }
    }
}
