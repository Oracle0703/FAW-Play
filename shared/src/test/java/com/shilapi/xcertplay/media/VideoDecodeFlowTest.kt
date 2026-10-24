package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeFlowTest {
    @Test fun fullQueueBackpressuresTheProducerAndPreservesEveryReferenceInOrder() {
        val queue = VideoWorkQueue<Int>(2)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        queue.frame(1)
        queue.frame(2)
        val producer = Thread { entered.countDown(); queue.frame(3); finished.countDown() }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, queue.poll(0))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(2, queue.poll(0))
            assertEquals(3, queue.poll(0))
            assertNull(queue.poll(0))
        } finally { queue.close(); producer.join(1_000) }
    }

    @Test fun closingAFullQueueReleasesTheBlockedProducerAndRejectsStaleSubmissions() {
        val queue = VideoWorkQueue<Int>(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        queue.frame(1)
        val producer = Thread { entered.countDown(); queue.frame(2); finished.countDown() }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            queue.close()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            queue.frame(3)
            queue.control(4)
            assertNull(queue.poll(0))
        } finally { queue.close(); producer.join(1_000) }
    }

    @Test fun controlMessagesDoNotNeedAFrameSlotOrDiscardQueuedFrames() {
        val queue = VideoWorkQueue<String>(1)
        try {
            queue.frame("keyframe")
            queue.control("config")
            assertEquals("keyframe", queue.poll(0))
            assertEquals("config", queue.poll(0))
        } finally { queue.close() }
    }

    @Test fun inputStarvationRetainsTheSamePictureAndTimestampWhileContinuingToDrain() {
        var available = false
        var drains = 0
        val accepted = mutableListOf<Pair<ByteArray, Long>>()
        val frame = byteArrayOf(1, 2, 3)
        val pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() { drains++ }
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                if (available) accepted.add(bytes to presentationTimeUs)
                return available
            }
        })
        pump.submit(frame, 123)
        repeat(10) { pump.tick() }
        assertTrue(pump.hasPending)
        assertEquals(20, drains)
        assertTrue(accepted.isEmpty())
        available = true
        pump.tick()
        assertFalse(pump.hasPending)
        assertEquals(1, accepted.size)
        assertSame(frame, accepted.single().first)
        assertEquals(123L, accepted.single().second)
        pump.tick()
        assertEquals(1, accepted.size)
        assertEquals(24, drains)
    }

    @Test fun aStaticPageStillDrainsOutputWithoutAnotherInputPicture() {
        var drains = 0
        val pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() { drains++ }
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean { fail(); return false }
        })
        pump.tick()
        assertEquals(2, drains)
    }

    @Test fun aPendingPictureCannotBeOverwrittenByTheNextPicture() {
        val pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() {}
            override fun queue(bytes: ByteArray, presentationTimeUs: Long) = false
        })
        pump.submit(byteArrayOf(1), 1)
        assertThrows(IllegalStateException::class.java) { pump.submit(byteArrayOf(2), 2) }
    }

    @Test fun avcWaitsForIdrAndThenAcceptsDependentPictures() {
        val gate = VideoSyncGate(VideoCodec.H264)
        assertFalse(gate.accept(nal(0x41)))
        assertTrue(gate.waitingForRandomAccess)
        assertTrue(gate.accept(nal(0x65)))
        assertFalse(gate.waitingForRandomAccess)
        assertTrue(gate.accept(nal(0x41)))
    }

    @Test fun hevcCraSkipsRaslWithoutLosingSyncAndResumesAtTrailingPictures() {
        val gate = VideoSyncGate(VideoCodec.H265)
        assertFalse(gate.accept(nal(1 shl 1)))
        assertTrue(gate.accept(nal(21 shl 1)))
        assertFalse(gate.accept(nal(8 shl 1)))
        assertFalse(gate.accept(nal(9 shl 1)))
        assertFalse(gate.waitingForRandomAccess)
        assertTrue(gate.accept(nal(1 shl 1)))
        assertTrue(gate.accept(nal(8 shl 1)))
    }

    @Test fun invalidNalHeadersDoNotUnlockTheReferenceChain() {
        val gate = VideoSyncGate(VideoCodec.H265)
        for (bytes in listOf(byteArrayOf(0, 0, 0, 1), nal(0x80), byteArrayOf(0, 0, 0, 1, 0x26, 0))) {
            assertThrows(IllegalArgumentException::class.java) { gate.accept(bytes) }
            assertTrue(gate.waitingForRandomAccess)
        }
    }

    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, type.toByte(), 1, 0x80.toByte())
}
