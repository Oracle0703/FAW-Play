package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class MediaCodecSupportTest {
    @Test fun wireFormatDoesNotGuessAnnexBFromAOneByteLength() {
        val bytes = byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 2, 0x65, 1)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 1, 0x65, 1),
            MediaCodecSupport.toAnnexB(bytes, allowAnnexB = false))
    }

    @Test fun invalidLengthsRejectWholeAccessUnitForEverySupportedPrefixSize() {
        for (lengthSize in listOf(1, 2, 4)) {
            val prefix = ByteArray(lengthSize).apply { this[lastIndex] = 2 }
            val valid = prefix + byteArrayOf(0x65, 1)
            assertEquals(0, MediaCodecSupport.toAnnexB(valid + prefix + byteArrayOf(0x41), lengthSize, false).size)
            assertEquals(0, MediaCodecSupport.toAnnexB(ByteArray(lengthSize), lengthSize, false).size)
        }
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(-1, -1, -1, -1, 0x65), allowAnnexB = false).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 2, 0x65, 1), 3, false).size)
    }

    @Test fun decoderAcceptsAlreadyConvertedAnnexBWithoutChangingItsBytes() {
        for (prefix in listOf(byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 0, 1))) {
            val bytes = prefix + byteArrayOf(0x65, 1)
            assertArrayEquals(bytes, MediaCodecSupport.toAnnexB(bytes))
        }
    }

    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    @Test fun incompleteHevcParameterSetsAreRejected() {
        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(hevcRecord(byteArrayOf(0x40, 1))).size)
    }

    @Test fun hevcParameterSetsAreOrderedAndSeiIsPreserved() {
        val vps = byteArrayOf(0x40, 1)
        val sps = byteArrayOf(0x42, 1, 2)
        val pps = byteArrayOf(0x44, 1)
        val sei = byteArrayOf(0x4e, 1, 2)
        val start = byteArrayOf(0, 0, 0, 1)
        assertArrayEquals(start + vps + start + sps + start + pps + start + sei,
            MediaCodecSupport.hevcCodecSpecificData(hevcRecord(pps, sei, sps, vps)))
    }

    @Test fun hevcNalHeadersMustMatchTheirArrayTypeAndHaveATemporalId() {
        val record = hevcRecord(byteArrayOf(0x40, 1), byteArrayOf(0x42, 1), byteArrayOf(0x44, 1))
        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(record.copyOf().apply { this[29] = 0 }).size)
        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(record.copyOf().apply { this[28] = 0x42 }).size)
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = if (parameterSet.isEmpty()) (32 + index).toByte()
                else ((parameterSet[0].toInt() ushr 1) and 0x3f).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }
}
