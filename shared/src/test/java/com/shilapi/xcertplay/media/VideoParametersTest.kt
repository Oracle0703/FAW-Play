package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoParametersTest {
    @Test fun avcUsesSpsDimensionsAndLeavesUnspecifiedColorsUnspecified() {
        val parsed = VideoParameters.parse(VideoCodec.H264, VideoTestConfig.start + VideoTestConfig.avcSps())
        assertEquals(960, parsed.width)
        assertEquals(540, parsed.height)
        assertEquals(-1, parsed.colorStandard)
        assertEquals(-1, parsed.colorRange)
        assertEquals(-1, parsed.colorTransfer)
    }

    @Test fun avcPropagatesFullRangeAndBt709FromVui() {
        val parsed = VideoParameters.parse(VideoCodec.H264, VideoTestConfig.start + VideoTestConfig.avcSps(color = true))
        assertEquals(1, parsed.colorStandard) // MediaFormat.COLOR_STANDARD_BT709
        assertEquals(1, parsed.colorRange) // MediaFormat.COLOR_RANGE_FULL
        assertEquals(3, parsed.colorTransfer) // MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    }

    @Test fun hevcUsesItsSpsConformanceWindowForTheDecodeDimensions() {
        val parsed = VideoParameters.parse(VideoCodec.H265, VideoTestConfig.start + VideoTestConfig.hevcSps())
        assertEquals(1920, parsed.width)
        assertEquals(1080, parsed.height)
    }

    @Test fun missingOrTruncatedSpsIsRejected() {
        assertThrows(Exception::class.java) { VideoParameters.parse(VideoCodec.H264, VideoTestConfig.start + byteArrayOf(0x67)) }
        assertThrows(Exception::class.java) { VideoParameters.parse(VideoCodec.H265, VideoTestConfig.start + byteArrayOf(0x40, 1)) }
    }
}

/** Small valid baseline/main-profile SPS records with known dimensions and optional AVC VUI. */
internal object VideoTestConfig {
    val start = byteArrayOf(0, 0, 0, 1)

    fun avcSps(color: Boolean = false): ByteArray = byteArrayOf(0x67) + Bits().apply {
        bits(66, 8); bits(0, 8); bits(30, 8)
        ue(0); ue(0); ue(0); ue(0); ue(1); bit(false)
        ue(59); ue(33); bit(true); bit(true)
        bit(true); ue(0); ue(0); ue(0); ue(2)
        bit(color)
        if (color) {
            bit(false); bit(false); bit(true); bits(5, 3); bit(true); bit(true)
            bits(1, 8); bits(1, 8); bits(1, 8)
            bit(false); bit(false); bit(false); bit(false); bit(false); bit(false)
        }
    }.finish()

    fun avcRecord(): ByteArray {
        val sps = avcSps()
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
        return byteArrayOf(1, 66, 0, 30, -1, -31, 0, sps.size.toByte()) + sps +
            byteArrayOf(1, 0, pps.size.toByte()) + pps
    }

    fun hevcSps(): ByteArray = byteArrayOf(0x42, 1) + Bits().apply {
        bits(0, 4); bits(0, 3); bit(true)
        bits(0, 2); bit(false); bits(1, 5)
        bits(0x60000000, 32); bit(true); bit(false); bit(false); bit(true)
        repeat(44) { bit(false) }; bits(120, 8)
        ue(0); ue(1); ue(1920); ue(1088)
        bit(true); ue(0); ue(0); ue(0); ue(4)
        ue(0); ue(0); ue(4)
        bit(false); ue(1); ue(0); ue(0)
        ue(0); ue(3); ue(0); ue(3); ue(0); ue(0)
        bit(false); bit(true); bit(true); bit(false)
        ue(0); bit(false); bit(true); bit(true); bit(false); bit(false)
    }.finish()

    private class Bits {
        private val value = StringBuilder()
        fun bit(one: Boolean) { value.append(if (one) '1' else '0') }
        fun bits(number: Int, count: Int) { for (i in count - 1 downTo 0) bit(number ushr i and 1 != 0) }
        fun ue(number: Int) {
            val code = Integer.toBinaryString(number + 1)
            repeat(code.length - 1) { bit(false) }
            value.append(code)
        }
        fun finish(): ByteArray {
            bit(true)
            while (value.length % 8 != 0) bit(false)
            val result = mutableListOf<Byte>()
            var zeroes = 0
            for (chunk in value.toString().chunked(8)) {
                val byte = chunk.toInt(2)
                if (zeroes >= 2 && byte <= 3) { result.add(3); zeroes = 0 }
                result.add(byte.toByte())
                zeroes = if (byte == 0) zeroes + 1 else 0
            }
            return result.toByteArray()
        }
    }
}
