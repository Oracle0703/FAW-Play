package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoStatsTest {
    @Test fun codecDeliveryCannotPretendToBePresentationAndWindowCountersAreBounded() {
        var clock = 1L
        val stats = VideoStats(nanoTime = { clock })
        stats.onReceived(100); stats.onRendered()
        stats.onQueueAge(120_000_000); stats.onInputWait(35_000_000)
        clock += 5_000_000_000
        val first = stats.logIfDue()!!
        assertTrue(first.contains("frameRenderedFps=0.0"))
        assertTrue(first.contains("codecDelivered=shown"))
        assertTrue(first.contains("queueAgeMaxMs=120")); assertTrue(first.contains("inputWaitMaxMs=35"))
        stats.onReceived(100); stats.onPresented(clock); clock += 50_000_000; stats.onPresented(clock)
        clock += 5_000_000_000
        val next = stats.logIfDue()!!
        assertTrue(next.contains("frameRenderedMaxGapMs=50")); assertTrue(next.contains("queueAgeMaxMs=0"))
        assertTrue(next.contains("presentationEnds=codec_frame_rendered"))
    }
    @Test fun longGapsAndZeroFrameWindowsRemainObservableWithPendingCodecOutput() {
        var clock = 1L
        val stats = VideoStats(label = " stream=111", nanoTime = { clock })
        stats.onReceived(100)
        stats.onCodecInput(1, clock)
        clock += 5_000_000_000
        val quiet = stats.logIfDue()!!
        assertTrue(quiet.contains("outputPending=1"))
        assertTrue(quiet.contains("outputPendingAgeMs=5000"))
        assertTrue(quiet.contains("receiveIdleMs=5000"))
        assertTrue(quiet.contains("gapClassification=unknown"))
        clock += 5_000_000_000
        assertNotNull(stats.logIfDue())
        stats.onReceived(100)
        stats.onCodecOutput(1)
        clock += 5_000_000_000
        val next = stats.logIfDue()!!
        assertTrue(next.contains("maxGap=10000ms"))
        assertTrue(next.contains("outputPending=0"))
        assertTrue(next.contains("decodeMaxMs=10000"))
    }

}
