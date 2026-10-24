package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authorization belongs before stream allocation and must not tear down another journey. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlaySessionLicenseTest {
    @Test fun deniedSetupCannotAllocateScreenAudioOrDataStreams() {
        val fixture = Fixture { false }
        val session = fixture.session()
        try {
            assertEquals(403, setup(session, 110, 100, 130).status)
            assertTrue(fixture.screenSetups.isEmpty())
            assertTrue(fixture.audioSetups.isEmpty())
            assertTrue(fixture.dataSetups.isEmpty())
            assertTrue(session.activeStreams.isEmpty())
            assertNull(session.mainScreenSessionToken())
        } finally { session.close() }
    }

    @Test fun deniedRecordCannotActivateThePhone() {
        val fixture = Fixture { false }
        val session = fixture.session()
        try {
            assertEquals(403, request(session, "RECORD").status)
            assertTrue(fixture.activations.isEmpty())
            assertTrue(fixture.mediaActivations.isEmpty())
        } finally { session.close() }
    }

    @Test fun denyingAnotherSessionPreservesTheEstablishedScreenAndItsSocket() {
        val established = mutableSetOf<AirPlaySession>()
        val fixture = Fixture { it in established }
        val mainSocket = Socket()
        val main = fixture.session(mainSocket)
        val auxiliary = fixture.session()
        established.add(main)
        try {
            assertEquals(200, setup(main, 110).status ?: 200)
            assertEquals(200, request(main, "RECORD").status)
            val mainToken = main.mainScreenSessionToken()
            assertNotNull(mainToken)

            assertEquals(403, setup(auxiliary, 111, 100).status)
            assertEquals(403, request(auxiliary, "RECORD").status)
            auxiliary.close()

            assertSame(mainToken, main.mainScreenSessionToken())
            assertFalse(mainSocket.isClosed)
            assertEquals(listOf(main), fixture.screenSetups)
            assertEquals(listOf(main), fixture.activations)
            assertFalse(fixture.closedSessions.contains(main))
        } finally { auxiliary.close(); main.close() }
    }

    @Test fun anEstablishedSessionCanContinueStreamsWhenNewConnectionsAreDenied() {
        var licenseValid = true
        val established = mutableSetOf<AirPlaySession>()
        val fixture = Fixture { licenseValid || it in established }
        val session = fixture.session()
        try {
            assertEquals(200, setup(session, 110, 100).status ?: 200)
            assertEquals(200, request(session, "RECORD").status)
            established.add(session)
            licenseValid = false

            assertEquals(200, setup(session, 110, 100).status ?: 200)
            assertEquals(200, request(session, "RECORD").status)
            assertEquals(listOf(session, session), fixture.screenSetups)
            assertEquals(listOf(session, session), fixture.audioSetups)
            assertEquals(listOf(session, session), fixture.activations)
            assertNotNull(session.mainScreenSessionToken())
        } finally { session.close() }
    }

    private class Fixture(private val allowed: (AirPlaySession) -> Boolean) {
        val screenSetups = mutableListOf<AirPlaySession>()
        val audioSetups = mutableListOf<AirPlaySession>()
        val dataSetups = mutableListOf<AirPlaySession>()
        val activations = mutableListOf<AirPlaySession>()
        val mediaActivations = mutableListOf<AirPlaySession>()
        val closedSessions = mutableListOf<AirPlaySession>()

        fun session(socket: Socket = Socket()): AirPlaySession = AirPlaySession(
            socket = socket,
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
                AirPlayDisplayConfig(800, 480)),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun authorizeSession(session: AirPlaySession): Boolean = allowed(session)
                override fun onSessionActive(session: AirPlaySession) { activations.add(session) }
            },
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int {
                    screenSetups.add(session)
                    return 32000
                }
                override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?> {
                    audioSetups.add(session)
                    return mapOf("type" to type, "dataPort" to 32001)
                }
                override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> {
                    dataSetups.add(session)
                    return mapOf("type" to 130, "dataPort" to 32002)
                }
                override fun onSessionActive(session: AirPlaySession) { mediaActivations.add(session) }
                override fun onSessionClosed(session: AirPlaySession) { closedSessions.add(session) }
            },
        )
    }

    private fun setup(session: AirPlaySession, vararg types: Int): RtspMessage.Response = request(session, "SETUP",
        BplistCodec.encode(mapOf("streams" to types.map { mapOf("type" to it.toLong()) })))

    private fun request(session: AirPlaySession, method: String, body: ByteArray = ByteArray(0)): RtspMessage.Response {
        val request = RtspMessage.Request(method, "*", "RTSP/1.0", emptyMap(), body)
        return AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request) as RtspMessage.Response
    }
}
