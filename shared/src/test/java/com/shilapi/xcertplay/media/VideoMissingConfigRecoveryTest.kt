package com.shilapi.xcertplay.media

import android.view.Surface
import android.graphics.SurfaceTexture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoMissingConfigRecoveryTest {
    @Test fun frameWithoutConfigRequestsAndRetriesOnlyItsOwnStream() {
        val requests = LinkedBlockingQueue<Int>()
        val diagnostics = LinkedBlockingQueue<String>()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink()
        try {
            for (type in listOf(110, 111)) sink.setVideoRecoveryHandler(type) { requests.offer(type) }
            sink.setVideoDiagnosticHandler(111) { diagnostics.offer(it) }
            sink.setSurface(111, surface)
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS))
            sink.onVideoFrame(111, byteArrayOf(0, 0, 0, 1, 0x26, 1))
            assertEquals(111, requests.poll(3, TimeUnit.SECONDS))
            assertEquals("frames without codec config; requesting keyframe", diagnostics.poll(3, TimeUnit.SECONDS))
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(111, requests.poll(2, TimeUnit.SECONDS))
            assertNull(diagnostics.poll(100, TimeUnit.MILLISECONDS))
        } finally { sink.close(); surface.release(); texture.release() }
    }

    @Test fun missingConfigWaitsForSurfaceThenRecoversWithoutAnotherFrame() {
        val requests = LinkedBlockingQueue<Int>()
        val diagnostics = LinkedBlockingQueue<String>()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink()
        try {
            sink.setVideoRecoveryHandler(110) { requests.offer(110) }
            sink.setVideoDiagnosticHandler(110) { diagnostics.offer(it) }
            sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65, 1))
            assertNotNull(diagnostics.poll(3, TimeUnit.SECONDS))
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS))
            sink.setSurface(110, surface)
            assertEquals(110, requests.poll(3, TimeUnit.SECONDS))
        } finally { sink.close(); surface.release(); texture.release() }
    }
}
