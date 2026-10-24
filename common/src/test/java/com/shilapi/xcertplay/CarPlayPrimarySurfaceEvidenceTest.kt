package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayPrimarySurfaceEvidenceTest {
    @Test fun movingThePrimaryOutputRequiresANewFrameOnTheNewSurface() {
        val oldSurface = Surface(SurfaceTexture(0))
        val newSurface = Surface(SurfaceTexture(1))
        val frames = mutableListOf<String>()
        val sink = AndroidMediaSink()
        sink.setVideoDiagnosticHandler(110) { frames += it }
        try {
            sink.setSurface(110, oldSurface)
            val decoder = decoder(sink)
            assertFalse(sink.hasRenderedVideoFrame(110, oldSurface))
            render(decoder)
            assertTrue(sink.hasRenderedVideoFrame(110, oldSurface))

            sink.setSurface(110, newSurface)
            assertFalse(sink.hasRenderedVideoFrame(110, oldSurface))
            assertFalse(sink.hasRenderedVideoFrame(110, newSurface))
            decoder.javaClass.getDeclaredMethod("changeSurface", Surface::class.java).apply { isAccessible = true }
                .invoke(decoder, newSurface)
            render(decoder)
            assertTrue(sink.hasRenderedVideoFrame(110, newSurface))
            assertEquals(2, frames.count { it == "first frame rendered" })
        } finally { sink.close(); oldSurface.release(); newSurface.release() }
    }

    @Test fun retiredDecodersAndMirrorsCannotReportTheSuccessorsPrimaryFrame() {
        val surface = Surface(SurfaceTexture(0))
        val mirror = Surface(SurfaceTexture(1))
        val oldFrames = mutableListOf<String>()
        val newFrames = mutableListOf<String>()
        val sink = AndroidMediaSink()
        sink.setVideoDiagnosticHandler(110) { oldFrames += it }
        try {
            sink.setSurface(110, surface)
            val staleReport = report(decoder(sink))
            sink.setVideoDiagnosticHandler(110) { newFrames += it }
            sink.setSurface(110, surface)
            staleReport("first frame rendered")
            assertTrue(oldFrames.isEmpty())
            assertTrue(newFrames.isEmpty())
            assertFalse(sink.hasRenderedVideoFrame(110, surface))

            sink.setMirrorSurface(110, "map", mirror)
            val mirrorDecoders = field<Map<*, *>>(sink, "mirrorDecoders")
            report(mirrorDecoders.values.single()!!)("first frame rendered")
            assertFalse(newFrames.contains("first frame rendered"))
            assertFalse(sink.hasRenderedVideoFrame(110, surface))

            render(decoder(sink))
            assertEquals(1, newFrames.count { it == "first frame rendered" })
            assertTrue(sink.hasRenderedVideoFrame(110, surface))
        } finally { sink.close(); surface.release(); mirror.release() }
    }

    private fun render(decoder: Any) {
        val codec = mock(MediaCodec::class.java)
        val callbacks = CopyOnWriteArrayList<MediaCodec.OnFrameRenderedListener>()
        doAnswer { callbacks.add(it.getArgument(0)); null }.`when`(codec).setOnFrameRenderedListener(any(), any())
        decoder.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.set(decoder, codec)
        decoder.javaClass.getDeclaredField("presentationTarget").apply { isAccessible = true }.set(decoder, null)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (callbacks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)
        assertFalse("the worker must attach the presentation listener", callbacks.isEmpty())
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        decoder.javaClass.getDeclaredMethod("drainOutput", MediaCodec::class.java).apply { isAccessible = true }
            .invoke(decoder, codec)
        verify(codec).releaseOutputBuffer(0, true)
        // Submitting output no longer proves display; complete the current binding's callback.
        callbacks.last().onFrameRendered(codec, 0, System.nanoTime())
    }

    private fun decoder(sink: AndroidMediaSink): Any = field<Map<Int, Any>>(sink, "videoDecoders").getValue(110)
    private fun report(decoder: Any): (String) -> Unit = field(decoder, "report")
    private fun <T> field(target: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    }
}
