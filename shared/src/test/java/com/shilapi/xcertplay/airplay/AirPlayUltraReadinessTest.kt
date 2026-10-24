package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test

class AirPlayUltraReadinessTest {
    @Test
    fun registeringTransportAndEmptySidecarCannotAdvertiseUnimplementedUltra() {
        val provisioning = AirPlayUltraProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactories = mapOf(
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to RcsDataStreamHandlerFactory { null },
            ),
            runtimeFeatures = setOf(AirPlayFeature.ALT_SCREEN),
        )
        val runtime = provisioning.createRuntime()
        val config = config(provisioning.createConfig(cluster, runtime))
        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        val proposal = AirPlayFeatureNegotiation.propose(
            config, listOf("uiSync", "vehicleStateProtocol", "altScreen", "viewAreas"), true,
        )
        assertFalse(proposal.enabledFeatures.contains("uiSync"))
        assertFalse(proposal.enabledFeatures.contains("vehicleStateProtocol"))
        assertFalse(proposal.enabledFeatures.contains("altScreen"))
        assertTrue(proposal.enabledFeatures.contains("viewAreas"))
        assertNull(AirPlayFeatureNegotiation.resolveInfoSidecar(config, AirPlayFeature.UI_SYNC))
        assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.validateEnabledFeatures(config, setOf(AirPlayFeature.UI_SYNC))
        }
    }

    @Test
    fun readinessListsConcreteRemainingContractsAndRequiresAllOfThem() {
        val readiness = AirPlayUltraReadiness(
            vehicleModelVerified = true,
            uiSyncVerified = true,
            vehicleAssetsVerified = true,
            localRendererVerified = true,
            displayArbitrationVerified = true,
        )
        assertFalse(readiness.ready)
        assertEquals(setOf(AirPlayUltraRequirement.TARGET_PHONE), readiness.missingRequirements)
        assertTrue(readiness.copy(targetPhoneVerified = true).ready)
    }

    @Test
    fun evenCompleteReadinessCannotSubstituteForMissingChannelImplementation() {
        val config = config(AirPlayUltraConfig(
            cluster = cluster,
            uiSyncInfo = AirPlayUiSyncInfo(),
            readyFeatures = setOf(AirPlayFeature.UI_SYNC),
            readiness = AirPlayUltraReadiness(true, true, true, true, true, true),
        ))
        assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(config, true)
        }
    }

    private fun config(ultra: AirPlayUltraConfig) = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0", main = AirPlayDisplayConfig(1280, 720), ultra = ultra,
    )

    private val cluster = AirPlayDisplayConfig(1920, 720)
}
