package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AudioInputPumpTest {
    @Test fun transientInputStarvationPreservesPacketOrderAndTimestamps() {
        var heldOutputs = 2
        var time = 0L
        val accepted = mutableListOf<Pair<Int, Long>>()

        for (packet in 1..3) {
            val timestamp = packet * 21_333L
            val index = AudioInputPump.acquire(
                running = { true },
                dequeue = { if (heldOutputs > 0) -1 else 0 },
                drain = { heldOutputs-- },
                nanoTime = { time.also { time += 10 } },
                timeoutNs = 100,
            )
            assertEquals(0, index)
            accepted += packet to timestamp
            heldOutputs = 2
        }

        assertEquals(listOf(1 to 21_333L, 2 to 42_666L, 3 to 63_999L), accepted)
    }

    @Test fun availableInputDoesNotDrainUnnecessarily() {
        assertEquals(7, AudioInputPump.acquire(
            running = { true }, dequeue = { 7 }, drain = { fail("No backpressure") },
        ))
    }

    @Test fun shutdownDoesNotWaitForTheStallDeadline() {
        var running = true
        var drains = 0
        assertEquals(-1, AudioInputPump.acquire(
            running = { running }, dequeue = { -1 },
            drain = { drains++; running = false },
            nanoTime = { 0L }, timeoutNs = 100,
        ))
        assertEquals(1, drains)
        assertEquals(-1, AudioInputPump.acquire(
            running = { false }, dequeue = { fail("Already stopped"); 0 },
            drain = { fail("Already stopped") },
        ))
    }

    @Test fun shutdownDuringDequeueDoesNotSubmitAnotherPacket() {
        var running = true
        assertEquals(-1, AudioInputPump.acquire(
            running = { running }, dequeue = { running = false; 0 },
            drain = { fail("Stopped during dequeue") },
        ))
    }

    @Test fun permanentlyStalledDecoderFailsInsteadOfSilentlySkippingPackets() {
        var time = 0L
        var attempts = 0
        try {
            AudioInputPump.acquire(
                running = { true }, dequeue = { attempts++; -1 }, drain = {},
                nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
            )
            fail("The renderer must report an unresponsive codec")
        } catch (failure: IllegalStateException) {
            assertEquals("Audio decoder input stalled", failure.message)
        }
        assertEquals(3, attempts)
    }

    @Test fun codecFailurePropagatesToRendererCleanup() {
        val failure = IllegalStateException("Codec stopped")
        try {
            AudioInputPump.acquire(
                running = { true }, dequeue = { -1 }, drain = { throw failure },
            )
            fail("The renderer must see the original failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}
