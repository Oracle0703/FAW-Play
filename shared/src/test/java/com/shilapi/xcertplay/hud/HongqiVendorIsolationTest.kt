package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiVendorIsolationTest {
    @Test fun savedVendorFlagsCannotReenableVehicleServiceReads() {
        val app = RuntimeEnvironment.getApplication()
        AutunnOutputSettings.setBatteryToIphone(app, true)
        AutunnOutputSettings.setWheelSpeedToIphone(app, true)
        AutunnOutputSettings.setVideoWhileParked(app, true)
        AutunnOutputSettings.setLegacyVehicleProbe(app, true)
        assertFalse(AutunnOutputSettings.batteryToIphoneActive(app))
        assertFalse(AutunnOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(AutunnOutputSettings.videoWhileParkedActive(app))
        assertFalse(AutunnOutputSettings.legacyVehicleProbe(app))
        assertNull(AutunnVehicleCapabilityProbe.probe(app).capabilities)
    }

    @Test fun hudPreferenceSurvivesTheCompatibilityFacade() {
        val app = RuntimeEnvironment.getApplication()
        AutunnOutputSettings.setEnabled(app, false)
        assertFalse(AutunnOutputSettings.enabled(app))
        AutunnOutputSettings.setEnabled(app, true)
        assertTrue(AutunnOutputSettings.enabled(app))
        assertTrue(app.getSharedPreferences("diplay_byd_outputs", 0).getBoolean("navigation_enabled", false))
    }

    @Test fun obsoleteMapHoldCannotInvokeTheCallerOrAcquireStockMap() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(AutunnOemClusterNavi.holdForLaunch(app, "test") {
            throw AssertionError("The HS5 build must not control another vendor's map")
        })
        assertFalse(AutunnOutputSettings.available(app))
        assertNull(AutunnNavigationOutputs.batteryStatus(app).snapshot())
        assertNull(AutunnNavigationOutputs.wheelSpeed(app).drain())
    }
}
