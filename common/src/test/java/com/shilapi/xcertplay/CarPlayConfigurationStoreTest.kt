package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayConfigurationStoreTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val saved get() = app.getSharedPreferences("faw_session_display", 0)

    @Before fun reset() {
        listOf("faw_session_display", "xcertplay_airplay", "diplay").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
    }

    @Test fun displaySurvivesWithoutAnActivityButRuntimeUsesCurrentSettingsAndIdentity() {
        val config = configuration()
        CarPlayConfigurationStore.save(app, config)
        val identity = AirPlayPersistence.loadIdentity(app)
        DiPlayPreferences.savePhone(app, "02:00:00:00:00:09", "Saved phone")
        AirPlayPersistence.saveWirelessEnabled(app, false)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val loaded = requireNotNull(CarPlayConfigurationStore.load(app))

        assertEquals(config.display, loaded.display)
        assertEquals(config.airPlay.main, loaded.airPlay.main)
        assertEquals(config.width, loaded.width)
        assertEquals(config.height, loaded.height)
        assertEquals(MfiTarget.USB_CH341, loaded.runtime.mfiTarget)
        assertEquals(CarPlayTransport.WIRELESS, loaded.runtime.transport)
        assertEquals("02:00:00:00:00:09", loaded.runtime.wirelessBluetoothDeviceAddress)
        assertFalse(loaded.microphoneEnabled)
        assertArrayEquals(identity.privateKey, loaded.identity.privateKey)
        assertEquals(identity.pairingId, loaded.identity.pairingId)
        assertNotEquals(config.identity.pairingId, loaded.identity.pairingId)
    }

    @Test fun snapshotDoesNotDuplicateIdentityKeysPairingsOrWirelessCredentials() {
        val config = configuration()
        AirPlayPersistence.savePairing(app, "existing-phone", ByteArray(32) { (it + 3).toByte() })
        CarPlayConfigurationStore.save(app, config)
        val json = requireNotNull(saved.getString("configuration", null))
        assertFalse(json.contains(config.identity.pairingId))
        assertFalse(json.contains("privateKey"))
        assertFalse(json.contains("existing-phone"))
        assertFalse(json.contains("passphrase"))
        assertEquals(setOf("configuration"), saved.all.keys)
    }

    @Test fun corruptedOrUnsupportedSnapshotLeavesExistingIdentityAndPairingsUntouched() {
        val identity = AirPlayPersistence.loadIdentity(app)
        val key = ByteArray(32) { (it + 3).toByte() }
        AirPlayPersistence.savePairing(app, "existing-phone", key)
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        val before = prefs.all.toMap()
        for (invalid in listOf("broken-json", "{\"version\":999}", "{\"version\":1,\"width\":-1,\"height\":720}")) {
            saved.edit().putString("configuration", invalid).commit()
            assertNull(CarPlayConfigurationStore.load(app))
            assertEquals(before, prefs.all)
            assertArrayEquals(identity.privateKey, AirPlayPersistence.loadIdentity(app).privateKey)
            assertArrayEquals(key, AirPlayPersistence.loadPairings(app) { _, _ -> }.get("existing-phone"))
        }
    }

    private fun configuration() = CarPlayResumeConfiguration(
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("Autunn Play", "test", "Autunn Play", "serial", "1", "1", 3)),
        AirPlayConfig("Autunn Play", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
            AirPlayDisplayConfig(1280, 480, widthPhysicalMm = 240, heightPhysicalMm = 90,
                viewArea = AirPlayInsets(1, 2, 3, 4), safeArea = AirPlayInsets(5, 6, 7, 8))),
        AirPlayIdentity(ByteArray(32) { 7 }, ByteArray(32) { 8 }, "not-saved-in-display"),
        1920, 720, CarPlaySessionDisplay(1280, 480, 0, true, true, 1920, 720),
        false, false, 14, true)
}
