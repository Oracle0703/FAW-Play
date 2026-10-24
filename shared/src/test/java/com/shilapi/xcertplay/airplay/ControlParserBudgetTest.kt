package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class ControlParserBudgetTest {
    @Test fun rtspKeepsPartialBodiesAndParsesCoalescedMessages() {
        val head = "SETUP * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 4\r\n\r\n".toByteArray()
        val partial = RtspMessage.parseMessages(head + byteArrayOf(1, 2))
        assertTrue(partial.messages.isEmpty())
        val parsed = RtspMessage.parseMessages(partial.rest + byteArrayOf(3, 4) + "OPTIONS * RTSP/1.0\r\n\r\n".toByteArray())
        assertEquals(2, parsed.messages.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.messages[0].body)
        assertTrue(parsed.rest.isEmpty())
    }

    @Test fun rtspRejectsInvalidLengthsBeforeWaitingForABody() {
        for (value in listOf("-1", "2147483647", "18446744073709551616", "1x", "")) {
            rejects { RtspMessage.parseMessages("SETUP * RTSP/1.0\r\nContent-Length: $value\r\n\r\n".toByteArray()) }
        }
        rejects { RtspMessage.parseMessages("SETUP * RTSP/1.0\r\nContent-Length: 1\r\ncontent-length: 2\r\n\r\nxx".toByteArray()) }
        rejects { RtspMessage.parseMessages(ByteArray(RtspMessage.MAX_HEADER_BYTES + 1) { 65 }) }
        rejects { RtspMessage.parseMessages(ByteArray(RtspMessage.MAX_HEADER_BYTES + 1) { 65 } + "\r\n\r\n".toByteArray()) }
        val body = ByteArray(1024 * 1024) { it.toByte() }
        assertArrayEquals(body, RtspMessage.parseMessages("SETUP * RTSP/1.0\r\nContent-Length: ${body.size}\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body).messages.single().body)
    }

    @Test fun bplistPreservesNormalTypesAndSharedObjects() {
        val data = ByteArray(1024) { it.toByte() }
        val value = linkedMapOf("name" to "中国 Autunn", "values" to listOf(1L, true, 3.5), "data" to data)
        val decoded = BplistCodec.decode(BplistCodec.encode(value)) as Map<*, *>
        assertEquals(value["name"], decoded["name"])
        assertEquals(value["values"], decoded["values"])
        assertArrayEquals(data, decoded["data"] as ByteArray)
        val shared = BplistCodec.decode(plist(listOf(byteArrayOf(0xa2.toByte(), 1, 1), byteArrayOf(0x51, 65)))) as List<*>
        assertSame(shared[0], shared[1])
    }

    @Test fun bplistRejectsCyclesDepthAndMalformedReferences() {
        rejects { BplistCodec.decode(plist(listOf(byteArrayOf(0xa1.toByte(), 0)))) }
        val nested = (0..BplistCodec.MAX_DEPTH + 1).map { i ->
            if (i == BplistCodec.MAX_DEPTH + 1) byteArrayOf(0x09) else byteArrayOf(0xa1.toByte(), (i + 1).toByte())
        }
        rejects { BplistCodec.decode(plist(nested)) }
        rejects { BplistCodec.decode(plist(listOf(byteArrayOf(0xa1.toByte(), 4)))) }
        rejects { BplistCodec.decode(plist(listOf(byteArrayOf(0x4f, 0x13, -1, -1, -1, -1, -1, -1, -1, -1)))) }
        rejects { BplistCodec.decode(plist(listOf(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0x10, 1), byteArrayOf(0x09)))) }
        val badWidth = BplistCodec.encode("ok").also { it[it.size - 26] = 0 }
        rejects { BplistCodec.decode(badWidth) }
        val badOffset = BplistCodec.encode("ok").also { it[it.size - 33] = 0 }
        rejects { BplistCodec.decode(badOffset) }
    }

    @Test fun bplistBoundsCopiesEvenWhenObjectOffsetsAlias() {
        val data = byteArrayOf(0x4f, 0x12, 0, 0x10, 0, 0) + ByteArray(1024 * 1024)
        val root = byteArrayOf(0xaf.toByte(), 0x10, 40) + ByteArray(40) { (it + 1).toByte() }
        val encoded = plist(listOf(root, data), listOf(8) + List(40) { 8 + root.size })
        rejects { BplistCodec.decode(encoded) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded parser rejection") } catch (_: IllegalArgumentException) { }
    }

    private fun plist(objects: List<ByteArray>, explicitOffsets: List<Int>? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeBytes("bplist00")
        val offsets = mutableListOf<Int>()
        objects.forEach { offsets += bytes.size(); out.write(it) }
        val table = bytes.size()
        val actual = explicitOffsets ?: offsets
        actual.forEach(out::writeInt)
        out.write(ByteArray(6)); out.writeByte(4); out.writeByte(1)
        out.writeLong(actual.size.toLong()); out.writeLong(0); out.writeLong(table.toLong())
        return bytes.toByteArray()
    }
}
