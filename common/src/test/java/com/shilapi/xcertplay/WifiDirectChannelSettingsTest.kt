package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class WifiDirectChannelSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun oldManualChannelCannotReplaceAutomaticSelection() {
        for (channel in listOf(0, 6, 36, 149, 165)) {
            prefs.edit().putInt("wifi_p2p_preferred_channel", channel).commit()
            assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
            AirPlayPersistence.saveWifiP2pPreferredChannel(context, channel)
            assertEquals(0, prefs.getInt("wifi_p2p_preferred_channel", -1))
        }
    }

    @Test fun invalidOrCorruptSavedChannelsUseAuto() {
        for (channel in listOf(-1, 12, 14, 52, 100, 196)) {
            prefs.edit().putInt("wifi_p2p_preferred_channel", channel).commit()
            assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
        }
        // A legacy preference with the wrong type must not crash app startup.
        prefs.edit().putString("wifi_p2p_preferred_channel", "149").commit()
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
        assertEquals(0, prefs.getInt("wifi_p2p_preferred_channel", -1))
    }

    @Test fun wrongTypedWirelessFlagIsRepaired() {
        prefs.edit().putInt("wireless_enabled", 1)
            .putString("wireless_hotspot_mode", "WIFI_P2P")
            .putInt("wifi_p2p_preferred_channel", 0).commit()

        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
        assertTrue(prefs.getBoolean("wireless_enabled", false))
    }

    @Test fun wrongTypedHotspotModeIsRepaired() {
        prefs.edit().putBoolean("wireless_enabled", true)
            .putBoolean("wireless_hotspot_mode", true)
            .putInt("wifi_p2p_preferred_channel", 0).commit()

        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("WIFI_P2P", prefs.getString("wireless_hotspot_mode", null))
    }

    @Test fun repairingChannelTypePreservesPairingAndAudioAndIsStableOnRepeatedStartup() {
        ConnectionModePolicy.enforce(context)
        prefs.edit().putString("pairing_id", "test-pairing-id")
            .putStringSet("pairing_ids", setOf("test-controller"))
            .putString("identity_private", "test-private-key")
            .putInt("media_audio_channel", 11)
            .putInt("navigation_audio_channel", 14)
            .putInt("display_fps", 60).commit()
        val before = prefs.all.toMap()
        prefs.edit().putString("wifi_p2p_preferred_channel", "149").commit()

        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(context))
        assertEquals(before, prefs.all)
        ConnectionModePolicy.enforce(context)
        assertEquals(before, prefs.all)
    }

    @Test fun hostAdvertisesWirelessWifiDirectAndAutoDespiteLegacyUsbAndHotspotChoices() {
        prefs.edit().putBoolean("wireless_enabled", false).putString("wireless_hotspot_mode", "MANUAL")
            .putInt("wifi_p2p_preferred_channel", 149).commit()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
                .set(host, AirPlayPersistence.loadIdentity(host))
            CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings")
                .apply { isAccessible = true }.invoke(host)
            val config = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
                .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
            assertEquals(CarPlayTransport.WIRELESS, config.transport)
            assertEquals(WirelessHotspotMode.WIFI_P2P, config.wirelessHotspotMode)
            assertEquals(0, config.wifiP2pPreferredChannel)
        } finally {
            (CarPlayHostActivity::class.java.getDeclaredField("mainHandler").apply { isAccessible = true }
                .get(host) as Handler).removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(host) as ExecutorService).shutdownNow()
            }
        }
    }
}
