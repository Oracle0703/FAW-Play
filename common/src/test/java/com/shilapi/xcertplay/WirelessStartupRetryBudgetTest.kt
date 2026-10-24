package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessAddressRecovery
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessStartupRetryBudgetTest {
    @Test fun wholeStartupDeadlineSurvivesRetriesRestorationAndRepeatedBegin() {
        val budget = WirelessStartupRetryBudget()
        val due = budget.begin(100)
        budget.nextDelayMillis()
        budget.disconnected()
        assertEquals(due, budget.begin(40_000))
        val restored = WirelessStartupRetryBudget()
        restored.restore(budget.retries, budget.addressRecovery.snapshot(), budget.deadlineMillis)
        assertEquals(due, restored.begin(80_000))
        restored.connected()
        assertNull(restored.deadlineMillis)
        assertEquals(200_000L, restored.begin(80_000))
        restored.manualRetry()
        assertEquals(210_000L, restored.begin(90_000))
    }
    @Test fun ordinaryRetriesAndRestorationKeepConsumedFamilyButManualUseResetsIt() {
        val budget = WirelessStartupRetryBudget()
        val saved = WirelessAddressRecovery.Snapshot("IPv6", true)
        budget.restore(1, saved)
        budget.disconnected()
        assertEquals(2_000L, budget.nextDelayMillis())
        assertEquals(saved, budget.addressRecovery.snapshot())

        val replacement = WirelessStartupRetryBudget()
        replacement.restore(budget.retries, budget.addressRecovery.snapshot())
        assertEquals(saved, replacement.addressRecovery.snapshot())
        val oldToken = replacement.addressRecovery.token()
        replacement.manualRetry()
        assertEquals(WirelessAddressRecovery.Snapshot(), replacement.addressRecovery.snapshot())
        assertFalse(replacement.addressRecovery.confirmed(oldToken, InetAddress.getByName("192.0.2.1")))
        assertEquals(1_000L, replacement.nextDelayMillis())
    }

    @Test fun stopsAfterFiveRetriesWithFasterBoundedBackoff() {
        val budget = WirelessStartupRetryBudget()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L), List(5) { budget.nextDelayMillis() })
        assertNull(budget.nextDelayMillis())
        assertEquals(5, budget.retries)
        budget.manualRetry()
        assertEquals(1_000L, budget.nextDelayMillis())
    }

    @Test fun onlyContinuousVideoSessionResetsBudget() {
        val budget = WirelessStartupRetryBudget()
        budget.nextDelayMillis()
        val one = Any()
        assertFalse(budget.resetIfStable(one, 120_000))
        assertTrue(budget.firstFrame(one, 100))
        assertFalse(budget.firstFrame(one, 200))
        assertFalse(budget.resetIfStable(one, 60_099))
        budget.disconnected()
        assertFalse(budget.resetIfStable(one, 60_100))
        val two = Any()
        assertTrue(budget.firstFrame(two, 70_000))
        assertFalse(budget.resetIfStable(one, 130_000))
        assertTrue(budget.resetIfStable(two, 130_000))
        assertEquals(0, budget.retries)
    }
}
