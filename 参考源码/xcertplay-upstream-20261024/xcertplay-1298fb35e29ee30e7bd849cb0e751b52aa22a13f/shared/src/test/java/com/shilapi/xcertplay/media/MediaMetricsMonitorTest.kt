package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetricsMonitorTest {
    @Test fun streamFramesAreCountedSeparatelyFromReleasedFrames() {
        val monitor = MediaMetricsMonitor()
        val now = 10_000_000_000L
        assertNull(monitor.snapshot(now).streamFps)
        repeat(60) { monitor.recordVideoFrameRendered(now - 900_000_000L + it, now - 500_000_000L + it) }
        repeat(25) { monitor.recordVideoFrameReceived(now - 400_000_000L + it) }
        // Older than the one-second window.
        monitor.recordVideoFrameReceived(now - 2_000_000_000L)
        val snapshot = monitor.snapshot(now)
        assertEquals(60f, snapshot.fps)
        assertEquals(25f, snapshot.streamFps)

        monitor.setEnabled(false)
        monitor.setEnabled(true)
        assertNull(monitor.snapshot(now).streamFps)
    }
}
