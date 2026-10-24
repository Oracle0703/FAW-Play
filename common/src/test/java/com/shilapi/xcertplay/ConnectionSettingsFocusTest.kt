package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionSettingsFocusTest {
    private lateinit var activity: DiPlaySettingsActivity

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        ShadowLog.clear()
        openConnection()
    }

    @Test fun renderedConnectionSettingsRecordFocusAfterPageNormalizationOnlyOnce() {
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        activity.onWindowFocusChanged(false)
        assertTrue(logs().isEmpty())
        assertTrue(activity.intent.hasExtra("disconnected_at_ns"))
        activity.onWindowFocusChanged(true)
        assertEquals(1, logs().size)
        assertFalse(activity.intent.hasExtra("disconnected_at_ns"))
        activity.onWindowFocusChanged(false)
        activity.onWindowFocusChanged(true)
        assertEquals(1, logs().size)
    }

    @Test fun anotherSettingsTabDoesNotConsumeTheConnectionTimingMarker() {
        selectSettingsTab(activity, "关于")
        activity.onWindowFocusChanged(true)
        assertTrue(logs().isEmpty())
        assertTrue(activity.intent.hasExtra("disconnected_at_ns"))
        selectSettingsTab(activity, "连接设置")
        activity.onWindowFocusChanged(true)
        assertEquals(1, logs().size)
    }

    @Test fun homeCannotBeMistakenForTheLastSelectedConnectionTab() {
        settingsButton(activity, "返回首页").performClick()
        activity.onWindowFocusChanged(true)
        assertTrue(logs().isEmpty())
        assertTrue(activity.intent.hasExtra("disconnected_at_ns"))
    }

    @Test fun aNewDisconnectIntentRecordsItsOwnFocus() {
        activity.onWindowFocusChanged(true)
        openConnection()
        activity.onWindowFocusChanged(true)
        assertEquals(2, logs().size)
        assertFalse(activity.intent.hasExtra("disconnected_at_ns"))
    }

    @Test fun ordinaryConnectionSettingsDoNotCreateADisconnectMeasurement() {
        deliver(Intent().putExtra("page", "connection"))
        activity.onWindowFocusChanged(true)
        assertTrue(logs().isEmpty())
    }

    private fun openConnection() {
        deliver(Intent().putExtra("page", "connection")
            .putExtra("disconnected_at_ns", System.nanoTime()))
    }

    private fun deliver(intent: Intent) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent", from(Intent::class.java, intent))
    }

    private fun logs() = ShadowLog.getLogsForTag("DiPlaySetup")
        .filter { it.msg.startsWith("CONNECTION_DIAGNOSTIC settings focused ") }
}
