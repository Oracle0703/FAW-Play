package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AutunnSystemParametersTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        CarPlayPicture.preferences(context).edit().clear().commit()
    }

    @Test fun legacyResolutionAndUiScaleCannotChangeTheNativeCanvas() {
        for (percent in listOf(30, 73, 100, 157, 160)) {
            AirPlayPersistence.saveDisplayScalePercent(context, percent)
            AirPlayPersistence.saveDisplayScaleTenths(context, percent / 10)
            AirPlayPersistence.saveUiScalePercent(context, percent)
            assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
            assertEquals(10, AirPlayPersistence.loadDisplayScaleTenths(context))
            assertEquals(100, AirPlayPersistence.loadUiScalePercent(context))
        }
    }

    @Test fun legacyNightModeThresholdAndDelayCannotOverrideAmbientPolicy() {
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(context, mode)
            prefs.edit().putInt("ambient_lux_threshold", 200)
                .putInt("ambient_delay_seconds", 5).commit()
            assertEquals(CarPlayNightMode.AMBIENT, AirPlayPersistence.loadCarPlayNightMode(context))
            assertEquals(30, AirPlayPersistence.loadAmbientLightThreshold(context).lux)
            assertEquals(0, AirPlayPersistence.loadAmbientDelaySeconds(context))
        }
    }

    @Test fun legacyCodecBufferAndBarChoicesCannotOverrideHs5Policy() {
        prefs.edit().putBoolean("hevc_enabled", true).putBoolean("hevc_software_decoder", true)
            .putInt("media_buffer_ms", 20).putBoolean("hide_top_bar", false)
            .putBoolean("hide_bottom_bar", false).putBoolean("adapt_pip_resolution", false)
            .putBoolean("right_hand_drive", true).commit()
        assertFalse(AirPlayPersistence.loadHevcEnabled(context))
        assertFalse(AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context))
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertTrue(AirPlayPersistence.loadAdaptPipResolution(context))
        assertFalse(AirPlayPersistence.loadRightHandDrive(context))
    }

    @Test fun fixedPolicyRepairsLegacyPreferencesWithoutClearingPairingOrAudio() {
        prefs.edit().putInt("display_scale_percent", 160).putBoolean("hide_top_bar", false)
            .putString("device_id", "02:00:00:00:00:03").putString("pairing_sentinel", "keep")
            .putInt("navigation_stream_type", 5).commit()
        context.getSharedPreferences("diplay", 0).edit().putBoolean("auto_connect", false)
            .putString("phone_address", "02:00:00:00:00:02").putString("phone_name", "Test iPhone").commit()
        CarPlayPicture.preferences(context).edit().putInt("brightness", 50).putInt("contrast", 180).commit()

        AutunnSystemParameters.enforce(context)
        val repaired = prefs.all.toMap()
        AutunnSystemParameters.enforce(context)

        assertEquals(repaired, prefs.all)
        assertEquals(100, prefs.getInt("display_scale_percent", 0))
        assertTrue(prefs.getBoolean("hide_top_bar", false))
        assertEquals("keep", prefs.getString("pairing_sentinel", null))
        assertEquals("02:00:00:00:00:03", prefs.getString("device_id", null))
        assertEquals(5, prefs.getInt("navigation_stream_type", 0))
        assertEquals("02:00:00:00:00:02", DiPlayPreferences.phoneAddress(context))
        assertFalse(context.getSharedPreferences("diplay", 0).getBoolean("auto_connect", true))
        assertEquals(0, CarPlayPicture.preferences(context).getInt("brightness", -1))
        assertEquals(100, CarPlayPicture.preferences(context).getInt("contrast", -1))
    }

    @Test fun frameRateRemainsSelectableAndSurvivesFixedPolicy() {
        for (fps in listOf(30, 50, 60)) {
            AutunnSystemParameters.saveFps(context, fps)
            AutunnSystemParameters.enforce(context)
            assertEquals(fps, AutunnSystemParameters.fps(context))
        }
        for (fps in listOf(-1, 0, 24, 45, 120)) {
            AutunnSystemParameters.saveFps(context, fps)
            assertEquals(30, AutunnSystemParameters.fps(context))
        }
    }

    @Test @Config(sdk = [28]) fun hs5LocationDefaultDoesNotOverrideAnExplicitOptOut() {
        val oldModel = Build.MODEL
        val oldDevice = Build.DEVICE
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
            ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
            AutunnSystemParameters.enforce(context)
            assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
            AirPlayPersistence.saveLocationReportingEnabled(context, false)
            AutunnSystemParameters.enforce(context)
            assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", oldModel)
            ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", oldDevice)
        }
    }

    @Test fun bootLaunchAndAutomaticConnectionRemainIndependentSettings() {
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(context))
        for (enabled in listOf(false, true)) {
            AirPlayPersistence.saveAutoStartOnBoot(context, enabled)
            for (auto in listOf(false, true)) {
                DiPlayPreferences.saveAutoConnect(context, auto)
                AutunnSystemParameters.enforce(context)
                assertEquals(enabled, AirPlayPersistence.loadAutoStartOnBoot(context))
                assertEquals(auto, DiPlayPreferences.autoConnect(context))
            }
        }
    }
}
