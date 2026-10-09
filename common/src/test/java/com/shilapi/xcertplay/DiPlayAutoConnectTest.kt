package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiPlayAutoConnectTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.intent = launcher()
    }

    @Test fun firstRunKeepsPairingManual() {
        assertTrue(DiPlayPreferences.autoConnect(activity))
        attempt()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun savedPhoneStartsProjectionWithoutAConnectClick() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        attempt()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun legacyDisabledPreferenceCannotStopAutoConnection() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        activity.getSharedPreferences("diplay", 0).edit().putBoolean("auto_connect", false).commit()
        attempt()
        assertTrue(DiPlayPreferences.autoConnect(activity))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun explicitHomeFromCarPlayDoesNotReopenProjection() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        activity.intent = Intent().putExtra("page", "home")
        attempt()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun warmLauncherRearmsOnlyForTheLauncherEntry() {
        setInitialLaunch(false)
        prepare(launcher())
        assertTrue(initialLaunch())
        prepare(Intent()) // Factory launcher entry using only the activity component.
        assertTrue(initialLaunch())
        prepare(launcher().putExtra("page", "settings"))
        assertFalse(initialLaunch())
        prepare(Intent().putExtra("page", "wireless-recovery"))
        assertFalse(initialLaunch())
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private fun attempt() = DiPlayActivity::class.java.getDeclaredMethod("autoConnectOnOpen")
        .apply { isAccessible = true }.invoke(activity)
    private fun prepare(intent: Intent) = DiPlayActivity::class.java
        .getDeclaredMethod("prepareAutoConnectLaunch", Intent::class.java)
        .apply { isAccessible = true }.invoke(activity, intent)
    private fun initialLaunch() = DiPlayActivity::class.java.getDeclaredField("initialLaunch")
        .apply { isAccessible = true }.getBoolean(activity)
    private fun setInitialLaunch(value: Boolean) = DiPlayActivity::class.java.getDeclaredField("initialLaunch")
        .apply { isAccessible = true }.setBoolean(activity, value)
}
