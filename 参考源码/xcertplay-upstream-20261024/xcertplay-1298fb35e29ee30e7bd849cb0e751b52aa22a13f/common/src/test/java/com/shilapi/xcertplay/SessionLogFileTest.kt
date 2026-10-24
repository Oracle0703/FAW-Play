package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionLogFileTest {
    @Test fun rotationKeepsHandshakeAndWholeRecentRecords() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            val records = (0..100).map { "record-$it\n${"x".repeat(300)}\nend-$it" }
            SessionLogFile(file, maxBytes = 8192, headLimitBytes = 2048).use { log ->
                log.reset("handshake-header")
                records.forEach(log::append)
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("handshake-header\n${records.first()}\n"))
            assertTrue(retained.endsWith("${records.last()}\n"))
            assertTrue(retained.contains("LOG RETENTION"))
            assertTrue(file.length() <= 8192)
            for (i in records.indices) {
                assertEquals(retained.contains("record-$i\n"), retained.contains("\nend-$i\n"))
            }
        } finally { file.delete() }
    }

    @Test fun oversizedTextRemainsValidUtf8AndReportsTruncation() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLogFile(file, maxBytes = 8192, headLimitBytes = 2048).use { log ->
                log.reset("header")
                log.append("原始数据".repeat(4000))
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("header\nLOG TRUNCATED"))
            assertFalse(retained.contains('\uFFFD'))
            assertTrue(file.length() <= 8192)
        } finally { file.delete() }
    }

    @Test fun rawVideoIsOptInAndCopiesExactWireAndPlainBytes() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLog().use { log ->
                log.start(file, "header")
                val header = byteArrayOf(0, 1, 2, 3, 0)
                val wire = byteArrayOf(0x10, 0x20, 0xff.toByte())
                val plain = byteArrayOf(0, 0, 1, 0x65)
                log.appendVideoPacket(110, VideoCodec.H264, 1, header, wire, plain)
                log.videoFramesEnabled = true
                log.appendVideoPacket(111, VideoCodec.H265, 2, header, wire, plain)
                wire.fill(0)
                plain.fill(0)
                log.videoFramesEnabled = false
                log.appendVideoPacket(110, VideoCodec.H264, 3, header, wire, plain)
            }
            val retained = file.readText()
            assertFalse(retained.contains("type=110"))
            assertTrue(retained.contains("type=111 codec=H265 sequence=2"))
            assertTrue(retained.contains("headerHex=0001020300"))
            assertTrue(retained.contains("wireHex=1020ff"))
            assertTrue(retained.contains("plainHex=00000165"))
        } finally { file.delete() }
    }

    @Test fun oversizedRawPacketIsDroppedWithMarkerAndProgressSurvives() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLogFile(file, maxBytes = 2048, headLimitBytes = 512).use { log ->
                log.reset("handshake-header")
                log.appendVideoPacket(110, "H264", 1, ByteArray(128), ByteArray(2048), null)
                log.append("connection-ready")
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("handshake-header\n"))
            assertTrue(retained.contains("LOG VIDEO LOSS droppedPackets=1"))
            assertFalse(retained.contains("wireHex="))
            assertTrue(retained.endsWith("connection-ready\n"))
        } finally { file.delete() }
    }

    @Test fun writerBacklogDropsRecordsInsteadOfBlockingCallers() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLogFile(file, maxBytes = 1024 * 1024, headLimitBytes = 1024).use { log ->
                log.reset("header")
                val gate = java.util.concurrent.CountDownLatch(1)
                // The first record holds the writer until every producer call has returned.
                log.enqueue { gate.await(); "first" }
                val started = System.nanoTime()
                repeat(10_000) { log.append("record-$it") }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                gate.countDown()
                assertTrue("producers blocked for ${elapsedMillis}ms", elapsedMillis < 5_000)
                // Wait for room so the next record is accepted rather than counted as lost.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (log.backlogFull && System.nanoTime() < deadline) Thread.sleep(1)
                log.append("after-backlog")
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("header\nfirst\nrecord-0\n"))
            // Everything accepted before the backlog filled is kept, then one marker, then new records.
            val marker = Regex("\nLOG LOSS droppedRecords=(\\d+) reason=writer-backlog\nafter-backlog\n$").find(retained)
            assertNotNull(retained.takeLast(200), marker)
            val kept = retained.lines().count { it.startsWith("record-") }
            assertEquals(10_000, kept + marker!!.groupValues[1].toInt())
            assertTrue(retained.contains("\nrecord-${kept - 1}\nLOG LOSS"))
        } finally { file.delete() }
    }

    @Test fun activityReattachmentKeepsOneCaptureAndDoesNotTruncate() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLog().use { log ->
                log.start(file, "first-activity")
                log.append("connected")
                log.start(file, "second-activity")
                log.append("reattached")
            }
            assertEquals("first-activity\nconnected\nreattached\n", file.readText())
        } finally { file.delete() }
    }
}
