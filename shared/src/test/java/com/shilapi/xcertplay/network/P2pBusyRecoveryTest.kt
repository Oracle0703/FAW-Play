package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class P2pBusyRecoveryTest {
    private var time = 0L
    private fun millis(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)

    @Test fun busyRecoveryUsesIncreasingDelaysAndAtMostSixSubmissions() {
        val budget = P2pBusyRecovery(millis(20_000)) { time }
        val delays = mutableListOf<Long>()
        repeat(6) { index ->
            budget.submitted()
            val delay = budget.busyDelayNanos()
            if (index == 5) assertNull(delay)
            else { delays += requireNotNull(delay); time += delay }
        }
        assertEquals(listOf(250L, 500L, 1000L, 1000L, 1000L).map(::millis), delays)
        assertEquals(6, budget.submissions)
        assertThrows(P2pTransientFailure::class.java) { budget.submitted() }
    }

    @Test fun aDelayedRetryCannotSubmitAfterTheFiveSecondBusyWindow() {
        val budget = P2pBusyRecovery(millis(20_000)) { time }
        budget.submitted()
        assertEquals(millis(250), budget.busyDelayNanos())
        time = millis(5_000)
        assertThrows(P2pTransientFailure::class.java) { budget.submitted() }
        assertNull(budget.busyDelayNanos())
        assertEquals(1, budget.submissions)
    }

    @Test fun busyRecoveryNeverExtendsTheOriginalStartupDeadline() {
        val budget = P2pBusyRecovery(millis(100)) { time }
        budget.submitted()
        assertEquals(millis(100), budget.busyDelayNanos())
        time = millis(100)
        assertThrows(P2pTransientFailure::class.java) { budget.submitted() }
        assertNull(budget.busyDelayNanos())
    }
}
