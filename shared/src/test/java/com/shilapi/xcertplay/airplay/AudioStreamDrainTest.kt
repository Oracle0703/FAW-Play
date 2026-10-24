package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamDrainTest {
    @Test fun normalEndRetainsAnAuthenticatedFinalPacketAcrossASequenceGap() = finalPacket(cancel = false)

    @Test fun cancellationDiscardsTheSameTailAndDoesNotReportGracefulEnd() = finalPacket(cancel = true)

    private fun finalPacket(cancel: Boolean) {
        val key = ByteArray(32) { it.toByte() }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val exited = CountDownLatch(1)
        val stream = AudioStream(key, 101, {
            if (it.startsWith("Audio: RTP order")) exited.countDown()
        }, "default")
        val ports = stream.listen(object : AudioStream.Listener {
            override fun onRtp(rtp: ByteArray, sample: Int) { events.add("sample:$sample") }
            override fun onEnded() { events.add("end") }
            override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                assertNull(error)
                // End at the authenticated final datagram, before the 20ms gap deadline can
                // fire. No extra packet or timing sleep is needed to release its ordered tail.
                if (sample == 12 * 320) {
                    if (cancel) stream.close() else stream.finish()
                }
            }
        })
        try {
            DatagramSocket().use { socket ->
                for (sequence in listOf(10, 12)) {
                    val counters = MicrophoneCounters(sequence, sequence * 320, sequence.toLong())
                    val wire = MicrophonePacketizer.sealPacket(key, 101, counters, byteArrayOf(1, 2, 3), 320)
                    socket.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), ports.first))
                }
            }
            assertTrue("receiver did not finish", exited.await(3, TimeUnit.SECONDS))
            assertEquals(if (cancel) listOf("sample:3200") else listOf("sample:3200", "sample:3840", "end"), events)
        } finally { stream.close() }
    }
}
