package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ScreenPictureProgressTest {
    @Test fun aRecordWithoutACompleteAuthenticationTagCannotBeDecryptedAsPlaintext() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCodec.decryptFrame(ByteArray(32), 0, ByteArray(128), byteArrayOf(0, 0, 0, 2, 0x65, 0x80.toByte()))
        }
    }

    @Test fun h264ConfigurationSeiAndTruncatedHeadersAreNotPictures() {
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0x67, 1) + nal(0x68, 2)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0x06, 1)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0x65)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0xe5, 1)))
        assertTrue(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0x67, 1) + nal(0x65, 2)))
        assertTrue(ScreenCodec.hasPictureAccessUnit(VideoCodec.H264, nal(0x41, 2)))
    }

    @Test fun hevcPictureNeedsAValidHeaderAndPayload() {
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(32 shl 1, 1, 2)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(19 shl 1, 1)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(19 shl 1, 0, 2)))
        assertFalse(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(0x80 or (19 shl 1), 1, 2)))
        assertTrue(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(19 shl 1, 1, 2)))
        assertTrue(ScreenCodec.hasPictureAccessUnit(VideoCodec.H265, nal(1 shl 1, 1, 2)))
    }

    private fun nal(vararg bytes: Int) = byteArrayOf(0, 0, 0, 1) + bytes.map(Int::toByte).toByteArray()
}
