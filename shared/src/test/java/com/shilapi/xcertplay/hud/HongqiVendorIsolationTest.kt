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
        FawOutputSettings.setBatteryToIphone(app, true)
        FawOutputSettings.setWheelSpeedToIphone(app, true)
        FawOutputSettings.setVideoWhileParked(app, true)
        FawOutputSettings.setLegacyVehicleProbe(app, true)
        assertFalse(FawOutputSettings.batteryToIphoneActive(app))
        assertFalse(FawOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(FawOutputSettings.videoWhileParkedActive(app))
        assertFalse(FawOutputSettings.legacyVehicleProbe(app))
        assertNull(FawVehicleCapabilityProbe.probe(app).capabilities)
    }

    @Test fun hudPreferenceSurvivesTheCompatibilityFacade() {
        val app = RuntimeEnvironment.getApplication()
        FawOutputSettings.setEnabled(app, false)
        assertFalse(FawOutputSettings.enabled(app))
        FawOutputSettings.setEnabled(app, true)
        assertTrue(FawOutputSettings.enabled(app))
        assertTrue(app.getSharedPreferences("diplay_byd_outputs", 0).getBoolean("navigation_enabled", false))
    }

    @Test fun obsoleteMapHoldCannotInvokeTheCallerOrAcquireStockMap() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(FawOemClusterNavi.holdForLaunch(app, "test") {
            throw AssertionError("The HS5 build must not control another vendor's map")
        })
        assertFalse(FawOutputSettings.available(app))
        assertNull(FawNavigationOutputs.batteryStatus(app).snapshot())
        assertNull(FawNavigationOutputs.wheelSpeed(app).drain())
    }
}
