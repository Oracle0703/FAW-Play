package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the actual control/event receive loops, including their response flush order. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlayViewAreaHandshakeTest {
    @Test fun controlRequestCompletesMainViewAreaAfterFlushingItsResponse() {
        val flushes = mutableListOf<String>()
        val control = MemorySocket(request("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 0)), "control", flushes)
        val event = MemorySocket(ByteArray(0), "event", flushes)
        val session = session(control, event)
        invokeLoop(session, "runControl")
        val acknowledgement = RtspMessage.parseMessages(control.output.toByteArray()).messages.single()
        assertEquals("200", acknowledgement.path)
        assertEquals("37", acknowledgement.headers["cseq"])
        val commands = eventMessages(event)
        assertEquals("iPhone must receive a view-area completion command", 1, commands.size)
        assertViewArea(commands.single(), AirPlayInfoPlist.MAIN_UUID)
        assertTrue(flushes.toString(), flushes.indexOf("control") < flushes.indexOf("event"))
    }

    @Test fun encryptedEventRequestIsHandledAndAcknowledgedBeforeViewAreaCompletion() {
        val wire = request("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID,
            "viewArea" to mapOf("viewAreaIndex" to 0)))
        val event = MemorySocket(ControlCipher(KEY, KEY).encrypt(wire))
        val session = session(MemorySocket(ByteArray(0)), event)
        invokeLoop(session, "runEventRead", event)
        val messages = eventMessages(event)
        assertEquals("A generic 200 response alone does not complete requestViewArea", 2, messages.size)
        assertEquals("200", messages[0].path)
        assertEquals("37", messages[0].headers["cseq"])
        assertViewArea(messages[1], AirPlayInfoPlist.ALT_UUID)
    }

    @Test fun unknownViewAreaIsRejectedWithoutSendingAnUpdate() {
        val event = MemorySocket(ControlCipher(KEY, KEY).encrypt(request("requestViewArea",
            mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 1))))
        val session = session(MemorySocket(ByteArray(0)), event)
        invokeLoop(session, "runEventRead", event)
        val messages = eventMessages(event)
        assertEquals(1, messages.size)
        assertEquals("400", messages.single().path)
    }

    @Test fun ordinaryEventCommandsReachTheSameSessionListenerAsControlCommands() {
        val observed = mutableListOf<String>()
        val event = MemorySocket(ControlCipher(KEY, KEY).encrypt(request("modesChanged", emptyMap())))
        val session = session(MemorySocket(ByteArray(0)), event, observed)
        invokeLoop(session, "runEventRead", event)
        assertEquals(listOf("modesChanged"), observed)
        assertEquals("200", eventMessages(event).single().path)
    }

    @Test fun pipelinedControlRequestsAreAllAcknowledgedBeforeSendingViewAreaUpdates() {
        val flushes = mutableListOf<String>()
        val control = MemorySocket(
            request("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 0)) +
                request("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID, "viewArea" to 0)),
            "control", flushes,
        )
        val event = MemorySocket(ByteArray(0), "event", flushes)
        invokeLoop(session(control, event), "runControl")
        val acknowledgements = RtspMessage.parseMessages(control.output.toByteArray()).messages
        assertEquals(listOf("200", "200"), acknowledgements.map { it.path })
        val commands = eventMessages(event)
        assertEquals(2, commands.size)
        assertViewArea(commands[0], AirPlayInfoPlist.MAIN_UUID)
        assertViewArea(commands[1], AirPlayInfoPlist.ALT_UUID)
        assertTrue(flushes.toString(), flushes.indexOf("control") < flushes.indexOf("event"))
    }

    @Test fun anEventResponseDoesNotReachTheCommandHandlerOrReceiveAnAcknowledgement() {
        val observed = mutableListOf<String>()
        val event = MemorySocket(ControlCipher(KEY, KEY).encrypt(
            "RTSP/1.0 200 OK\r\nCSeq: 37\r\nContent-Length: 0\r\n\r\n".toByteArray(),
        ))
        invokeLoop(session(MemorySocket(ByteArray(0)), event, observed), "runEventRead", event)
        assertTrue(observed.isEmpty())
        assertTrue(event.output.size() == 0)
    }

    @Test fun negotiatedMainUiContextIsShownOnlyAfterItsEventAcknowledgement() {
        val event = MemorySocket(ControlCipher(KEY, KEY).encrypt(request("suggestUI",
            mapOf("uuid" to AirPlayInfoPlist.MAIN_UUID, "urls" to listOf("music:main")))))
        val session = session(MemorySocket(ByteArray(0)), event)
        val display = session.javaClass.getDeclaredField("viewAreaControl").apply { isAccessible = true }
            .get(session) as AirPlayViewAreaControl
        display.negotiate(listOf("uiContext", "altScreen"), true)
        invokeLoop(session, "runEventRead", event)
        val messages = eventMessages(event)
        assertEquals(2, messages.size)
        assertEquals("200", messages[0].path)
        val command = BplistCodec.decode(messages[1].body) as Map<*, *>
        assertEquals("showUI", command["type"])
        assertEquals("music:main", (command["params"] as Map<*, *>)["url"])
    }

    @Test fun viewAreaUpdateWaitsForBothResponsesAndIsRetainedUntilTheEventChannelIsReady() {
        var eventReady = false
        val sent = mutableListOf<Map<String, Any?>>()
        val control = AirPlayViewAreaControl(testConfig()) { command ->
            if (eventReady) sent.add(command)
            eventReady
        }
        control.responseStarted()
        control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 0))
        control.responseStarted()
        control.responseSent()
        control.flush()
        assertTrue(sent.isEmpty())
        control.responseSent()
        assertTrue(sent.isEmpty())
        eventReady = true
        control.flush()
        assertEquals(1, sent.size)
        control.flush()
        assertEquals("The pending update must only be sent once", 1, sent.size)
    }

    @Test fun anUnadvertisedDisplayIsRejected() {
        val sent = mutableListOf<Map<String, Any?>>()
        val control = AirPlayViewAreaControl(testConfig().copy(cluster = null)) { sent.add(it); true }
        for (uuid in listOf(AirPlayInfoPlist.ALT_UUID, "unknown-display")) {
            assertThrows(IllegalArgumentException::class.java) {
                control.receive("requestViewArea", mapOf("displayUUID" to uuid, "viewArea" to 0))
            }
        }
        control.flush()
        assertTrue(sent.isEmpty())
    }

    @Test fun aRetiredResponseReleasesItsBarrierWithoutClaimingAnAcknowledgement() {
        val sent = mutableListOf<Map<String, Any?>>()
        val control = AirPlayViewAreaControl(testConfig()) { sent.add(it); true }
        control.responseStarted()
        control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 0))
        control.responseAborted()
        assertTrue("An aborted response must not flush queued commands", sent.isEmpty())
        control.responseStarted()
        control.responseSent()
        assertEquals("The replacement must not remain behind the retired reader's barrier", 1, sent.size)
    }

    private fun session(control: MemorySocket, event: MemorySocket, observed: MutableList<String> = mutableListOf()): AirPlaySession {
        val session = AirPlaySession(control,
            testConfig(),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { observed.add(type) }
            }, object : AirPlayMediaHandler {})
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(KEY, KEY))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        return session
    }

    private fun testConfig() = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
        AirPlayDisplayConfig(960, 540), cluster = AirPlayDisplayConfig(1600, 600))

    private fun invokeLoop(session: AirPlaySession, method: String, event: Socket? = null) {
        try {
            if (event == null) session.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(session)
            else session.javaClass.getDeclaredMethod(method, Socket::class.java).apply { isAccessible = true }.invoke(session, event)
        } finally { session.close() }
    }

    private fun eventMessages(event: MemorySocket): List<RtspMessage.Request> =
        RtspMessage.parseMessages(ControlCipher(KEY, KEY).decrypt(event.output.toByteArray()).data).messages

    private fun assertViewArea(message: RtspMessage.Request, uuid: String) {
        assertEquals("POST", message.method)
        val body = BplistCodec.decode(message.body) as Map<*, *>
        assertEquals("updateViewArea", body["type"])
        val params = body["params"] as Map<*, *>
        assertEquals(uuid, params["uuid"])
        assertEquals(0L, params["viewAreaIndex"])
        assertEquals(0L, params["animationDurationMillis"])
    }

    private fun request(type: String, params: Map<String, Any?>): ByteArray {
        val body = BplistCodec.encode(mapOf("type" to type, "params" to params))
        return "POST /command RTSP/1.0\r\nCSeq: 37\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
    }

    private class MemorySocket(bytes: ByteArray, private val name: String = "event", private val flushes: MutableList<String> = mutableListOf()) : Socket() {
        val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(bytes)
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun flush() { flushes.add(name) }
        }
    }

    companion object { private val KEY = ByteArray(32) }
}
