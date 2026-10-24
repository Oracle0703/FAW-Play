package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/** Bodies are verbatim requests from an iOS 26 wireless session (they carry no device identifiers). */
class BplistCodecTest {
    @Test fun decodesPhoneInfoRequest() {
        val request = BplistCodec.decode(hex(INFO_REQUEST)) as Map<*, *>
        assertEquals(
            listOf("maps:/car/instrumentcluster/map", "maps:/car/instrumentcluster/instructioncard", "maps:/car/instrumentcluster"),
            request["altScreenURLs"],
        )
        assertEquals(listOf("maps:", "mobilephone:", "nowplaying:", "messages:"), request["uiContextURLs"])
    }

    @Test fun decodesTunnelStreamSetupWithTwoByteOffsetsAndSigned64BitSeed() {
        val stream = streams(IAP_TUNNEL_SETUP).single()
        assertEquals(130L, stream["type"])
        assertEquals(2L, stream["controlType"])
        assertEquals(-8958586947476470172L, stream["seed"])
        assertEquals(true, stream["wantsDedicatedSocket"])
        assertEquals(true, stream["sendMessageAsIs"])
        assertEquals("E9459FD0-BCAD-4C45-820F-1E72447EF2F2", stream["clientTypeUUID"])
        assertEquals("02:00:00:00:00:02-RCS-422", stream["channelID"])
    }

    @Test fun decodesAltScreenStreamSetup() {
        val stream = streams(ALT_SCREEN_SETUP).single()
        assertEquals(111L, stream["type"])
        assertEquals(75L, stream["latencyMs"])
        assertEquals(5961060656565797976L, stream["streamConnectionID"])
        assertEquals(AirPlayInfoPlist.ALT_UUID, stream["uuid"])
    }

    @Test fun reEncodingPhoneRequestsPreservesTheirContent() {
        for (body in listOf(INFO_REQUEST, ALT_SCREEN_SETUP)) {
            val decoded = BplistCodec.decode(hex(body))
            assertEquals(decoded.toString(), BplistCodec.decode(BplistCodec.encode(decoded)).toString())
        }
    }

    @Test fun highBitIdentifiersMustBeEchoedAsUnsignedIntegers() {
        val seed = streams(IAP_TUNNEL_SETUP).single()["seed"]
        // A negative Long is written as a real, which would corrupt the identifier.
        assertEquals(-8.958586947476470172E18, BplistCodec.decode(BplistCodec.encode(seed)))
        assertEquals(seed, BplistCodec.decode(BplistCodec.encode(unsignedPlistInteger(seed))))
    }

    @Test fun roundTripsEveryEncodedType() {
        val blob = ByteArray(300) { it.toByte() }
        val root = linkedMapOf<String, Any?>(
            "bool" to false,
            "u8" to 0xff,
            "u16" to 0x1234,
            "u32" to 0x12345678L,
            "u64" to 0x123456789aL,
            "unsignedMax" to BigInteger("18446744073709551615"),
            "real" to 0.5,
            "negative" to -3,
            "ascii" to "x".repeat(20),
            "utf16" to "车机·CarPlay",
            "data" to blob,
            "nested" to listOf(linkedMapOf("empty" to emptyList<Any>()), true),
        )

        val decoded = BplistCodec.decode(BplistCodec.encode(root)) as Map<*, *>

        assertEquals(root.keys.toList(), decoded.keys.toList())
        assertEquals(false, decoded["bool"])
        assertEquals(0xffL, decoded["u8"])
        assertEquals(0x1234L, decoded["u16"])
        assertEquals(0x12345678L, decoded["u32"])
        assertEquals(0x123456789aL, decoded["u64"])
        // Unsigned 64-bit values come back as the same bit pattern in a signed Long.
        assertEquals(-1L, decoded["unsignedMax"])
        assertEquals(0.5, decoded["real"])
        assertEquals(-3.0, decoded["negative"])
        assertEquals("x".repeat(20), decoded["ascii"])
        assertEquals("车机·CarPlay", decoded["utf16"])
        assertArrayEquals(blob, decoded["data"] as ByteArray)
        assertEquals(listOf(mapOf("empty" to emptyList<Any>()), true), decoded["nested"])
    }

    @Test fun manyObjectsUseWideReferences() {
        val list = (0 until 400).map { "item$it" }
        assertEquals(list, BplistCodec.decode(BplistCodec.encode(list)))
    }

    @Test fun rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(ByteArray(39)) }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(ByteArray(64)) }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.encode(BigInteger.ONE.shiftLeft(64)) }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.encode(Any()) }
    }

    private fun streams(body: String): List<Map<*, *>> =
        ((BplistCodec.decode(hex(body)) as Map<*, *>)["streams"] as List<*>).map { it as Map<*, *> }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val INFO_REQUEST =
            "62706c6973743030d2010203075d616c7453637265656e55524c735d7569436f6e7465787455524c73a30405065f101f6d6170733a2f" +
                "6361722f696e737472756d656e74636c75737465722f6d61705f102b6d6170733a2f6361722f696e737472756d656e74636c75" +
                "737465722f696e737472756374696f6e636172645f101b6d6170733a2f6361722f696e737472756d656e74636c7573746572a4" +
                "08090a0b556d6170733a5c6d6f62696c6570686f6e653a5b6e6f77706c6179696e673a596d657373616765733a080d1b292d4f" +
                "7d9ba0a6b3bf0000000000000101000000000000000c000000000000000000000000000000c9"
        const val IAP_TUNNEL_SETUP =
            "62706c6973743030d101025773747265616d73a103d80405060708090a0b0c0d0e0f101111135b636f6e74726f6c547970655963" +
                "68616e6e656c494454736565645a636c69656e745555494454747970655f101477616e7473446564696361746564536f636b65" +
                "745f100f73656e644d657373616765417349735e636c69656e74547970655555494410025f101930323a30303a30303a30303a" +
                "30303a30322d5243532d3432321383acb4a2ca36c6645f102431344631433243362d434134422d343438312d424339412d4334" +
                "4341424642313538464610820909" +
                "5f102445393435394644302d424341442d344334352d383230462d3145373234343745463246320008000b0013001500260032" +
                "003c0041004c00510068007a0089008b00a700b000d700d900da00db000000000000020100000000000000140000000000000000" +
                "0000000000000102"
        const val ALT_SCREEN_SETUP =
            "62706c6973743030d101025773747265616d73a103d40405060708090a0b5f101273747265616d436f6e6e656374696f6e494459" +
                "6c6174656e63794d73547479706554757569641352b9f11587294458104b106f5f102462376536633561302d323232322d3430" +
                "30302d383030302d303030303030303030303032080b13151e333d42475052540000000000000101000000000000000c000000" +
                "0000000000000000000000007b"
    }
}
