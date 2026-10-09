package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.atomic.AtomicInteger

import android.media.MediaCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoDecoderThreadCountTest {
    @Test fun recoverableCodecFailuresKeepTheWorkerCountUntilActualThreadExit() {
        val liveThreads = ReflectionHelpers.getStaticField<AtomicInteger>(
            Class.forName("com.shilapi.xcertplay.media.PlaybackResourceSampler"), "liveDecoderThreads")
        val baseline = liveThreads.get()
        var failed = CountDownLatch(1)
        val sink = AndroidMediaSink(surface = null)
        sink.setVideoDiagnosticHandler(110) { if (it.startsWith("decoder failed")) failed.countDown() }
        sink.onVideoFrame(110, byteArrayOf())
        val decoders = ReflectionHelpers.getField<Map<Int, Any>>(sink, "videoDecoders")
        val worker = decoders.getValue(110)
        val thread = ReflectionHelpers.getField<Thread>(worker, "thread")
        try {
            waitUntil { liveThreads.get() == baseline + 1 }
            repeat(2) {
                failed = CountDownLatch(1)
                val codec = mock(MediaCodec::class.java)
                `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
                    .thenThrow(IllegalStateException("recoverable codec failure"))
                ReflectionHelpers.setField(worker, "decoder", codec)
                sink.onVideoFrame(110, byteArrayOf())
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                waitUntil { thread.state == Thread.State.TIMED_WAITING }
                assertTrue(thread.isAlive)
                assertEquals(baseline + 1, liveThreads.get())
            }
        } finally {
            sink.close()
            thread.join(3_000)
        }
        assertFalse(thread.isAlive)
        assertEquals(baseline, liveThreads.get())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var reached = condition()
        while (!reached && System.nanoTime() < deadline) {
            Thread.sleep(5)
            reached = condition()
        }
        assertTrue("Decoder worker did not reach the expected state", reached)
    }
}
