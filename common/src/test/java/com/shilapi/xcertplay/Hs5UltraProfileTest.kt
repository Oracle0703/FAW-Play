package com.shilapi.xcertplay

import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayUltraNegotiationMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Hs5UltraProfileTest {
    @Test fun everyResumeCreatesRealHandlersWithoutClaimingPhoneVerification() {
        val factory = Hs5UltraProfile.factory(ContextWrapper(RuntimeEnvironment.getApplication()))
        val first = factory()
        val second = factory()
        assertNotSame(first.pluginRegistrations.single().handler, second.pluginRegistrations.single().handler)
        val config = second.createConfig(AirPlayDisplayConfig(1920, 720), second.createRuntime())
        assertEquals(AirPlayUltraNegotiationMode.EXPERIMENTAL, config.negotiationMode)
        assertTrue(config.negotiationAllowed)
        assertFalse(config.readiness.ready)
        assertEquals(6, config.readiness.missingRequirements.size)
        assertEquals(setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL, AirPlayFeature.UI_SYNC), config.readyFeatures)
        assertEquals("cn.autunn.hs5", second.pluginRegistrations.single().pluginName)
    }
}
