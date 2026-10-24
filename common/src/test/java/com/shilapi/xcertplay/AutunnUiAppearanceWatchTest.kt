package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutunnUiAppearanceWatchTest {
    private lateinit var activity: Activity

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).get()
        AutunnUiTheme.preferences(activity).edit().clear().apply()
        setSystemMode(Configuration.UI_MODE_NIGHT_NO)
    }

    @Test fun actualVehicleSignalOverridesAndroidAndOnlyColorChangesNotify() {
        val changes = mutableListOf<Boolean>()
        val watch = AutunnUiAppearanceWatch(activity, Runnable { changes += AutunnUiTheme.isDark(activity) })
        try {
            shadowOf(Looper.getMainLooper()).idle()
            watch.vehicleChanged(true)
            assertTrue(AutunnUiTheme.isDark(activity))
            watch.vehicleChanged(true)
            assertEquals(listOf(true), changes)
            watch.vehicleChanged(false)
            assertFalse(AutunnUiTheme.isDark(activity))
            assertEquals(listOf(true, false), changes)
            setSystemMode(Configuration.UI_MODE_NIGHT_YES)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertFalse(AutunnUiTheme.isDark(activity))
            assertEquals(listOf(true, false), changes)
            watch.vehicleChanged(null)
            assertTrue(AutunnUiTheme.isDark(activity))
            assertEquals(listOf(true, false, true), changes)
        } finally {
            watch.close()
        }
    }

    @Test fun uiModePollingWorksWithoutFirmwareConfigurationCallbacks() {
        val changes = mutableListOf<Boolean>()
        val watch = AutunnUiAppearanceWatch(activity, Runnable { changes += AutunnUiTheme.isDark(activity) })
        try {
            shadowOf(Looper.getMainLooper()).idle()
            setSystemMode(Configuration.UI_MODE_NIGHT_YES)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertEquals(listOf(true), changes)
            setSystemMode(Configuration.UI_MODE_NIGHT_UNDEFINED)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertEquals(listOf(true), changes)
            assertTrue(AutunnUiTheme.isDark(activity))
            setSystemMode(Configuration.UI_MODE_NIGHT_NO)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertEquals(listOf(true, false), changes)
        } finally {
            watch.close()
        }
    }

    @Test fun manualOverridesIgnoreVehicleAndSystemEventsUntilAutomaticIsSelected() {
        val changes = mutableListOf<Boolean>()
        val watch = AutunnUiAppearanceWatch(activity, Runnable { changes += AutunnUiTheme.isDark(activity) })
        try {
            shadowOf(Looper.getMainLooper()).idle()
            AutunnUiTheme.setMode(activity, "dark")
            shadowOf(Looper.getMainLooper()).idle()
            watch.vehicleChanged(false)
            setSystemMode(Configuration.UI_MODE_NIGHT_NO)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertTrue(AutunnUiTheme.isDark(activity))
            assertEquals(listOf(true), changes)
            AutunnUiTheme.setMode(activity, "system")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AutunnUiTheme.isDark(activity))
            assertEquals(listOf(true, false), changes)
        } finally {
            watch.close()
        }
    }

    @Test fun closeStopsEventsAndReleasesTheLiveVehicleOverride() {
        val changes = mutableListOf<Boolean>()
        val watch = AutunnUiAppearanceWatch(activity, Runnable { changes += AutunnUiTheme.isDark(activity) })
        shadowOf(Looper.getMainLooper()).idle()
        watch.vehicleChanged(true)
        watch.close()
        watch.close()
        assertFalse(AutunnUiTheme.isDark(activity))
        watch.vehicleChanged(false)
        AutunnUiTheme.setMode(activity, "dark")
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertEquals(listOf(true), changes)
    }

    private fun setSystemMode(nightMode: Int) {
        // Configuration merging treats undefined as an inherited value; report it directly.
        activity.resources.configuration.uiMode = Configuration.UI_MODE_TYPE_CAR or nightMode
        assertEquals(nightMode, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }
}
