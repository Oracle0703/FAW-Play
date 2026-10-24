package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class P2pChannelRestrictionTest {
    private var pending = false
    private var owned = true
    private val calls = mutableListOf<Int>()
    private val logs = mutableListOf<String>()
    private val accepted: (Int, (Boolean) -> Unit) -> Unit = { value, callback -> calls.add(value); callback(true) }
    private fun acquire(set: (Int, (Boolean) -> Unit) -> Unit = accepted, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)) =
        P2pChannelRestriction.acquire("test", 36, deadline, { pending }, { pending = it }, { owned }, set, logs::add)
    private fun release() = P2pChannelRestriction.release("test", { pending }, { pending = it }, { owned }, accepted, logs::add)

    @Test fun acceptedSelectionHasRestorationResponsibilityUntilRelease() {
        try { assertEquals(36, acquire()); assertTrue(pending); assertEquals(listOf(36), calls) }
        finally { release() }
        assertEquals(listOf(36, 0), calls); assertFalse(pending)
    }
    @Test fun explicitFailureFallsBackOnlyAfterAClearingAcknowledgement() {
        val selection = acquire({ value, callback -> calls.add(value); callback(value == 0) })
        assertEquals(0, selection); assertEquals(listOf(36, 0), calls); assertFalse(pending)
        assertTrue(logs.any { "clean_auto" in it })
    }
    @Test fun setterAndCleanupTimeoutCannotBeCalledCleanAuto() {
        try {
            val error = runCatching { acquire({ value, _ -> calls.add(value) }, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40)) }.exceptionOrNull()
            assertNotNull(error); assertTrue(pending); assertFalse(logs.any { "clean_auto" in it })
        } finally { release() }
    }
    @Test fun channelLossDoesNotWaitInsideSetterSerializationOrDiscardCleanupResponsibility() {
        val failure = P2pTransientFailure("Channel recovering")
        try {
            assertSame(failure, assertThrows(P2pTransientFailure::class.java) {
                acquire({ value, _ -> calls.add(value); throw failure })
            })
            assertEquals(listOf(36), calls)
            assertTrue(pending)
        } finally { release() }
        assertFalse(pending)
    }
    @Test fun nextAttemptClearsAnUnfinishedPriorProcessRestrictionFirst() {
        pending = true
        try { assertEquals(36, acquire()); assertEquals(listOf(0, 36), calls) }
        finally { release() }
    }
    @Test fun systemAutoClearsThePreviousRestrictionOnceBeforeReturning() {
        acquire()
        val selection = P2pChannelRestriction.acquire("test", 0,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(3), { pending }, { pending = it },
            { owned }, accepted, logs::add)
        assertEquals(0, selection)
        assertEquals(listOf(36, 0), calls)
        assertFalse(pending)
        release()
        assertEquals(listOf(36, 0), calls)
    }
    @Test fun replacementOwnerIsNeverBlindlyCleared() {
        acquire()
        owned = false; release()
        assertEquals(listOf(36), calls); assertTrue(pending)
        owned = true; release()
    }
}
