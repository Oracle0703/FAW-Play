package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlayWireDiagnosticsTest {
    @Test fun largeInfoResponseKeepsItsWireBodyWhileDiagnosticsStayBounded() {
        val control = MemorySocket(("GET /info RTSP/1.0\r\nCSeq: 7\r\n" +
            "X-Private: secret-header-value\r\n\r\nOPTIONS * RTSP/1.0\r\nCSeq: 8\r\n\r\n").toByteArray())
        val logs = mutableListOf<String>()
        val icon = ByteArray(18_000) { (it % 251).toByte() }
        val session = AirPlaySession(control,
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
                AirPlayDisplayConfig(800, 480), icons = listOf(AirPlayIcon(64, 64, icon))),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { logs.add(message) }
            }, object : AirPlayMediaHandler {})
        try {
            session.javaClass.getDeclaredMethod("runControl").apply { isAccessible = true }.invoke(session)
            val responses = RtspMessage.parseMessages(control.output.toByteArray()).messages
            assertEquals(2, responses.size)
            assertEquals("200", responses[0].path)
            assertEquals("7", responses[0].headers["cseq"])
            assertTrue(responses[0].body.size > icon.size)
            assertTrue(BplistCodec.decode(responses[0].body) is Map<*, *>)
            assertEquals("8", responses[1].headers["cseq"])
            val traces = logs.filter { it.startsWith("TRACE ") }
            assertTrue(traces.any { it.contains("airplay control tx status=200 cseq=7 contentBytes=") })
            assertTrue(traces.all { it.length < 256 })
            assertFalse(logs.any { it.contains("secret-header-value") || it.contains("wireHex=") || it.contains("bodyHex=") })
        } finally { session.close() }
    }

    private class MemorySocket(bytes: ByteArray) : Socket() {
        val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(bytes)
        override fun getInputStream() = input
        override fun getOutputStream() = output
    }
}
