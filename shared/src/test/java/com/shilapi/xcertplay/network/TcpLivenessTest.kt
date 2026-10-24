package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class TcpLivenessTest {
    @Test fun unsupportedOptionDoesNotPreventOtherOptionsBeingApplied() {
        val values = mutableMapOf<Int, Int>()
        val lines = mutableListOf<String>()
        TcpLiveness.configureOptions(true, set = { option, value ->
            if (option == 4) throw UnsupportedOperationException()
            values[option] = value
        }, read = { values.getValue(it) }, diagnostic = lines::add)
        assertEquals(3, values.size)
        assertTrue(lines.any { it.contains("idleSeconds requested=1 unavailable=") })
        assertTrue(lines.any { it.contains("probeCount requested=2 effective=2") })
        assertTrue(lines.any { it.contains("userTimeoutMillis requested=3000 effective=3000") })
    }

    @Test fun kernelMismatchAndReadFailureAreNotReportedAsSuccess() {
        val lines = mutableListOf<String>()
        TcpLiveness.configureOptions(true, set = { _, _ -> }, read = {
            if (it == 5) throw UnsupportedOperationException()
            99
        }, diagnostic = lines::add)
        assertEquals(4, lines.size)
        assertTrue(lines.any { it.contains("idleSeconds requested=1 effective=99") })
        assertTrue(lines.any { it.contains("intervalSeconds requested=1 readbackUnavailable=") })
        assertFalse(lines.any { it.contains("effective=1") })
    }

    @Test fun wiredPolicyKeepsExistingTiming() {
        val values = mutableMapOf<Int, Int>()
        val lines = mutableListOf<String>()
        TcpLiveness.configureOptions(false, { option, value -> values[option] = value }, { values.getValue(it) }, lines::add)
        assertTrue(lines.any { it.contains("idleSeconds requested=2 effective=2") })
        assertTrue(lines.any { it.contains("probeCount requested=3 effective=3") })
        assertTrue(lines.any { it.contains("userTimeoutMillis requested=5000 effective=5000") })
    }
}
