package com.shilapi.xcertplay.media

import java.io.ByteArrayOutputStream

/**
 * Pure byte helpers that convert the CarPlay screen/audio payloads into the
 * records Android MediaCodec and AudioTrack expect. Kept free of Android types
 * so they stay testable on the JVM.
 */
object MediaCodecSupport {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** Splits an AVCDecoderConfigurationRecord into raw first SPS and first PPS. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> {
        if (codecData.size < 7) return emptySet()
        var cursor = 6
        val sps = readParameterSets(codecData, cursor, codecData[5].toInt() and 0x1f)
        cursor += sps.sumOf { it.size + 2 }
        if (cursor >= codecData.size) return emptySet()
        val pps = readParameterSets(codecData, cursor + 1, codecData[cursor].toInt() and 0xff)
        return (sps.firstOrNull() ?: ByteArray(0)) to (pps.firstOrNull() ?: ByteArray(0))
    }

    /**
     * Converts an HEVCDecoderConfigurationRecord (hvcC) into Annex B VPS/SPS/PPS CSD.
     *
     * Android video decoders expect the initialization data as NAL units with start codes,
     * not as the raw ISO-BMFF hvcC record.
     */
    fun hevcCodecSpecificData(codecData: ByteArray): ByteArray {
        if (codecData.size < HEVC_FIXED_RECORD_SIZE || codecData[0].toInt() != 1) {
            return ByteArray(0)
        }

        var cursor = HEVC_ARRAY_COUNT_OFFSET
        val arrayCount = codecData[cursor++].toInt() and 0xff
        val sets = sortedMapOf<Int, MutableList<ByteArray>>()
        repeat(arrayCount) {
            if (cursor + 3 > codecData.size) return ByteArray(0)
            val type = codecData[cursor++].toInt() and 0x3f
            val count = readU16Be(codecData, cursor)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > codecData.size) return ByteArray(0)
                val length = readU16Be(codecData, cursor)
                cursor += 2
                if (length < 2 || length > codecData.size - cursor) return ByteArray(0)
                if (((codecData[cursor].toInt() ushr 1) and 0x3f) != type ||
                    codecData[cursor].toInt() and 0x80 != 0 ||
                    codecData[cursor + 1].toInt() and 7 == 0
                ) return ByteArray(0)
                if (type in 32..34 || type == 39 || type == 40) {
                    sets.getOrPut(type) { mutableListOf() }.add(codecData.copyOfRange(cursor, cursor + length))
                }
                cursor += length
            }
        }
        if ((32..34).any { sets[it].isNullOrEmpty() }) return ByteArray(0)
        return ByteArrayOutputStream().apply {
            sets.values.flatten().forEach { write(START_CODE); write(it) }
        }.toByteArray()
    }

    /** Converts a complete access unit; never feeds a valid prefix of a damaged picture. */
    fun toAnnexB(
        lengthPrefixed: ByteArray,
        lengthSize: Int = 4,
        allowAnnexB: Boolean = true,
    ): ByteArray {
        if (allowAnnexB && startCodeSize(lengthPrefixed, 0) != 0) return lengthPrefixed
        val outputSize = annexBSize(lengthPrefixed, lengthSize)
        if (outputSize < 0) return ByteArray(0)
        // Runs once per video frame: size first, then copy, so no intermediate buffers grow.
        val output = ByteArray(outputSize)
        var cursor = 0
        var position = 0
        while (cursor < lengthPrefixed.size) {
            val length = readLength(lengthPrefixed, cursor, lengthSize)
            cursor += lengthSize
            START_CODE.copyInto(output, position)
            position += START_CODE.size
            lengthPrefixed.copyInto(output, position, cursor, cursor + length)
            position += length
            cursor += length
        }
        return output
    }

    /**
     * Converts four-byte length prefixes to start codes in place, the common case for every
     * screen frame. Returns false, leaving [buffer] untouched, when it is not well formed.
     */
    fun toAnnexBInPlace(buffer: ByteArray, length: Int = buffer.size): Boolean {
        if (length !in 0..buffer.size || annexBSize(buffer, length, 4) != length) return false
        var cursor = 0
        while (cursor < length) {
            val length = readLength(buffer, cursor, 4)
            START_CODE.copyInto(buffer, cursor)
            cursor += 4 + length
        }
        return true
    }

    /** Annex B size of a length-prefixed access unit, or -1 when it is malformed. */
    private fun annexBSize(lengthPrefixed: ByteArray, lengthSize: Int): Int {
        return annexBSize(lengthPrefixed, lengthPrefixed.size, lengthSize)
    }

    private fun annexBSize(lengthPrefixed: ByteArray, limit: Int, lengthSize: Int): Int {
        if (limit !in 0..lengthPrefixed.size) return -1
        if (lengthSize != 1 && lengthSize != 2 && lengthSize != 4) return -1
        var cursor = 0
        var size = 0L
        while (cursor < limit) {
            if (lengthSize > limit - cursor) return -1
            val length = readLength(lengthPrefixed, cursor, lengthSize)
            cursor += lengthSize
            if (length <= 0 || length > limit - cursor) return -1
            size += START_CODE.size + length
            cursor += length
        }
        return if (size > Int.MAX_VALUE) -1 else size.toInt()
    }

    /** Reads a big-endian length; four-byte values above Int.MAX_VALUE come back negative. */
    private fun readLength(source: ByteArray, offset: Int, lengthSize: Int): Int {
        var length = 0L
        for (index in offset until offset + lengthSize) {
            length = (length shl 8) or (source[index].toLong() and 255)
        }
        return if (length > Int.MAX_VALUE) -1 else length.toInt()
    }

    /**
     * Normalizes a complete access unit to four-byte length prefixes, the sample format of
     * avcC/hvcC streams (MP4, WebCodecs). Returns an empty array for damaged input.
     */
    fun toLengthPrefixed(bytes: ByteArray): ByteArray {
        if (startCodeSize(bytes, 0) != 0) {
            // One output array sized up front: this runs for every forwarded screen frame.
            val bounds = annexBUnitBounds(bytes) ?: return ByteArray(0)
            var size = 0
            for (index in bounds.indices step 2) size += 4 + bounds[index + 1] - bounds[index]
            val output = ByteArray(size)
            var position = 0
            for (index in bounds.indices step 2) {
                val length = bounds[index + 1] - bounds[index]
                output[position] = (length ushr 24).toByte()
                output[position + 1] = (length ushr 16).toByte()
                output[position + 2] = (length ushr 8).toByte()
                output[position + 3] = length.toByte()
                bytes.copyInto(output, position + 4, bounds[index], bounds[index + 1])
                position += 4 + length
            }
            return output
        }
        return if (lengthPrefixedHeaders(bytes).isEmpty()) ByteArray(0) else bytes
    }

    /** The first two bytes (NAL header) of every unit in a four-byte length-prefixed access unit. */
    fun lengthPrefixedHeaders(bytes: ByteArray): List<ByteArray> {
        val headers = mutableListOf<ByteArray>()
        var cursor = 0
        while (cursor < bytes.size) {
            if (bytes.size - cursor < 4) return emptyList()
            val length = ((bytes[cursor].toLong() and 255) shl 24) or ((bytes[cursor + 1].toLong() and 255) shl 16) or
                ((bytes[cursor + 2].toLong() and 255) shl 8) or (bytes[cursor + 3].toLong() and 255)
            cursor += 4
            if (length <= 0 || length > bytes.size - cursor) return emptyList()
            headers.add(bytes.copyOfRange(cursor, cursor + minOf(2, length.toInt())))
            cursor += length.toInt()
        }
        return headers
    }

    /** Raw NAL units from either three- or four-byte Annex B start codes. */
    fun annexBNalUnits(bytes: ByteArray): List<ByteArray> {
        val bounds = annexBUnitBounds(bytes) ?: return emptyList()
        return (bounds.indices step 2).map { bytes.copyOfRange(bounds[it], bounds[it + 1]) }
    }

    /** The first two bytes (NAL header) of every unit in an Annex B access unit; no payload copies. */
    fun annexBNalHeaders(bytes: ByteArray): List<ByteArray> {
        return annexBNalHeaders(bytes, bytes.size)
    }

    fun annexBNalHeaders(bytes: ByteArray, length: Int): List<ByteArray> {
        val bounds = annexBUnitBounds(bytes, length) ?: return emptyList()
        return (bounds.indices step 2).map { bytes.copyOfRange(bounds[it], minOf(bounds[it] + 2, bounds[it + 1])) }
    }

    /** Start and end offsets of each Annex B NAL unit, flattened, or null when malformed. */
    private fun annexBUnitBounds(bytes: ByteArray): IntArray? {
        return annexBUnitBounds(bytes, bytes.size)
    }

    private fun annexBUnitBounds(bytes: ByteArray, length: Int): IntArray? {
        if (length !in 1..bytes.size || startCodeSize(bytes, 0, length) == 0) return null
        var bounds = IntArray(8)
        var count = 0
        var cursor = 0
        while (cursor < length) {
            val prefix = startCodeSize(bytes, cursor, length)
            if (prefix == 0) return null
            val start = cursor + prefix
            cursor = start
            while (cursor < length && startCodeSize(bytes, cursor, length) == 0) cursor++
            if (cursor == start) return null
            if (count + 2 > bounds.size) bounds = bounds.copyOf(bounds.size * 2)
            bounds[count++] = start
            bounds[count++] = cursor
        }
        return bounds.copyOf(count)
    }

    private fun startCodeSize(bytes: ByteArray, offset: Int): Int {
        return startCodeSize(bytes, offset, bytes.size)
    }

    private fun startCodeSize(bytes: ByteArray, offset: Int, limit: Int): Int {
        if (offset + 3 > limit || bytes[offset] != 0.toByte() || bytes[offset + 1] != 0.toByte()) return 0
        if (bytes[offset + 2] == 1.toByte()) return 3
        return if (offset + 4 <= limit && bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte()) 4 else 0
    }

    /** Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame. */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnit.size + 7
        val header = ByteArray(7)
        header[0] = 0xff.toByte()
        header[1] = 0xf1.toByte()
        header[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        header[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        header[4] = ((frameLength ushr 3) and 0xff).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        header[6] = 0xfc.toByte()
        return header + accessUnit
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun emptySet(): Pair<ByteArray, ByteArray> = ByteArray(0) to ByteArray(0)

    private fun readParameterSets(source: ByteArray, offset: Int, count: Int): List<ByteArray> {
        val sets = ArrayList<ByteArray>(count)
        var cursor = offset
        var index = 0
        while (index < count && cursor + 2 <= source.size) {
            index++
            val length = readU16Be(source, cursor)
            cursor += 2
            if (cursor + length > source.size) break
            sets.add(source.copyOfRange(cursor, cursor + length))
            cursor += length
        }
        return sets
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val HEVC_FIXED_RECORD_SIZE = 23
    private const val HEVC_ARRAY_COUNT_OFFSET = 22
}
