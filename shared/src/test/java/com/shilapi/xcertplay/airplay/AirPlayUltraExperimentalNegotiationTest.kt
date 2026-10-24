package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test

class AirPlayUltraExperimentalNegotiationTest {
    private val implementation = AirPlayUltraImplementation(true, true, true, true, true)
    private val base = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
        AirPlayDisplayConfig(1280, 720))

    @Test
    fun explicitImplementedDevelopmentProfileNegotiatesBeforeAnyVerifiedFlagIsTrue() {
        val config = configured()
        val ultra = config.ultra!!
        assertTrue(ultra.negotiationAllowed)
        assertFalse(ultra.readiness.ready)
        assertEquals(AirPlayUltraRequirement.entries.toSet(), ultra.readiness.missingRequirements)
        assertTrue("uiSync" in setupEnabledFeatures(config, listOf("uiSync")))
        assertTrue(AirPlayInfoPlist.build(config).containsKey("uiSyncInfo"))
        assertFalse(ultra.readiness.targetPhoneVerified)
    }

    @Test
    fun defaultModeRemainsClosedEvenWithAllImplementationDeclarations() {
        val config = configured().let { it.copy(ultra = it.ultra!!.copy(
            negotiationMode = AirPlayUltraNegotiationMode.VERIFIED_ONLY)) }
        assertFalse(config.ultra!!.negotiationAllowed)
        assertArrayEquals(BplistCodec.encode(AirPlayInfoPlist.build(base)),
            BplistCodec.encode(AirPlayInfoPlist.build(config)))
        assertFalse("uiSync" in setupEnabledFeatures(config, listOf("uiSync")))
    }

    @Test
    fun experimentalSwitchAloneCannotEnableUnimplementedProfile() {
        val missing = listOf(
            implementation.copy(vehicleModelImplemented = false),
            implementation.copy(uiSyncImplemented = false),
            implementation.copy(vehicleAssetsInstalled = false),
            implementation.copy(localRendererImplemented = false),
            implementation.copy(displayArbitrationImplemented = false),
        )
        missing.forEach { value ->
            val config = configured().let { it.copy(ultra = it.ultra!!.copy(implementation = value)) }
            assertFalse(config.ultra!!.negotiationAllowed)
            assertFalse("uiSync" in setupEnabledFeatures(config, listOf("uiSync")))
        }
    }

    @Test
    fun experimentalProfileRequiresRegisteredRuntimeAndEachRequiredClientFactory() {
        val config = configured()
        assertFalse(config.ultra!!.copy(runtime = null).negotiationAllowed)
        assertFalse(config.ultra!!.copy(runtime = AirPlayUltraRuntime.empty()).negotiationAllowed)
        val onlyOneCafChannel = AirPlayUltraRuntime.builder().rcs(
            listOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA), factory).build()
        assertFalse(config.ultra!!.copy(runtime = onlyOneCafChannel,
            readyFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL)).negotiationAllowed)
        assertFalse(config.ultra!!.copy(readyFeatures = emptySet()).negotiationAllowed)
    }

    @Test
    fun missingSidecarIsStillAConfigurationErrorInExperimentalMode() {
        val runtime = AirPlayUltraRuntime.builder().rcs(
            listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL), factory).build()
        val config = configured().let { it.copy(ultra = it.ultra!!.copy(runtime = runtime, uiSyncInfo = null)) }
        assertTrue(config.ultra!!.negotiationAllowed)
        assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(config, true)
        }
    }

    @Test
    fun firstExperimentalNegotiationStillRequiresPhoneProposalAndEventPort() {
        val config = configured()
        assertFalse("uiSync" in setupEnabledFeatures(config, null))
        assertFalse("uiSync" in setupEnabledFeatures(config, emptyList<String>()))
        assertFalse("uiSync" in setupEnabledFeatures(config, listOf("uiSync"), eventAvailable = false))
        assertFalse(AirPlayInfoPlist.build(config, setOf("viewAreas")).containsKey("uiSyncInfo"))
    }

    @Test
    fun provisioningCarriesModeAndImplementationWithoutPromotingValidationEvidence() {
        val provisioning = AirPlayUltraProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactories = mapOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to factory),
            negotiationMode = AirPlayUltraNegotiationMode.EXPERIMENTAL,
            implementation = implementation,
        )
        val config = provisioning.createConfig(AirPlayDisplayConfig(1920, 720), provisioning.createRuntime())
        assertEquals(AirPlayUltraNegotiationMode.EXPERIMENTAL, config.negotiationMode)
        assertEquals(implementation, config.implementation)
        assertTrue(config.negotiationAllowed)
        assertFalse(config.readiness.ready)
    }

    private fun configured(): AirPlayConfig {
        val runtime = AirPlayUltraRuntime.builder()
            .sidecar(AirPlayFeature.UI_SYNC, emptyMap<String, Any?>())
            .rcs(listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL), factory).build()
        return base.copy(ultra = AirPlayUltraConfig(
            cluster = AirPlayDisplayConfig(1920, 720), uiSyncInfo = AirPlayUiSyncInfo(),
            readyFeatures = setOf(AirPlayFeature.UI_SYNC), runtime = runtime,
            negotiationMode = AirPlayUltraNegotiationMode.EXPERIMENTAL, implementation = implementation,
        ))
    }

    private val factory = RcsDataStreamHandlerFactory { object : RcsDataStreamHandler {} }
}
