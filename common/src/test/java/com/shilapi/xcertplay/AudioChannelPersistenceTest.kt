package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioChannelPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, false)
    }

    @Test fun freshInstallUsesAutomaticRouting() {
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun automotiveDefaultsToFocusForFactoryVolumeControls() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true)
        assertEquals(true, AirPlayPersistence.loadAudioFocusEnabled(context))
    }

    @Test fun phoneKeepsTheExistingFocusDefault() {
        assertEquals(false, AirPlayPersistence.loadAudioFocusEnabled(context))
    }

    @Test fun savedFocusChoiceIsPreservedOnBothDeviceTypes() {
        for (automotive in listOf(false, true)) {
            shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, automotive)
            for (enabled in listOf(false, true)) {
                AirPlayPersistence.saveAudioFocusEnabled(context, enabled)
                assertEquals(enabled, AirPlayPersistence.loadAudioFocusEnabled(context))
            }
        }
    }

    @Test fun legacyNavigationChannelIsInheritedWithoutChangingMedia() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
    }

    @Test fun explicitNavigationChannelTakesPrecedenceOverLegacySetting() {
        AirPlayPersistence.saveNavigationStreamType(context, 14)
        AirPlayPersistence.saveNavigationAudioChannel(context, 15)
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun explicitAutomaticRoutingTakesPrecedenceOverLegacySetting() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun extendedChannelsCanBeSavedForMediaAndNavigation() {
        for (channel in listOf(11, 14, 15, 20)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(channel, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(channel, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun existingExtendedChannelsRemainAvailable() {
        prefs.edit().putInt("media_audio_channel", 15).putInt("navigation_audio_channel", 15).apply()
        assertEquals(15, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun invalidSavedChannelsUseAutomaticRouting() {
        for (channel in listOf(-1, 21)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun invalidStoredChannelsUseAutomaticRouting() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        for (channel in listOf(-1, 21)) {
            prefs.edit().putInt("media_audio_channel", channel).putInt("navigation_audio_channel", channel).apply()
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun invalidLegacyChannelUsesAutomaticRouting() {
        AirPlayPersistence.saveNavigationStreamType(context, 21)
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }
}
