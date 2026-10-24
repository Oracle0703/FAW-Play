package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioPacketCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun slowDiskCannotBlockAudioRecordOrCloseAndDiagnosticQueueIsBounded() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val capture = AudioPacketCapture(temporary.newFolder(), 100, openOutput = {
            blocked.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            bytes
        })
        try {
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
            val submitted = CountDownLatch(1)
            Thread {
                repeat(1000) { capture.record(byteArrayOf(it.toByte()), null, it, null) }
                capture.close()
                submitted.countDown()
            }.start()
            assertTrue("Live audio submission and close must not wait for the disk", submitted.await(1, TimeUnit.SECONDS))
            assertFalse(capture.awaitClosed(1))
            release.countDown()
            assertTrue(capture.awaitClosed(2000))
            val records = records(bytes.toByteArray())
            assertEquals(64, records.size)
            assertEquals((0..63).toList(), records.map { it.first })
        } finally { release.countDown(); capture.close(); capture.awaitClosed(2000) }
    }

    @Test fun packetCopiesAndOriginalVersionOneFormatArePreserved() {
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val capture = AudioPacketCapture(temporary.newFolder(), 102, maxPackets = 1, openOutput = {
            check(release.await(3, TimeUnit.SECONDS)); bytes
        })
        val wire = byteArrayOf(1, 2)
        val rtp = byteArrayOf(3, 4)
        capture.record(wire, rtp, 123, IllegalArgumentException("test"))
        wire.fill(99); rtp.fill(99)
        release.countDown()
        assertTrue(capture.awaitClosed(2000))
        val input = DataInputStream(bytes.toByteArray().inputStream())
        assertEquals("XCAUDIO1", String(ByteArray(8).also(input::readFully), Charsets.US_ASCII))
        assertEquals(1, input.readInt()); assertEquals(102, input.readInt()); assertTrue(input.readLong() > 0)
        assertEquals(2, input.readInt()); assertEquals(2, input.readInt()); assertEquals(123, input.readInt())
        input.readLong(); assertEquals(4, input.readInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ByteArray(4).also(input::readFully))
        assertEquals("test", String(ByteArray(4).also(input::readFully)))
        assertEquals(-1, input.read())
    }

    @Test fun packetAndByteLimitsApplyBeforeWritingAndOpenFailureStaysDiagnosticOnly() {
        val bytes = ByteArrayOutputStream()
        val capture = AudioPacketCapture(temporary.newFolder(), 100, maxBytes = 50, openOutput = { bytes })
        capture.record(byteArrayOf(1, 2), null, 1, null) // 24-byte header + 26-byte record
        capture.record(byteArrayOf(3), null, 2, null)
        capture.close()
        assertTrue(capture.awaitClosed(2000))
        assertEquals(50, bytes.size())
        val failed = AudioPacketCapture(temporary.newFolder(), 100, openOutput = { throw java.io.IOException("unavailable") })
        failed.record(ByteArray(10), null, null, null)
        failed.close()
        assertTrue(failed.awaitClosed(2000))
    }

    private fun records(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val input = DataInputStream(bytes.inputStream())
        input.skipBytes(24)
        val result = ArrayList<Pair<Int, ByteArray>>()
        while (input.available() > 0) {
            val wire = input.readInt(); val rtp = input.readInt(); val sample = input.readInt()
            input.readLong(); val error = input.readInt()
            val content = ByteArray(wire + rtp.coerceAtLeast(0) + error).also(input::readFully)
            result += sample to content
        }
        return result
    }
}
