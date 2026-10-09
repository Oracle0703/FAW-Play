package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class CarHotspotSetupTest {
    @Test fun legacyAutomaticHotspotSettingCannotStartOrReplaceWifiDirect() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putBoolean("wireless_enabled", true).putString("wireless_hotspot_mode", "MANUAL")
            .putString("manual_hotspot_ssid", "Legacy hotspot")
            .putString("manual_hotspot_passphrase", "test-password").commit()
        CarHotspotSettings.setEnabled(context, true)
        for (hasSession in listOf(false, true)) {
            assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession))
        }
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
    }
}
