package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneRtpClockTest {
    private fun opus(clock: Int) = MicrophoneConfig("telephony", 48_000, 1, 100, 20,
        InetAddress.getByName("192.0.2.1"), 12345, ByteArray(32), AudioCodecKind.OPUS,
        48_000, clock)

    @Test fun negotiatedClockDoesNotChangeTheSoftwareEncoderPcmFrame() {
        for ((clock, samples) in listOf(16_000 to 320, 24_000 to 480, 48_000 to 960)) {
            val config = opus(clock)
            assertEquals(960, config.samplesPerPacket)
            assertEquals(1920, config.frameBytes)
            assertEquals(samples, config.rtpSamplesPerPacket)
            assertEquals(20L, config.samplesPerPacket * 1000L / config.sampleRate)
            assertEquals(20L, config.rtpSamplesPerPacket * 1000L / config.rtpClockRate)
        }
    }

    @Test fun fiftyPacketsAdvanceOneSecondAtEachNegotiatedClock() {
        for (clock in listOf(16_000, 24_000, 48_000)) {
            val config = opus(clock)
            val counters = MicrophoneCounters()
            repeat(50) { index ->
                val packet = MicrophonePacketizer.sealPacket(config.key, config.payloadType,
                    counters, byteArrayOf(1, 2), config.rtpSamplesPerPacket)
                val timestamp = (4..7).fold(0) { value, i -> (value shl 8) or (packet[i].toInt() and 255) }
                assertEquals(index * config.rtpSamplesPerPacket, timestamp)
            }
            assertEquals(clock, counters.timestamp)
            assertEquals(50, counters.sequence)
            assertEquals(50L, counters.nonce)
        }
    }

    @Test fun skippedSpeechAdvancesWireTimeAtSixteenKilohertz() {
        val config = opus(16_000)
        val counters = MicrophoneCounters()
        counters.timestamp += 2 * config.rtpSamplesPerPacket
        MicrophonePacketizer.sealPacket(config.key, 100, counters, byteArrayOf(1), config.rtpSamplesPerPacket)
        assertEquals(960, counters.timestamp) // three 20 ms frames at the negotiated clock
        assertEquals(1, counters.sequence)
    }

    @Test fun copyRetainsClockAndPcmFormatsKeepTheirCaptureClock() {
        assertEquals(16_000, opus(16_000).copy(audioType = "speechrecognition").rtpClockRate)
        val pcm = MicrophoneConfig("telephony", 44_100, 2, 100, 20,
            InetAddress.getByName("192.0.2.1"), 12345, ByteArray(32))
        assertEquals(44_100, pcm.rtpClockRate)
        assertEquals(882, pcm.rtpSamplesPerPacket)
        assertEquals(3528, pcm.frameBytes)
    }

    @Test fun originalJavaConstructorRetainsItsCaptureClockDefault() {
        val ctor = MicrophoneConfig::class.java.getConstructor(String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, InetAddress::class.java, Int::class.javaPrimitiveType,
            ByteArray::class.java, AudioCodecKind::class.java, Integer::class.java)
        val config = ctor.newInstance("telephony", 48_000, 1, 100, 20,
            InetAddress.getByName("192.0.2.1"), 12345, ByteArray(32), AudioCodecKind.OPUS, 48_000)
        assertEquals(48_000, config.rtpClockRate)
        assertEquals(960, config.rtpSamplesPerPacket)
    }
}
