package com.shilapi.xcertplay

import android.view.Surface
import android.view.SurfaceHolder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class VideoSurfaceViewTest {
    @Test fun destroyedCallbackWaitsUntilTheRetiringDecoderFinishesNativeRelease() {
        val sink = com.shilapi.xcertplay.media.AndroidMediaSink()
        var attached: Surface? = null
        val view = VideoSurfaceView(RuntimeEnvironment.getApplication(), { surface ->
            if (surface != null) { attached = surface; sink.setSurface(110, surface) }
            else attached?.let { sink.clearSurface(110, it); attached = null }
        })
        val callback = shadowOf(view).fakeSurfaceHolder.callbacks.single()
        val holder = mock(SurfaceHolder::class.java)
        val surface = mock(Surface::class.java)
        `when`(holder.surface).thenReturn(surface)
        val releaseEntered = java.util.concurrent.CountDownLatch(1)
        val releaseAllowed = java.util.concurrent.CountDownLatch(1)
        val destroyedReturned = java.util.concurrent.CountDownLatch(1)
        var destroyer: Thread? = null
        try {
            callback.surfaceCreated(holder)
            val workers = sink.javaClass.getDeclaredField("videoDecoders").apply { isAccessible = true }.get(sink) as Map<*, *>
            val worker = workers[110]!!
            val codec = mock(android.media.MediaCodec::class.java)
            doAnswer {
                releaseEntered.countDown()
                // Worker close interrupts its thread; the fake native release still must finish.
                Thread.interrupted()
                check(releaseAllowed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                null
            }.`when`(codec).release()
            worker.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.set(worker, codec)
            destroyer = Thread { callback.surfaceDestroyed(holder); destroyedReturned.countDown() }.apply { start() }
            assertTrue(releaseEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(destroyedReturned.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            releaseAllowed.countDown()
            assertTrue(destroyedReturned.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, (worker.javaClass.getDeclaredField("exited").apply { isAccessible = true }.get(worker)
                as java.util.concurrent.CountDownLatch).count)
            verify(surface, never()).release()
        } finally { releaseAllowed.countDown(); destroyer?.join(3_000); view.close(); sink.close() }
    }

    @Test fun holderOwnsTheSurfaceAndRevokedViewsCannotAttachAgain() {
        val callbacks = mutableListOf<Surface?>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val view = VideoSurfaceView(RuntimeEnvironment.getApplication(), callbacks::add,
            { width, height -> sizes.add(width to height) })
        val fake = shadowOf(view).fakeSurfaceHolder
        val callback = fake.callbacks.single()
        val surface = mock(Surface::class.java)
        val holder = mock(SurfaceHolder::class.java)
        `when`(holder.surface).thenReturn(surface)

        callback.surfaceCreated(holder)
        callback.surfaceCreated(holder)
        assertEquals(listOf(surface), callbacks)
        callback.surfaceChanged(holder, 0, 1920, 720)
        callback.surfaceChanged(holder, 0, 1280, 720)
        assertEquals(listOf(1920 to 720, 1280 to 720), sizes)
        callback.surfaceDestroyed(holder)
        assertEquals(listOf(surface, null), callbacks)
        verify(surface, never()).release()

        callback.surfaceCreated(holder)
        view.close()
        view.close()
        callback.surfaceCreated(holder)
        callback.surfaceChanged(holder, 0, 100, 100)
        callback.surfaceDestroyed(holder)
        assertEquals(listOf(surface, null, surface, null), callbacks)
        assertEquals(2, sizes.size)
        assertTrue(fake.callbacks.isEmpty())
        verify(surface, never()).release()
    }
}
