package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keep the real sink/worker binding and diagnostics; replace only the vendor codec. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidVideoPresentationTest {
    @Test fun actualCallbacksMustBelongToTheCurrentCodecSurfaceAndSubmittedPicture() {
        val sink = AndroidMediaSink()
        val first = mock(Surface::class.java)
        val second = mock(Surface::class.java)
        val codec = mock(MediaCodec::class.java)
        val callbacks = CopyOnWriteArrayList<MediaCodec.OnFrameRenderedListener>()
        val reports = CopyOnWriteArrayList<String>()
        doAnswer { callbacks.add(it.getArgument(0)); null }.`when`(codec)
            .setOnFrameRenderedListener(any(), any())
        sink.setVideoDiagnosticHandler(110) { reports.add(it) }
        try {
            sink.setSurface(110, first)
            val worker = (field(sink, "videoDecoders") as Map<*, *>)[110]!!
            set(worker, "decoder", codec)
            await { callbacks.isNotEmpty() }
            val oldCallback = callbacks.last()
            oldCallback.onFrameRendered(codec, 100, System.nanoTime())
            assertFalse(sink.hasRenderedVideoFrame(110, first))
            releaseOutput(worker, codec, 100)
            assertFalse("output submission is not presentation", sink.hasRenderedVideoFrame(110, first))
            oldCallback.onFrameRendered(codec, 100, System.nanoTime())
            assertTrue(sink.hasRenderedVideoFrame(110, first))
            assertEquals(1, reports.count { it == "first frame rendered" })

            val previousTarget = field(worker, "presentationTarget")
            sink.setSurface(110, second)
            await { field(worker, "outputSurface") === second &&
                field(worker, "presentationTarget") !== previousTarget && callbacks.size >= 2 }
            val currentCallback = callbacks.last()
            oldCallback.onFrameRendered(codec, 100, System.nanoTime())
            currentCallback.onFrameRendered(codec, 100, System.nanoTime())
            assertFalse(sink.hasRenderedVideoFrame(110, second))
            releaseOutput(worker, codec, 200)
            currentCallback.onFrameRendered(mock(MediaCodec::class.java), 200, System.nanoTime())
            currentCallback.onFrameRendered(codec, 100, System.nanoTime())
            assertFalse(sink.hasRenderedVideoFrame(110, second))
            currentCallback.onFrameRendered(codec, 200, System.nanoTime())
            assertTrue(sink.hasRenderedVideoFrame(110, second))
            assertFalse(sink.hasRenderedVideoFrame(110, first))
            assertEquals(2, reports.count { it == "first frame rendered" })
            sink.close()
            currentCallback.onFrameRendered(codec, 200, System.nanoTime())
            assertFalse(sink.hasRenderedVideoFrame(110, second))
        } finally {
            sink.close()
            assertTrue(VideoSurfaceHandoff.releaseSurface(first))
            assertTrue(VideoSurfaceHandoff.releaseSurface(second))
        }
    }

    private fun releaseOutput(worker: Any, codec: MediaCodec, pts: Long) {
        var output = true
        `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            if (output) {
                output = false
                it.getArgument<MediaCodec.BufferInfo>(0).set(0, 20, pts, 0)
                0
            } else MediaCodec.INFO_TRY_AGAIN_LATER
        }
        worker.javaClass.getDeclaredMethod("drainOutput", MediaCodec::class.java)
            .apply { isAccessible = true }.invoke(worker, codec)
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition())
    }
}
