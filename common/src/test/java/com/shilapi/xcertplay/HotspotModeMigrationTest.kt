package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class HotspotModeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun oldLocalSelectionMigratesToAutomaticWithoutLosingCarHotspotDetails() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-password")
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("WIFI_P2P", prefs.getString("wireless_hotspot_mode", null))
        assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun freshInstallUsesAutomaticWifi() {
        prefs.edit().clear().apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("WIFI_P2P", prefs.getString("wireless_hotspot_mode", null))
    }

    @Test fun existingWifiDirectSelectionIsPreserved() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun automaticSelectionIsNotReplacedByCarHotspotOnOlderAndroid() {
        prefs.edit().putString("wireless_hotspot_mode", "WIFI_P2P").apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun unconfiguredOldDefaultMigratesToAutomatic() {
        prefs.edit().clear().putString("wireless_hotspot_mode", "MANUAL").apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun configuredCarHotspotMigratesToWifiDirectWithoutClearingOtherData() {
        prefs.edit().clear().putString("wireless_hotspot_mode", "MANUAL")
            .putString("manual_hotspot_ssid", "HS5 hotspot")
            .putString("manual_hotspot_passphrase", "test-password").apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun configuredExistingWifiMigratesToWifiDirectWithoutClearingOtherData() {
        prefs.edit().clear().putString("wireless_hotspot_mode", "EXISTING_WIFI").apply()
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test Wi-Fi", "test-password")
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("Test Wi-Fi", AirPlayPersistence.loadExistingWifiSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadExistingWifiPassphrase(context))
    }

    @Test fun invalidStoredModeUsesAutomatic() {
        prefs.edit().clear().putString("wireless_hotspot_mode", "invalid").apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun oldUsbAndManualChannelSelectionsCannotReturn() {
        prefs.edit().clear().putBoolean("wireless_enabled", false)
            .putInt("wifi_p2p_preferred_channel", 149).apply()
        assertEquals(true, AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
        AirPlayPersistence.saveWirelessEnabled(context, false)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWifiP2pPreferredChannel(context, 149)
        assertEquals(true, AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
    }
}
