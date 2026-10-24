package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AdaptiveAudioReserveTest {
    @Test fun reserveNeverUndercutsExistingFiveHundredMillisecondSetting() {
        val reserve = AdaptiveAudioReserve(500, 800, 80, 0)
        assertEquals(500, reserve.targetMillis)
        repeat(10) { reserve.stable((it + 1) * 60_000_000_000L) }
        assertEquals(500, reserve.targetMillis)
        assertTrue(reserve.underrun(1_000_000))
        assertEquals(580, reserve.targetMillis)
        repeat(10) { reserve.underrun(2_000_000) }
        assertEquals(800, reserve.targetMillis)
    }

    @Test fun stableRecoveryIsSlowAndNewUnderrunResetsItsWindow() {
        val reserve = AdaptiveAudioReserve(500, 800, 80, 0)
        reserve.underrun(0)
        assertFalse(reserve.stable(29_000_000_000))
        assertTrue(reserve.stable(30_000_000_000))
        assertEquals(560, reserve.targetMillis)
        assertFalse(reserve.stable(39_000_000_000))
        assertTrue(reserve.stable(40_000_000_000))
        reserve.underrun(41_000_000_000)
        assertFalse(reserve.stable(70_000_000_000))
        assertTrue(reserve.stable(71_000_000_000))
    }

    @Test fun callsAndAssistantDoNotAcquireAMusicSizedReserve() {
        assertNull(AdaptiveAudioReserve.forChannel(AudioChannel.PHONE, 500))
        assertNull(AdaptiveAudioReserve.forChannel(AudioChannel.ASSISTANT, 500))
        assertEquals(240, AdaptiveAudioReserve.forChannel(AudioChannel.NAVIGATION, 500)!!.minimumMillis)
        assertEquals(500, AdaptiveAudioReserve.forChannel(AudioChannel.MEDIA, 500)!!.minimumMillis)
    }

    @Test fun enlargedTargetStillFitsAnActualSmallVendorTrack() {
        assertTrue(MediaAudioBuffer.startBytesFor(153600, 4096, 2048) <= 4096)
        assertTrue(MediaAudioBuffer.startBytesFor(153600, 1024, 2048) <= 1024)
    }
}
