package com.shilapi.xcertplay.airplay.rcs.caf

import org.junit.Assert.*
import org.junit.Test

class CafTransactionTrackerTest {
    @Test
    fun peerRequestWithSameNumberDoesNotConsumeOutboundTransaction() {
        val tracker = CafTransactionTracker()
        val pending = tracker.begin(7, CafCommand.READ_REQUEST, setOf(CafCommand.READ_RESPONSE))
        assertNull(tracker.accept(CarAccessoryMessages.reader(
            CarAccessoryMessages.readRequest(7, pending.transactionId, listOf(9)).rcsBody,
        )))
        assertEquals(1, tracker.size())
        assertEquals(pending, tracker.accept(CarAccessoryMessages.reader(
            CarAccessoryMessages.readResponse(7, pending.transactionId, mapOf(9L to 20), emptyMap()).rcsBody,
        )))
        assertEquals(0, tracker.size())
    }

    @Test
    fun wrongPluginCannotCompleteTransactionAndPendingStateIsBounded() {
        val tracker = CafTransactionTracker(maxPending = 1)
        val pending = tracker.begin(7, CafCommand.READ_REQUEST, setOf(CafCommand.READ_RESPONSE))
        assertThrows(CafProtocolException::class.java) {
            tracker.accept(CarAccessoryMessages.reader(
                CarAccessoryMessages.readResponse(8, pending.transactionId, emptyMap(), emptyMap()).rcsBody,
            ))
        }
        assertThrows(CafProtocolException::class.java) {
            tracker.begin(7, CafCommand.READ_REQUEST, setOf(CafCommand.READ_RESPONSE))
        }
        tracker.clear()
        assertEquals(0, tracker.size())
    }
}
