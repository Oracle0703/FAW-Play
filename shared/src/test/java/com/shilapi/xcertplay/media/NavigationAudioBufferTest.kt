package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioBufferTest {
    @Test fun hs5MonoNavigationCoversObservedGap() {
        val plan = NavigationAudioBuffer.plan(48_000, 1, 4_624)
        assertEquals(23_040, plan.startBytes)
        assertTrue("Starting reserve must cover the measured 188ms gap", plan.startBytes / 96 > 188)
        assertTrue(plan.trackBufferBytes / 96 >= 400)
        assertTrue(plan.startBytes < plan.trackBufferBytes)
    }

    @Test fun stereoFramesStayAligned() {
        val plan = NavigationAudioBuffer.plan(44_100, 2, 4_096)
        assertEquals(0, plan.startBytes % 4)
        assertEquals(0, plan.trackBufferBytes % 4)
    }

    @Test fun vendorMinimumAndActualCapacityAreRespected() {
        val plan = NavigationAudioBuffer.plan(48_000, 1, 32_768)
        assertEquals(131_072, plan.trackBufferBytes)
        assertEquals(32_768, plan.startBytes)
        val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, 8_192, 1_024)
        assertTrue(threshold <= 8_192 - 1_024)
    }
}
