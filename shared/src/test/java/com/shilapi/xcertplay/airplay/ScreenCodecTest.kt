package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Test

class ScreenCodecTest {
    @Test fun senderTimeUsesLittleEndianNtpFractionAndRemainsSeparateFromAndroidClock() {
        val header = ByteArray(128)
        java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(8, (123L shl 32) or 0x8000_0000L)
        assertEquals(123_500_000_000L, ScreenCodec.senderNanos(header))
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(15)))
    }
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsRejectWholeFrameWithoutMutatingInput() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertEquals(0, ScreenCodec.lengthPrefixedToAnnexB(payload).size)
        assertArrayEquals(original, payload)
    }

    @Test fun oneByteAudDoesNotHideTheFollowingKeyframe() {
        val payload = byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 2, 0x65, 1)
        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 1, 0x65, 1), converted)
        assertTrue(MediaCodecSupport.isRandomAccess(MediaCodecSupport.toAnnexB(converted), VideoCodec.H264))
    }

    @Test fun negotiatedLengthSizesConvertMultipleNals() {
        for (lengthSize in listOf(1, 2, 4)) {
            val prefix = ByteArray(lengthSize).apply { this[lastIndex] = 2 }
            assertArrayEquals(
                byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x65, 1),
                ScreenCodec.lengthPrefixedToAnnexB(prefix + byteArrayOf(0x67, 1) + prefix + byteArrayOf(0x65, 1), lengthSize),
            )
        }
    }

    @Test fun readsLengthSizeFromAvcAndHevcConfiguration() {
        for (codec in VideoCodec.entries) {
            val offset = if (codec == VideoCodec.H265) 21 else 4
            for (lengthSize in listOf(1, 2, 4)) {
                val config = ByteArray(offset + 1).apply { this[offset] = (0xfc or (lengthSize - 1)).toByte() }
                assertEquals(lengthSize, ScreenCodec.nalLengthSize(codec, config))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedCodecConfig() {
        ScreenCodec.nalLengthSize(VideoCodec.H265, ByteArray(21))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReservedLengthSize() {
        ScreenCodec.nalLengthSize(VideoCodec.H264, byteArrayOf(1, 0, 0, 0, 2))
    }

    @Test fun malformedFrameAfterOneByteAudIsRejectedWithoutPartialConversion() {
        val payload = byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 3, 0x65, 1)
        val original = payload.copyOf()
        assertEquals(0, ScreenCodec.lengthPrefixedToAnnexB(payload).size)
        assertArrayEquals(original, payload)
    }

    @Test fun inPlaceConversionMatchesThePreviousValidatorAcrossLargeAndMalformedAccessUnits() {
        val random = java.util.Random(47)
        val cases = mutableListOf<ByteArray>()
        repeat(64) {
            val size = if (it == 0) 1024 * 1024 else random.nextInt(1024) + 1
            val nalu = ByteArray(size).also(random::nextBytes)
            val packet = java.nio.ByteBuffer.allocate(4 + size).putInt(size).put(nalu).array()
            cases += packet
            cases += packet + byteArrayOf(0, 0, 0, 3, 0x41)
        }
        cases += byteArrayOf(-1, -1, -1, -1)
        cases += byteArrayOf(0, 0, 0, 0)
        for (packet in cases) {
            val original = packet.copyOf()
            val expected = MediaCodecSupport.toAnnexB(packet, 4, allowAnnexB = false)
            val actual = ScreenCodec.lengthPrefixedToAnnexB(packet)
            assertArrayEquals(expected, actual)
            if (expected.isEmpty()) assertArrayEquals(original, packet) else assertSame(packet, actual)
        }
    }
}
