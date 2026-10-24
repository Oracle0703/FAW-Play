package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoFrameTraceTest {
    @get:Rule val temp = TemporaryFolder()
    private val csd = byteArrayOf(0, 0, 0, 1, 0x67, 1)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 2)
    private val delta = byteArrayOf(0, 0, 0, 1, 0x41, 3)

    @Test fun randomAccessPreRollAndLiveAuOffsetsRetainTheExactPtsAndPresentationChain() {
        val root = temp.newFolder()
        val trace = VideoFrameTrace(root)
        trace.onConfig(VideoCodec.H264, listOf(csd))
        trace.onFrame(idr, 10, 11, 1)
        trace.onInput(1, 12)
        trace.onOutput(1, 13)
        trace.onPresent(1, 14)
        trace.onFrame(delta, 20, 21, 2)
        assertTrue(trace.startCapture(7, 100))
        // Same AU in the wire's length-prefixed representation.
        trace.onFrame(byteArrayOf(0, 0, 0, 2, 0x41, 3), 101, 102, 3)
        trace.onInput(3, 103)
        trace.onOutput(3, 104)
        trace.onPresent(3, 105)
        trace.close()
        assertTrue(trace.awaitClosed(2_000))

        assertArrayEquals(csd + idr + delta + delta, root.single("h264").readBytes())
        val rows = root.single("tsv").readLines().map { it.split('\t') }
        val frames = rows.filter { it[0] == "frame" }
        assertEquals(listOf("1", "2", "3"), frames.map { it[2] })
        assertEquals(listOf("6", "12", "18"), frames.map { it[8] })
        assertEquals(listOf("6", "6", "6"), frames.map { it[9] })
        assertEquals(listOf("preroll", "preroll", "live"), frames.map { it[11] })
        assertEquals(listOf("12", "13", "14"), frames[0].subList(5, 8))
        for ((event, column, expected) in listOf(Triple("submit", 5, "103"), Triple("output", 6, "104"), Triple("present", 7, "105"))) {
            val row = rows.single { it[0] == event && it[2] == "3" }
            assertEquals("2", row[1])
            assertEquals(expected, row[column])
        }
    }

    @Test fun byteAndSessionBudgetsRejectMoreDataWithoutProducingOversizedFiles() {
        val root = temp.newFolder()
        val limits = VideoFrameTrace.Limits(bytes = 22, sessionBytes = 40, preRollBytes = 8, preRollFrames = 2)
        val trace = VideoFrameTrace(root, limits)
        trace.onConfig(VideoCodec.H264, listOf(csd))
        trace.onFrame(idr, 1, 1, 1)
        assertTrue(trace.startCapture(1, 10))
        trace.onFrame(delta, 11, 11, 2)
        trace.onFrame(delta, 12, 12, 3) // Reserve + two live AUs would exceed 22 bytes.
        assertTrue(trace.startCapture(1, 20))
        trace.onFrame(delta, 21, 21, 4)
        assertFalse(trace.startCapture(1, 30)) // Cumulative session reservation exhausted.
        trace.close()
        assertTrue(trace.awaitClosed(2_000))
        val videos = root.listFiles()!!.filter { it.extension == "h264" }
        assertEquals(2, videos.size)
        assertTrue(videos.all { it.length() <= 22 })
        assertTrue(videos.sumOf { it.length() } <= 40)
        assertTrue(root.listFiles()!!.filter { it.extension == "tsv" }.any { it.readText().contains("\tbytes\n") })
    }

    @Test fun timeAndCaptureLimitsResetOnlyForANewSession() {
        val root = temp.newFolder()
        val limits = VideoFrameTrace.Limits(windowNs = 100, preRollBytes = 0, preRollFrames = 0)
        val trace = VideoFrameTrace(root, limits)
        trace.onConfig(VideoCodec.H265, listOf(csd))
        assertTrue(trace.startCapture(1, 100))
        trace.onFrame(delta, 150, 149, 1)
        trace.onFrame(delta, 200, 199, 2)
        assertTrue(trace.startCapture(1, 300))
        assertTrue(trace.startCapture(1, 400))
        assertFalse(trace.startCapture(1, 500))
        assertTrue(trace.startCapture(2, 600))
        trace.close()
        assertTrue(trace.awaitClosed(2_000))
        assertEquals(4, root.listFiles()!!.count { it.extension == "h265" })
        val first = File(root, "cp-1-100-1.tsv").readText()
        assertTrue(first.contains("frame\t0\t1\t150\t149"))
        assertFalse(first.contains("frame\t1\t2"))
        assertTrue(first.contains("\tduration\n"))
    }

    @Test fun aBlockedWriterDropsAtCapacityAndPlaybackCallsStillReturn() {
        val root = temp.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val limits = VideoFrameTrace.Limits(queuedBytes = 12, queuedTasks = 2, preRollBytes = 0, preRollFrames = 0)
        val trace = VideoFrameTrace(root, limits) { line ->
            reports.add(line)
            if (line.startsWith("start ")) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        }
        trace.onConfig(VideoCodec.H264, listOf(csd))
        assertTrue(trace.startCapture(1, 10))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val producer = Thread {
            repeat(3) { trace.onFrame(delta, 11L + it, 11L + it, it.toLong()) }
            trace.close()
            returned.countDown()
        }.apply { start() }
        try {
            assertTrue("playback must not wait for a blocked writer", returned.await(500, TimeUnit.MILLISECONDS))
            assertTrue(reports.any { it.startsWith("drop ptsUs=2 reason=writer-capacity") })
        } finally { release.countDown(); producer.join(2_000); trace.close() }
        assertTrue(trace.awaitClosed(2_000))
        assertEquals(2, root.single("tsv").readLines().count { it.startsWith("frame\t") })
        assertTrue(root.single("tsv").readText().contains("\twriter-capacity\n"))
    }

    @Test fun anIdleScreenStillFinalizesAndFlushesTheCaptureAtItsDeadline() {
        val root = temp.newFolder()
        val ended = CountDownLatch(1)
        val trace = VideoFrameTrace(root,
            VideoFrameTrace.Limits(windowNs = 50_000_000, preRollFrames = 0, preRollBytes = 0),
            nanoTime = System::nanoTime,
        ) { if (it.startsWith("end ") && it.endsWith("reason=duration")) ended.countDown() }
        trace.onConfig(VideoCodec.H264, listOf(csd))
        val touch = System.nanoTime()
        assertTrue(trace.startCapture(1, touch))
        trace.onFrame(idr, touch + 1, 10, 1)
        try {
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            assertArrayEquals(csd + idr, root.single("h264").readBytes())
            assertTrue(root.single("tsv").readText().contains("\tduration\n"))
        } finally { trace.close(); assertTrue(trace.awaitClosed(2_000)) }
    }

    private fun File.single(extension: String) = listFiles()!!.single { it.extension == extension }
}
