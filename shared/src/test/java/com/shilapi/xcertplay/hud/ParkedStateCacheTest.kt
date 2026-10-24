package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedStateCacheTest {
    @Test fun onlyFreshParkAuthorizesPlayback() {
        val state = ParkedStateCache()
        assertNull(state.parked(1_000))
        state.update(0, 1_000)
        assertTrue(state.parked(1_500) == true)
        assertNull(state.parked(1_501))
        assertNull(state.parked(999))
    }

    @Test fun changingGearImmediatelyRevokesParkWithoutAnySpeedSamples() {
        val state = ParkedStateCache()
        for (gear in 1..8) {
            state.update(0, 1_000)
            assertTrue(state.parked(1_000) == true)
            state.update(gear, 1_100)
            assertFalse(state.parked(1_100) == true)
            assertEquals(false, state.parked(1_100))
        }
    }

    @Test fun invalidGearAndDisconnectClearPreviousParkPermission() {
        val state = ParkedStateCache()
        for (unknown in listOf(-1, 9, Int.MAX_VALUE)) {
            state.update(0, 1_000)
            state.update(unknown, 1_100)
            assertNull(state.parked(1_100))
        }
        state.update(0, 1_200)
        state.clear()
        assertNull(state.parked(1_200))
    }
}
