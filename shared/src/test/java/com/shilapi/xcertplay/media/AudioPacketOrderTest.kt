package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioPacketOrderTest {
    @Test fun normalPacketsNeverWaitForPrefill() {
        val order = AudioPacketOrder<String>(40_000_000)
        assertEquals(listOf("one"), order.offer(1, "one", 0))
        assertEquals(0, order.receiveTimeoutMillis(0))
        assertEquals(listOf("two"), order.offer(2, "two", 1))
    }

    @Test fun outOfOrderPacketWaitsOnlyUntilItsMissingPredecessorArrives() {
        val order = AudioPacketOrder<Int>(40_000_000)
        order.offer(10, 10, 0)
        assertTrue(order.offer(12, 12, 1).isEmpty())
        assertEquals(listOf(11, 12), order.offer(11, 11, 2))
        assertTrue(order.offer(12, 12, 3).isEmpty())
        assertEquals(1L, order.duplicatesOrLate)
        assertEquals(0L, order.missing)
    }

    @Test fun gapDeadlineSkipsMissingPacketsAndDoesNotReplayALateArrival() {
        val order = AudioPacketOrder<Int>(40_000_000)
        order.offer(1, 1, 0)
        order.offer(3, 3, 1)
        assertTrue(order.flush(40_000_000).isEmpty())
        assertEquals(listOf(3), order.flush(40_000_001))
        assertEquals(1L, order.missing)
        assertTrue(order.offer(2, 2, 50_000_000).isEmpty())
    }

    @Test fun authenticatedSourceChangeResetsSequenceAndRetiredSourceCannotSwitchBack() {
        val order = AudioPacketOrder<String>(40_000_000)
        assertEquals(listOf("old"), order.offer(1000, "old", 0, 1))
        assertEquals(listOf("new"), order.offer(2, "new", 1, 2))
        assertTrue(order.offer(1001, "delayed-old", 2, 1).isEmpty())
        assertTrue(order.offer(1, "late-same-source", 3, 2).isEmpty())
        assertEquals(listOf("current"), order.offer(3, "current", 4, 2))
        assertEquals(1L, order.sourceChanges)
        assertEquals(1L, order.retiredSourcePackets)
        assertEquals(1L, order.duplicatesOrLate)
    }

    @Test fun sequenceWrapAndCapacityCannotTrapTheReceiver() {
        val order = AudioPacketOrder<Int>(40_000_000, 2)
        assertEquals(listOf(65535), order.offer(65535, 65535, 0))
        assertTrue(order.offer(1, 1, 1).isEmpty())
        assertEquals(listOf(0, 1), order.offer(0, 0, 2))
        order.offer(3, 3, 3)
        assertEquals(listOf(3, 4), order.offer(4, 4, 4))
        assertEquals(1L, order.missing)
    }

    @Test fun gracefulEndDrainsEveryFinalGapWithoutWaitingForAnotherPacket() {
        val order = AudioPacketOrder<Int>(40_000_000)
        assertEquals(listOf(10), order.offer(10, 10, 0))
        assertTrue(order.offer(14, 14, 1).isEmpty())
        assertTrue(order.offer(12, 12, 2).isEmpty())
        assertEquals(listOf(12, 14), order.drain(3))
        assertEquals(2L, order.missing)
        assertTrue(order.drain(4).isEmpty())
    }
}
