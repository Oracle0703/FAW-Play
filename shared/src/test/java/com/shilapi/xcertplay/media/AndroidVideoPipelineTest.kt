package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidVideoPipelineTest {
    @Test fun aConfiguredInitialPictureIsHeldWhileTheSurfaceIsAbsentAndCloseReleasesBackpressure() {
        val sink = AndroidMediaSink()
        var producer: Thread? = null
        val finished = CountDownLatch(1)
        try {
            sink.onVideoConfig(110, VideoTestConfig.avcRecord())
            val receive = sink.prepareVideoFrame(110)
            val decoder = decoders(sink)[110]!!
            receive(VideoTestConfig.start + byteArrayOf(0x65, 1))
            await { field(decoder, "heldFrame") != null }
            val held = field(decoder, "heldFrame")
            // Beyond the old 250 ms cutoff: Surface readiness must not discard this first picture.
            Thread.sleep(300)
            assertSame(held, field(decoder, "heldFrame"))
            repeat(8) { receive(VideoTestConfig.start + byteArrayOf(0x41, 1)) }
            producer = Thread { receive(VideoTestConfig.start + byteArrayOf(0x41, 2)); finished.countDown() }.apply { start() }
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            sink.onScreenStreamActive(110, false)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(decoders(sink).isEmpty())
        } finally { sink.close(); producer?.join(1_000) }
    }

    @Test fun aCapturedReceiverCannotReviveAClosedStreamOrSubmitToItsReplacement() {
        val sink = AndroidMediaSink()
        try {
            val old = sink.prepareVideoFrame(110)
            val first = decoders(sink)[110]!!
            sink.onScreenStreamActive(110, false)
            old(VideoTestConfig.start + byteArrayOf(0x65, 1))
            assertTrue(decoders(sink).isEmpty())
            sink.prepareVideoFrame(110)
            val second = decoders(sink)[110]!!
            assertNotSame(first, second)
            old(VideoTestConfig.start + byteArrayOf(0x65, 1))
            assertSame(second, decoders(sink)[110])
            assertEquals(0, (field(second, "queue") as VideoWorkQueue<*>).size)
        } finally { sink.close() }
    }

    @Test fun virtualAltScreenHasNoInvisiblePrimaryAndItsMirrorKeepsConsumingBeyondQueueCapacity() {
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        val sink = AndroidMediaSink()
        var producer: Thread? = null
        val finished = CountDownLatch(1)
        try {
            sink.onVideoConfig(111, VideoTestConfig.avcRecord())
            assertFalse(decoders(sink).containsKey(111))
            sink.setMirrorSurface(111, "card", surface)
            val receive = sink.prepareVideoFrame(111)
            producer = Thread {
                repeat(16) { receive(VideoTestConfig.start + byteArrayOf(0x65, 1)) }
                finished.countDown()
            }.apply { start() }
            assertTrue("invisible primary must not block the mirror", finished.await(3, TimeUnit.SECONDS))
            assertFalse(decoders(sink).containsKey(111))
            assertEquals(1, (field(sink, "mirrorDecoders") as Map<*, *>).size)
        } finally {
            sink.close(); producer?.join(1_000)
            assertTrue(VideoSurfaceHandoff.releaseSurface(surface))
            surface.release(); texture.release()
        }
    }

    @Test fun closedSinkRejectsLateConfigSurfaceAndCapturedReceivers() {
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        val sink = AndroidMediaSink()
        val receive = sink.prepareVideoFrame(110)
        sink.close()
        try {
            receive(VideoTestConfig.start + byteArrayOf(0x65, 1))
            sink.setSurface(110, surface)
            sink.setMirrorSurface(111, "card", surface)
            sink.onVideoConfig(110, VideoTestConfig.avcRecord())
            sink.prepareVideoFrame(110)(VideoTestConfig.start + byteArrayOf(0x65, 1))
            assertTrue(decoders(sink).isEmpty())
            assertTrue((field(sink, "mirrorDecoders") as Map<*, *>).isEmpty())
        } finally { VideoSurfaceHandoff.releaseSurface(surface); surface.release(); texture.release() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decoders(sink: AndroidMediaSink) = field(sink, "videoDecoders") as Map<Int, Any>
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }

    @Test fun surfaceReleaseConfirmsActualWorkerExitIncludingRetiringDecoders() {
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        val sink = AndroidMediaSink(surface)
        sink.onVideoConfig(110, VideoTestConfig.avcRecord())
        val worker = decoders(sink)[110]!!
        val thread = field(worker, "thread") as Thread
        try {
            sink.close() // The retiring worker has already left the sink map.
            assertTrue(VideoSurfaceHandoff.releaseSurface(surface))
            assertEquals(0, (field(worker, "exited") as CountDownLatch).count)
            thread.join(1_000)
            assertFalse(thread.isAlive)
        } finally { sink.close(); surface.release(); texture.release() }
    }

    @Test fun pendingMainRecoveryDoesNotSuppressTheAlternateScreen() {
        val sink = AndroidMediaSink()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val alternate = CountDownLatch(1)
        sink.setVideoRecoveryHandler(110) { entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)); true }
        sink.setVideoRecoveryHandler(111) { alternate.countDown(); true }
        val request = sink.javaClass.getDeclaredMethod("requestVideoRecovery", Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val completed: (Boolean) -> Unit = {}
        try {
            assertEquals(true, request.invoke(sink, 110, completed))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(true, request.invoke(sink, 111, completed))
            assertEquals(false, request.invoke(sink, 110, completed))
            resume.countDown()
            assertTrue(alternate.await(3, TimeUnit.SECONDS))
        } finally { resume.countDown(); sink.close() }
    }

    @Test fun failedTailRefreshCanRetryOnceTheEventChannelBecomesReady() {
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        val sink = AndroidMediaSink()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val writes = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        sink.setVideoRecoveryHandler(110) { ready.get().also { writes.offer(it) } }
        sink.setSurface(110, surface)
        val worker = decoders(sink)[110]!!
        try {
            (field(worker, "outputProgress") as VideoOutputProgress).onInput(1, System.nanoTime() - 1_000_000_000)
            worker.javaClass.getDeclaredField("needsKeyFrame").apply { isAccessible = true }.setBoolean(worker, false)
            assertEquals(false, writes.poll(3, TimeUnit.SECONDS))
            ready.set(true)
            assertEquals(true, writes.poll(3, TimeUnit.SECONDS))
            assertNull("A successful static-tail refresh must not loop", writes.poll(1_200, TimeUnit.MILLISECONDS))
            TouchLatencyProbe.onTouchSent(System.nanoTime(), 0)
            assertEquals("A new user interaction permits another refresh", true, writes.poll(3, TimeUnit.SECONDS))
            assertNull("The new interaction still permits only one refresh", writes.poll(1_200, TimeUnit.MILLISECONDS))
        } finally {
            sink.close(); assertTrue(VideoSurfaceHandoff.releaseSurface(surface))
            surface.release(); texture.release()
        }
    }
}
