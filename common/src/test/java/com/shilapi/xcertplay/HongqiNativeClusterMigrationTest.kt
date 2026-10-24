package com.shilapi.xcertplay

import android.os.Build
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiNativeClusterMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        prefs.edit().clear().commit()
        AirPlayPersistence.overlaySettingsListener = null
    }

    @After fun clear() { AirPlayPersistence.overlaySettingsListener = null }

    @Test fun newHs5InstallationStartsWithTheNativeInstrumentStream() {
        assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
        assertEquals(Content.INSTRUMENTS.name, prefs.getString("cluster_content", null))
        assertFalse(prefs.contains("cluster_map_enabled"))
    }

    @Test fun savedLegacyModesAreMigratedOnUpgrade() {
        for (legacy in listOf(Content.MAP, Content.MAP_WITH_CUSTOM_CARD, Content.TURN_CARD)) {
            prefs.edit().clear().putString("cluster_content", legacy.name).commit()
            assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
            assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
        }
    }

    @Test fun explicitChoicesBeforeOrAfterTheFirstLoadAreNotOverridden() {
        for (content in Content.entries) {
            prefs.edit().clear().commit()
            AirPlayPersistence.saveClusterContent(app, content)
            assertEquals(content, AirPlayPersistence.loadClusterContent(app))
            AirPlayPersistence.saveClusterContent(app, Content.INSTRUMENTS)
            AirPlayPersistence.saveClusterContent(app, content)
            assertEquals(content, AirPlayPersistence.loadClusterContent(app))
        }
    }

    @Test fun migrationPreservesPairingCalibrationDisabledMapAndLicenseStorage() {
        prefs.edit().putString("cluster_content", Content.MAP_WITH_CUSTOM_CARD.name)
            .putString("identity_private", "test-identity")
            .putStringSet("pairing_ids", setOf("test-phone"))
            .putInt("cluster_marker_horizontal_step", 1)
            .putInt("cluster_map_scale_percent", 67)
            .putBoolean("cluster_map_enabled", false).commit()
        val before = prefs.all.filterKeys { it != "cluster_content" }
        val license = File(app.noBackupFilesDir, "product-license-v1.json")
        license.parentFile!!.mkdirs()
        license.writeText("test-existing-license")
        try {
            assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
            assertEquals(before, prefs.all.filterKeys {
                it != "cluster_content" && it != "hongqi_native_cluster_v1"
            })
            assertEquals("test-existing-license", license.readText())
            assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        } finally { license.delete() }
    }

    @Test fun otherDevicesKeepTheirSavedModeAndPureMapDefault() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        assertEquals(Content.MAP, AirPlayPersistence.loadClusterContent(app))
        prefs.edit().putString("cluster_content", Content.MAP_WITH_CUSTOM_CARD.name).commit()
        assertEquals(Content.MAP_WITH_CUSTOM_CARD, AirPlayPersistence.loadClusterContent(app))
        assertFalse(prefs.contains("hongqi_native_cluster_v1"))
    }

    @Test @Config(sdk = [33]) fun unverifiedAndroidVersionsAreNotMigratedByModelNameAlone() {
        prefs.edit().putString("cluster_content", Content.MAP_WITH_CUSTOM_CARD.name).commit()
        assertEquals(Content.MAP_WITH_CUSTOM_CARD, AirPlayPersistence.loadClusterContent(app))
        assertFalse(prefs.contains("hongqi_native_cluster_v1"))
    }
}
