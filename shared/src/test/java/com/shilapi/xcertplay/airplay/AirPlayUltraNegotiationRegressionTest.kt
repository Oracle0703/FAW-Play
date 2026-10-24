package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test

class AirPlayUltraNegotiationRegressionTest {
    private val base = AirPlayConfig(
        "test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
        AirPlayDisplayConfig(1920, 720), cluster = AirPlayDisplayConfig(1920, 720),
        receiverManagedClusterUi = false, hevc = true, videoInCar = true,
    )

    @Test
    fun compiledTransportAndUnreadyProvisioningKeepClassicInfoAndNegotiationIdentical() {
        val config = configured(AirPlayUltraReadiness())
        assertArrayEquals(BplistCodec.encode(AirPlayInfoPlist.build(base)),
            BplistCodec.encode(AirPlayInfoPlist.build(config)))
        for (proposal in listOf(null, emptyList<String>(),
            listOf("uiSync", "vehicleStateProtocol", "viewAreas", "altScreen", "videoPlayback"))) {
            assertEquals(setupEnabledFeatures(base, proposal), setupEnabledFeatures(config, proposal))
        }
        val info = AirPlayInfoPlist.build(config, setOf("uiSync", "vehicleStateProtocol"))
        assertFalse(info.containsKey("uiSyncInfo"))
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
    }

    @Test
    fun readyUltraStillRequiresExplicitProposalAndAvailableEventTransport() {
        val config = configured(AirPlayUltraReadiness(true, true, true, true, true, true))
        assertFalse("uiSync" in setupEnabledFeatures(config, null))
        assertFalse("uiSync" in setupEnabledFeatures(config, emptyList<String>()))
        assertFalse("uiSync" in setupEnabledFeatures(config, listOf("viewAreas")))
        assertFalse("uiSync" in setupEnabledFeatures(config, listOf("uiSync"), eventAvailable = false))
        assertEquals(listOf("uiSync", "iAPChannel"), setupEnabledFeatures(config, listOf("uiSync", "uiSync")))
        assertTrue(AirPlayInfoPlist.build(config).containsKey("uiSyncInfo"))
        assertFalse(AirPlayInfoPlist.build(config, setOf("viewAreas")).containsKey("uiSyncInfo"))
        assertTrue(AirPlayInfoPlist.build(config, setOf("uiSync")).containsKey("uiSyncInfo"))
    }

    @Test
    fun targetedUltraTeardownDoesNotSelectLegacyDataOrScreenTypes() {
        assertEquals(emptyList<Int>(), teardownStreamTypes(mapOf(
            "streams" to listOf(mapOf("type" to 130, "streamID" to 3L)),
        )))
        assertEquals(listOf(130), teardownStreamTypes(mapOf(
            "streams" to listOf(mapOf("type" to 130, "streamID" to 1L)),
        )))
        assertEquals(listOf(110), teardownStreamTypes(mapOf(
            "streams" to listOf(mapOf("type" to 130, "streamID" to 3L), mapOf("type" to 110)),
        )))
    }

    private fun configured(readiness: AirPlayUltraReadiness): AirPlayConfig {
        val runtime = AirPlayUltraRuntime.builder()
            .sidecar(AirPlayFeature.UI_SYNC, emptyMap<String, Any?>())
            .rcs(listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL), RcsDataStreamHandlerFactory {
                object : RcsDataStreamHandler {}
            })
            .build()
        return base.copy(ultra = AirPlayUltraConfig(
            cluster = base.cluster!!, uiSyncInfo = AirPlayUiSyncInfo(),
            readyFeatures = setOf(AirPlayFeature.UI_SYNC), runtime = runtime, readiness = readiness,
        ))
    }
}
