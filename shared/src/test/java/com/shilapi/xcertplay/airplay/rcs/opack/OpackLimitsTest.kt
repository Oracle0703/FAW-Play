package com.shilapi.xcertplay.airplay.rcs.opack

import org.junit.Assert.*
import org.junit.Test

class OpackLimitsTest {
    @Test
    fun nestedRemoteCollectionsFailBeforeStackExhaustion() {
        assertThrows(OpackException::class.java) {
            OpackCodec.decode(ByteArray(128) { 0xd1.toByte() } + byteArrayOf(0x04))
        }
    }

    @Test
    fun unboundedRemoteCollectionHasValueBudget() {
        assertThrows(OpackException::class.java) {
            OpackCodec.decode(byteArrayOf(0xdf.toByte()) + ByteArray(65_536) { 0x04 } + byteArrayOf(3))
        }
    }

    @Test
    fun knownScalarWireValuesAndObjectReferencesDecode() {
        assertEquals(listOf("ab", "ab", -1L), OpackCodec.decode(
            byteArrayOf(0xd3.toByte(), 0x42, 0x61, 0x62, 0xa0.toByte(), 0x30, 0xff.toByte()),
        ))
        assertThrows(OpackException::class.java) { OpackCodec.decode(byteArrayOf(0xa0.toByte())) }
        assertThrows(OpackException::class.java) { OpackCodec.decode(byteArrayOf(0x92.toByte(), 2)) }
    }

    @Test
    fun largeDataUsesFourByteLengthTag() {
        val data = ByteArray(70_000) { (it % 251).toByte() }
        val encoded = OpackCodec.encode(data)
        assertEquals(0x93, encoded[0].toInt() and 0xff)
        assertEquals(0x70, encoded[1].toInt() and 0xff)
        assertEquals(0x11, encoded[2].toInt() and 0xff)
        assertEquals(0x01, encoded[3].toInt() and 0xff)
        assertEquals(0x00, encoded[4].toInt() and 0xff)
        assertArrayEquals(data, OpackCodec.decode(encoded) as ByteArray)
    }
}
