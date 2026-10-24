package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlaySetupFeaturesTest {
    private val config = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
        sourceVersion = "1", main = AirPlayDisplayConfig(1920, 720),
        cluster = CarPlayClusterDisplay.config(1920, 720),
        receiverManagedClusterUi = false, hevc = true, videoInCar = true,
    )

    @Test fun olderPeerReceivesIapCapabilityInTheEncodedSetupResponse() {
        var activated = false
        var dataStreams = 0
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { activated = true }
            }, object : AirPlayMediaHandler {
                override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
                    dataStreams++
                    return null
                }
            })
        try {
            // The failing peer sends these display features without repeating iAPChannel.
            val request = RtspMessage.Request("SETUP", "*", "RTSP/1.0", emptyMap(),
                BplistCodec.encode(mapOf("features" to listOf("viewAreas", "altScreen"), "timingPort" to 0L)))
            val response = AirPlaySession::class.java.getDeclaredMethod("handleSetup", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(session, request) as RtspMessage.Response
            val body = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(listOf("viewAreas", "altScreen", "iAPChannel"), body["enabledFeatures"])
            assertTrue((body["eventPort"] as Number).toInt() > 0)
            // Declaring support does not manufacture a type-130 stream or a usable runtime.
            assertEquals(0, dataStreams)
            assertFalse(activated)
            assertFalse(session.hasRenderedMainScreen())
            assertFalse((body["enabledFeatures"] as List<*>).contains("videoPlayback"))
            assertFalse((body["enabledFeatures"] as List<*>).contains("hevc"))
        } finally { session.close() }
    }

    @Test fun unavailableEventTransportNeverAdvertisesIapOrEventBackedOptions() {
        for (proposal in listOf(null, emptyList<String>(), listOf("viewAreas", "altScreen", "iAPChannel", "videoPlayback"))) {
            val enabled = setupEnabledFeatures(config, proposal, eventAvailable = false)
            assertFalse("iAPChannel" in enabled)
            assertFalse("altScreen" in enabled)
            assertFalse("videoPlayback" in enabled)
        }
    }

    @Test fun explicitAndLegacyIapCapabilitiesRemainSingleWithoutChangingDisplayPolicy() {
        val explicit = setupEnabledFeatures(config, listOf("iAPChannel", "iAPChannel", "viewAreas"))
        assertEquals(listOf("iAPChannel", "viewAreas"), explicit)
        assertEquals(1, setupEnabledFeatures(config, null).count { it == "iAPChannel" })
        val noCluster = setupEnabledFeatures(config.copy(cluster = null), listOf("viewAreas", "altScreen"))
        assertEquals(listOf("viewAreas", "iAPChannel"), noCluster)
        assertFalse("altScreen" in noCluster)
        assertFalse("videoPlayback" in noCluster)
    }
}
