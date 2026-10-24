package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.After
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
    private lateinit var activity: DiPlaySettingsActivity
    private lateinit var stateIoScope: SessionStateIoTestScope

    @Before fun setup() {
        stateIoScope = SessionStateIoTestScope()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayBackgroundSession.requestConnection(context)
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        activity.intent = launcher()
        assertNull("Fresh automatic-entry fixture must finish prior cleanup: " + sessionState(),
            CarPlayBackgroundSession.startBlockReason())
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        stateIoScope.close()
    }

    @Test fun firstRunKeepsPairingManual() {
        assertTrue(DiPlayPreferences.autoConnect(activity))
        attempt()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun savedPhoneStartsBackgroundConnectionAndWaitsForAPrimaryBeforeOpeningProjection() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        attempt()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(DiPlaySessionService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun disabledAutomaticConnectionIsRespectedWhenOpeningTheApp() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        activity.getSharedPreferences("diplay", 0).edit().putBoolean("auto_connect", false).commit()
        attempt()
        assertFalse(DiPlayPreferences.autoConnect(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun explicitHomeFromCarPlayDoesNotReopenProjection() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        activity.intent = Intent().putExtra("page", "home").putExtra(EXPLICIT_SOFTWARE_PAGE, true)
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

    @Test fun manualDisconnectBlocksAutomaticConnectionWhenTheLauncherReopens() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        CarPlayBackgroundSession.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())

        prepare(launcher())
        attempt()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
    }

    @Test fun explicitConnectClearsTheManualDisconnectLatch() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        CarPlayBackgroundSession.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())

        DiPlaySettingsActivity::class.java.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun explicitOpenAlsoRequestsConnectionInsteadOfKeepingTheManualLatch() {
        CarPlayBackgroundSession.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())

        DiPlaySettingsActivity::class.java.getDeclaredMethod("openProjection")
            .apply { isAccessible = true }.invoke(activity)

        assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private fun attempt() = DiPlaySettingsActivity::class.java.getDeclaredMethod("autoConnectOnOpen")
        .apply { isAccessible = true }.invoke(activity)
    private fun prepare(intent: Intent) = DiPlaySettingsActivity::class.java
        .getDeclaredMethod("prepareAutoConnectLaunch", Intent::class.java)
        .apply { isAccessible = true }.invoke(activity, intent)
    private fun initialLaunch() = DiPlaySettingsActivity::class.java.getDeclaredField("initialLaunch")
        .apply { isAccessible = true }.getBoolean(activity)
    private fun setInitialLaunch(value: Boolean) = DiPlaySettingsActivity::class.java.getDeclaredField("initialLaunch")
        .apply { isAccessible = true }.setBoolean(activity, value)

    private fun sessionState(): String = listOf("desired", "manualDisconnect", "terminated", "stopping",
        "starting", "intentRevision", "durableIntentRevision", "stopWaiters", "retiring").joinToString { name ->
        val value = org.robolectric.util.ReflectionHelpers.getStaticField<Any?>(CarPlayBackgroundSession::class.java, name)
        "$name=$value"
    }
}
