package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class CarPlayControlProbeBudgetTest {
    private val first = CarPlayControlEndpointKey("phone", "192.0.2.20", 7000, false)

    @Test fun rediscoveringAnEndpointRetainsItsSpentAttempts() {
        val budgets = CarPlayControlProbeBudget()
        val attempts = budgets.forEndpoint(first, 7)!!
        repeat(3) { assertEquals(it + 1, attempts.take()) }
        assertEquals(1, budgets.forEndpoint(first.copy(port = 7001), 7)!!.take())
        val rediscovered = budgets.forEndpoint(first, 7)!!
        assertSame(attempts, rediscovered)
        for (expected in 4..7) assertEquals(expected, rediscovered.take())
        assertFalse(rediscovered.available())
        assertNull(rediscovered.take())
        assertNull(budgets.forEndpoint(first, 7)!!.take())
    }

    @Test fun changedAddressPortAndFamilyHaveIndependentBoundedAttempts() {
        val budgets = CarPlayControlProbeBudget()
        for (endpoint in listOf(first, first.copy(host = "192.0.2.21"), first.copy(port = 7001),
            first.copy(host = "fe80::20%7", ipv6 = true))) {
            val attempts = budgets.forEndpoint(endpoint, 1)!!
            assertEquals(1, attempts.take())
            assertNull(attempts.take())
        }
    }

    @Test fun endpointChurnIsBoundedButExistingAndNewRequestBudgetsRemainUsable() {
        val budgets = CarPlayControlProbeBudget(maximumEndpoints = 2)
        val attempts = budgets.forEndpoint(first, 1)!!
        budgets.forEndpoint(first.copy(port = 7001), 1)!!
        assertNull(budgets.forEndpoint(first.copy(port = 7002), 1))
        assertSame(attempts, budgets.forEndpoint(first, 7))
        assertEquals(1, attempts.take())
        assertNull(attempts.take())
        budgets.clear()
        assertEquals(1, budgets.forEndpoint(first, 1)!!.take())
    }
}
