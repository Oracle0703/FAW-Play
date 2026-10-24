package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrandMigrationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)

    @Before fun reset() {
        prefs.edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(app)
    }

    @Test fun oldDefaultsMigrateWithoutChangingIdentityPairingsOrOtherSettings() {
        val identity = AirPlayPersistence.loadIdentity(app)
        val pairing = ByteArray(32) { (it + 4).toByte() }
        AirPlayPersistence.savePairing(app, "saved-phone", pairing)
        prefs.edit().putString("manual_hotspot_ssid", "saved-network")
            .putString("manual_hotspot_passphrase", "test-only-passphrase")
            .putInt("display_scale_tenths", 12).commit()
        for (old in listOf("红旗", "HONGQI", "FAW Play", "FAW-Play")) {
            prefs.edit().putString("oem_label", old).commit()
            val before = prefs.all.toMap()
            assertEquals("Autunn Play", AirPlayPersistence.loadOemLabel(app))
            assertEquals(before + ("oem_label" to "Autunn Play"), prefs.all)
            assertEquals("Autunn Play", AirPlayPersistence.loadOemLabel(app))
            assertArrayEquals(identity.privateKey, AirPlayPersistence.loadIdentity(app).privateKey)
            assertEquals(identity.pairingId, AirPlayPersistence.loadIdentity(app).pairingId)
            assertArrayEquals(pairing, AirPlayPersistence.loadPairings(app) { _, _ -> }.get("saved-phone"))
        }
    }

    @Test fun customLabelsAreKeptAndBlankLabelsStillHaveAVisibleReturnEntry() {
        AirPlayPersistence.saveOemLabel(app, "My car")
        val before = prefs.all.toMap()
        assertEquals("My car", AirPlayPersistence.loadOemLabel(app))
        assertEquals(before, prefs.all)
        AirPlayPersistence.saveOemLabel(app, "  ")
        assertEquals("Autunn Play", AirPlayPersistence.loadOemLabel(app))
        assertEquals("Autunn Play", AirPlayPersistence.loadManufacturer(app))
        assertEquals("Autunn Play", AirPlayPersistence.loadModel(app))
    }

    @Test fun aSavedPreviousDefaultFallsBackToTheNewIconWithoutDeletingTheFile() {
        val old = javaClass.getResourceAsStream("/legacy-carplay-default-icon.png")!!.use { it.readBytes() }
        AirPlayPersistence.saveCustomAirPlayIcon(app, old)
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(app))
        assertArrayEquals(old, File(app.filesDir, "airplay-icon.png").readBytes())
        val current = app.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        assertFalse(MessageDigest.getInstance("SHA-256").digest(old)
            .contentEquals(MessageDigest.getInstance("SHA-256").digest(current)))
    }

    @Test fun otherCustomIconsArePreservedIncludingOnRepeatedLoads() {
        val custom = byteArrayOf(10, 20, 30, 40)
        AirPlayPersistence.saveCustomAirPlayIcon(app, custom)
        repeat(2) {
            assertArrayEquals(custom, requireNotNull(AirPlayPersistence.loadCustomAirPlayIconFile(app)).readBytes())
        }
    }
}
