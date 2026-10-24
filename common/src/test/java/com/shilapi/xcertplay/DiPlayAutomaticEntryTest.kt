package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.ParcelUuid
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayAutomaticEntryTest {
    private lateinit var lifecycle: ActivityController<DiPlaySettingsActivity>
    private lateinit var activity: DiPlaySettingsActivity
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var stateIoScope: SessionStateIoTestScope
    private var stops = 0

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.requestConnection(context)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
        context.getSystemService(WifiManager::class.java).setWifiEnabled(true)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        lifecycle = Robolectric.buildActivity(DiPlaySettingsActivity::class.java)
        activity = lifecycle.get()
        activity.intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        field("initialLaunch").setBoolean(activity, false)
        lifecycle.create().start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {
            stops++
            it()
        }
        assertNull("Fresh UI fixture must have completed its previous stop: " + sessionState(),
            CarPlayBackgroundSession.startBlockReason())
    }

    private fun readyPrimary() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
    }

    @After fun tearDown() {
        try {
            CarPlayBackgroundSession.clear()
            lifecycle.pause().stop().destroy()
            CarPlayBackgroundSession.terminate()
            shadowOf(Looper.getMainLooper()).idle()
        } finally { stateIoScope.close() }
    }

    @Test fun aReadyPrimaryScreenOpensTheExistingHostExactlyOnce() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        checkEntry()
        assertNull(shadowOf(activity).nextStartedActivity)
        readyPrimary()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, opened.component?.className)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
        assertFalse(activity.isFinishing)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun fallbackPhoneChooserUsesIap2CapabilitiesAndRefreshesOnSdpCompletion() {
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val supported = ShadowBluetoothDevice.newInstance("10:00:00:00:00:11")
        val unknown = ShadowBluetoothDevice.newInstance("10:00:00:00:00:12")
        val other = ShadowBluetoothDevice.newInstance("10:00:00:00:00:13")
        shadowOf(supported).setName("已确认的手机")
        shadowOf(unknown).setName("改名的手机")
        shadowOf(other).setName("其他设备")
        val iap2 = ParcelUuid.fromString(com.shilapi.xcertplay.transport.IphoneBluetoothDevices.IAP2_SERVICE_UUID)
        shadowOf(supported).setUuids(arrayOf(iap2))
        shadowOf(other).setUuids(arrayOf(ParcelUuid.fromString("0000111e-0000-1000-8000-00805f9b34fb")))
        shadowOf(adapter).setBondedDevices(setOf(supported, unknown, other))
        activity.javaClass.getDeclaredMethod("choosePhone").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle() // Attach the dialog before SDP completes.
        val first = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(listOf("已确认的手机"), shadowOf(first).items.map(CharSequence::toString))
        shadowOf(unknown).setUuids(arrayOf(iap2))
        activity.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID))
        shadowOf(Looper.getMainLooper()).idle()
        val refreshed = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(first.isShowing)
        assertEquals(setOf("已确认的手机", "改名的手机"), shadowOf(refreshed).items.map(CharSequence::toString).toSet())
        refreshed.dismiss()
    }

    @Test fun automaticConnectionResumesInTheBackgroundWhileTheHomeRemainsVisible() {
        val services = shadowOf(RuntimeEnvironment.getApplication())
        services.clearStartedServices()
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Previously paired iPhone")
        val home = activity.window.decorView.findViewWithTag<View>("autunn_home_root")
        CarPlayBackgroundSession.requestAutomaticEntry()

        automaticConnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, services.allStartedServices.size)
        assertEquals(DiPlaySessionService::class.java.name, services.nextStartedService?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(home, activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertFalse(activity.isFinishing)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test @Config(shadows = [RejectingServiceLaunch::class])
    fun rejectedAutomaticServiceStartupKeepsHomeInsteadOfOpeningHostForPreparation() {
        val launch = Shadow.extract<RejectingServiceLaunch>(activity.baseContext)
        launch.rejectService = true
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Previously paired iPhone")
        val home = activity.window.decorView.findViewWithTag<View>("autunn_home_root")
        CarPlayBackgroundSession.requestAutomaticEntry()

        automaticConnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, launch.serviceAttempts)
        assertEquals(DiPlaySessionService::class.java.name, launch.lastService?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(home, activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertFalse(activity.isFinishing)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        verify(controller, never()).close()
    }

    @Test fun anAutomaticConnectionWithAnAcceptedPrimaryCanImmediatelyOpenTheExistingHost() {
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Previously paired iPhone")
        readyPrimary()
        CarPlayBackgroundSession.requestAutomaticEntry()

        automaticConnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(activity.isFinishing)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun aLauncherWithAConnectedPhoneRestoresTheHostBeforeDrawingHome() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        assertNull("A primary TEARDOWN must not prevent manual restoration", controller.activeAirPlaySessionToken())
        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
        val other = Robolectric.buildActivity(DiPlaySettingsActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        other.create()
        try {
            val target = other.get()
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(target).nextStartedActivity.component?.className)
            assertNull(target.window.decorView.findViewWithTag<View>("autunn_home_root"))
            assertTrue(target.isFinishing)
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
            assertEquals(0, stops)
            verify(controller, never()).start()
            verify(controller, never()).close()
        } finally { other.destroy() }
    }

    @Test fun aReusedFactoryLauncherRestoresThePhoneWithoutAutomaticConnectionOrRepaintingHome() {
        readyPrimary()
        DiPlayPreferences.saveAutoConnect(activity, false)
        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
        val home = activity.window.decorView.findViewWithTag<View>("autunn_home_root")
        // The factory launcher can use only an explicit component, without MAIN/LAUNCHER.
        deliver(Intent(activity, DiPlaySettingsActivity::class.java))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertSame(home, activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertTrue(activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun aFactoryLauncherHomePageExtraStillRestoresThePhoneWithoutDrawingSoftwareHome() {
        readyPrimary()
        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).putExtra("page", "home"))

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertTrue(activity.isFinishing)
        assertEquals(0, stops)
        verify(controller, never()).close()
    }

    @Test fun restoringTheHomeTaskOnResumeAloneRoutesBeforeItsResumeRefresh() {
        lifecycle.pause().stop()
        readyPrimary()
        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
        // Task restoration need not call onNewIntent. The old home must not be resumed first.
        lifecycle.start().resume()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun explicitPagesRemainAccessibleWithAConnectedPhone() {
        readyPrimary()
        for (page in listOf("home", "settings", "connection", "about", "authorization", "diagnostics")) {
            deliver(Intent().putExtra("page", page).apply {
                if (page == "home") putExtra(EXPLICIT_SOFTWARE_PAGE, true)
            })
            assertFalse("page=$page", activity.isFinishing)
            assertNull("page=$page", shadowOf(activity).nextStartedActivity)
        }
        assertEquals(0, stops)
    }

    @Test fun aLauncherWithoutACurrentPhoneKeepsTheNormalHomePage() {
        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        assertFalse(activity.isFinishing)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertNull(shadowOf(activity).nextStartedActivity)
        verify(controller, never()).start()
    }

    @Test fun aLauncherCannotRestoreAStoppedOrClosingUseEvenWithAStalePhoneToken() {
        readyPrimary()
        for (name in listOf("stopping", "ignitionOff", "manualDisconnect", "terminated")) {
            val flag = CarPlayBackgroundSession::class.java.getDeclaredField(name).apply { isAccessible = true }
            val previous = flag.getBoolean(CarPlayBackgroundSession)
            flag.setBoolean(CarPlayBackgroundSession, true)
            try {
                deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
                assertFalse("flag=$name", activity.isFinishing)
                assertNull("flag=$name", shadowOf(activity).nextStartedActivity)
                assertEquals(0, stops)
            } finally { flag.setBoolean(CarPlayBackgroundSession, previous) }
        }
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun anOutstandingRetirementKeepsTheLauncherOutOfTheCurrentHost() {
        readyPrimary()
        val type = CarPlayBackgroundSession::class.java.declaredClasses.single { it.simpleName == "Retirement" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val retirement = constructor.newInstance(null, null, "test-retirement")
        @Suppress("UNCHECKED_CAST")
        val retiring = CarPlayBackgroundSession::class.java.getDeclaredField("retiring")
            .apply { isAccessible = true }.get(CarPlayBackgroundSession) as MutableList<Any>
        retiring.add(retirement)
        try {
            deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { retiring.remove(retirement) }
    }

    @Test fun aLauncherIgnoresItsSavedSettingsPageAndRestoresTheConnectedPhone() {
        readyPrimary()
        val other = Robolectric.buildActivity(DiPlaySettingsActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        other.create(android.os.Bundle().apply { putString("page", "settings") })
        try {
            assertTrue(other.get().isFinishing)
            assertNull(other.get().window.decorView.findViewWithTag<View>("autunn_settings_root"))
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(other.get()).nextStartedActivity?.component?.className)
            assertSessionUntouched()
        } finally { other.destroy() }
    }

    @Test @Config(shadows = [RejectingActivityLaunch::class])
    fun aRejectedDirectLauncherReturnFallsBackToHomeWithoutClosingThePhone() {
        val launch = Shadow.extract<RejectingActivityLaunch>(activity)
        launch.rejectHost = true
        readyPrimary()
        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(launch.hostAttempts >= 1)
        val attempts = launch.hostAttempts
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("A rejected direct return must not spin while the software fallback is visible",
            attempts, launch.hostAttempts)
        assertFalse(activity.isFinishing)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        verify(controller, never()).close()
    }

    @Test fun theBackgroundDispatcherStillWaitsForAPrimaryWhenNoVisibleHomeObserverOwnsEntry() {
        CarPlayBackgroundSession.stopAutomaticEntryWatch(activity)
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.requestAutomaticEntry()
        checkEntry()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun confirmedIgnitionOffCannotReopenOrRestartTheSession() {
        val off = CarPlayBackgroundSession::class.java.getDeclaredField("ignitionOff").apply { isAccessible = true }
        CarPlayBackgroundSession.requestAutomaticEntry()
        readyPrimary()
        off.setBoolean(CarPlayBackgroundSession, true)
        try {
            checkEntry()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, stops)
            verify(controller, never()).start()
        } finally {
            off.setBoolean(CarPlayBackgroundSession, false)
        }
    }

    @Test fun anotherWindowKeepsTheHostInTheBackgroundUntilHomeRegainsFocus() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        checkEntry()
        activity.onWindowFocusChanged(false)
        readyPrimary()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        checkEntry()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())

        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun aPausedHomeCannotOpenCarPlayEvenWithAStaleFocusCallback() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        checkEntry()
        lifecycle.pause()
        activity.onWindowFocusChanged(true)
        readyPrimary()
        checkEntry()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNull(shadowOf(activity).nextStartedActivity)
        lifecycle.resume()
    }

    @Test fun explicitHomeAndSettingsCancelPendingAutomaticEntry() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        deliver(Intent().putExtra("page", "home").putExtra(EXPLICIT_SOFTWARE_PAGE, true))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())

        CarPlayBackgroundSession.requestAutomaticEntry()
        deliver(Intent().putExtra("page", "connection"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        readyPrimary()
        checkEntry()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anExplicitHomeIntentAlsoCancelsWhenTheHomeActivityIsNew() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        val other = Robolectric.buildActivity(DiPlaySettingsActivity::class.java,
            Intent().putExtra("page", "home").putExtra(EXPLICIT_SOFTWARE_PAGE, true))
        other.create()
        try {
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        } finally {
            other.destroy()
        }
    }

    @Test fun backAndLeavingForTheCarUiCancelAutomaticEntry() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        CarPlayBackgroundSession.requestAutomaticEntry()
        activity.javaClass.getDeclaredMethod("onUserLeaveHint").apply { isAccessible = true }.invoke(activity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun aPhoneRequestForTheCarUiCancelsAutomaticEntry() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val session = mock(AirPlaySession::class.java)
        host.javaClass.getDeclaredField("activeAirPlaySession").apply { isAccessible = true }.set(host, session)
        val listener = host.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(host, 0) as AirPlaySessionListener
        CarPlayBackgroundSession.requestAutomaticEntry()
        listener.onHostUiRequested(session)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host)
                as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test fun reopeningTheLauncherAfterTaskRemovalStartsAutomaticConnectionWithoutTheButton() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Previously paired iPhone")
        val services = shadowOf(RuntimeEnvironment.getApplication())
        services.clearStartedServices()

        queueLauncherAutoConnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(services.allStartedServices.any { it.component?.className == DiPlaySessionService::class.java.name })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anExplicitSettingsPageCannotReopenATaskTerminatedUse() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Previously paired iPhone")
        lifecycle.pause()
        deliver(Intent().putExtra("page", "settings"))
        lifecycle.resume()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun queuedAutoConnectCannotUndoAUserLeaveCancellation() {
        queueLauncherAutoConnect()
        activity.javaClass.getDeclaredMethod("onUserLeaveHint").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pausingDiscardsTheQueuedLaunchButANewLauncherEntryStillConnects() {
        queueLauncherAutoConnect()
        lifecycle.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)

        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        readyPrimary()
        lifecycle.resume()
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertEquals(0, stops)
    }

    @Test fun anExplicitPageDiscardsAQueuedAutomaticLaunch() {
        queueLauncherAutoConnect()
        deliver(Intent().putExtra("page", "home").putExtra(EXPLICIT_SOFTWARE_PAGE, true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test @Config(shadows = [RejectingActivityLaunch::class])
    fun aRejectedLaunchRetriesWithoutAnotherFocusEventAndRetainsTheCurrentConnection() {
        // Removing the visible observer transfers entry ownership to the background requester
        // (null). Use that requester's real dispatcher instead of the Settings-owned instance.
        CarPlayBackgroundSession.stopAutomaticEntryWatch(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val dispatcher = AutomaticEntryDispatcher(activity)
        val launch = Shadow.extract<RejectingActivityLaunch>(activity)
        launch.rejectHost = true
        try {
            CarPlayBackgroundSession.requestAutomaticEntry()
            readyPrimary()
            dispatcher.dispatch()
            assertEquals(1, launch.hostAttempts)
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            assertTrue(CarPlayBackgroundSession.backgroundEntryFollowUp()?.retryWait == true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertEquals(1, launch.hostAttempts)
            assertNull(shadowOf(activity).nextStartedActivity)
            launch.rejectHost = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(2, launch.hostAttempts)
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            assertSessionUntouched()
        } finally {
            dispatcher.cancel()
        }
    }

    @Test fun anInternalConnectionRecoveryRetainsIntentUntilTheHostIsActuallyVisible() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        deliver(Intent().putExtra("page", "connection").putExtra("internal_connection_recovery", true))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        readyPrimary()
        checkEntry()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        checkEntry()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aReturnRequestedAfterTheRecoveryPageAlreadyFocusedStillOpensWhenThePrimaryArrives() {
        CarPlayBackgroundSession.cancelAutomaticEntry("previous-entry-completed")
        deliver(Intent().putExtra("page", "connection").putExtra("internal_connection_recovery", true))
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNull(shadowOf(activity).nextStartedActivity)

        CarPlayBackgroundSession.requestAutomaticEntry()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNull(shadowOf(activity).nextStartedActivity)
        readyPrimary()
        val phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        val listener = CarPlayBackgroundSession.javaClass.getDeclaredMethod("backgroundListener", CarPlayController::class.java)
            .apply { isAccessible = true }.invoke(CarPlayBackgroundSession, controller) as AirPlaySessionListener
        listener.onSessionActive(phone)
        listener.onMainScreenReady(phone)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(activity.isFinishing)
        assertEquals(0, stops)
    }

    @Test fun queuedEntryStateChangesCannotReopenAfterTheUserLeavesTheRecoveryPage() {
        deliver(Intent().putExtra("page", "connection").putExtra("internal_connection_recovery", true))
        CarPlayBackgroundSession.requestAutomaticEntry()
        readyPrimary()
        activity.javaClass.getDeclaredMethod("onUserLeaveHint").apply { isAccessible = true }.invoke(activity)
        lifecycle.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)
        lifecycle.resume()
    }

    @Test fun aUserSettingsTabOrHomeClickCancelsInternalRecoveryEntry() {
        for (label in listOf("画面显示", "返回首页")) {
            `when`(controller.currentActiveAirPlaySession()).thenReturn(null)
            `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
            CarPlayBackgroundSession.active = false
            CarPlayBackgroundSession.requestAutomaticEntry()
            deliver(Intent().putExtra("page", "connection").putExtra("internal_connection_recovery", true))
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }
                .performClick()
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            readyPrimary()
            checkEntry()
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun aCanceledQueuedAutomaticIntentCannotRestoreABackgroundHome() {
        CarPlayBackgroundSession.cancelAutomaticEntry("user-left-home")
        lifecycle.pause().stop()
        deliver(Intent().putExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, Long.MAX_VALUE))
        lifecycle.start().resume()
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertFalse(field("activityResumed").getBoolean(activity))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aStaleAutomaticIntentCannotDismissTheUsersAlreadyForegroundSettings() {
        deliver(Intent().putExtra("page", "settings"))
        deliver(Intent().putExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, Long.MAX_VALUE))
        assertFalse(field("rejectAutomaticResume").getBoolean(activity))
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        assertEquals("settings", field("page").get(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun androidsPauseForNewIntentMustNotDismissTheAlreadyForegroundSettings() {
        deliver(Intent().putExtra("page", "settings"))
        // Android pauses even a foreground singleTop before delivering a new intent.
        lifecycle.pause()
        deliver(Intent().putExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, Long.MAX_VALUE))
        lifecycle.resume()
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        assertTrue(field("activityResumed").getBoolean(activity))
        assertEquals("settings", field("page").get(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }


    @Test fun aConnectedHomeHandsOffWhenTheSessionActivatesBeforeItsPrimaryStream() {
        CarPlayBackgroundSession.requestAutomaticEntry()
        val phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        assertNull(controller.activeAirPlaySessionToken())
        assertFalse(phone.hasRenderedMainScreen())
        backgroundListener().onSessionActive(phone)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSessionUntouched()
    }

    @Test fun aLauncherCanRestoreAnAcceptedPrimaryBeforeTheSessionActivationCallback() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        assertNull(controller.currentActiveAirPlaySession())
        assertFalse(CarPlayBackgroundSession.active)

        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertSessionUntouched()
    }

    @Test fun aPreviousDisconnectedFallbackCannotKeepTheSoftwareHomeOverANowConnectedPhone() {
        deliver(Intent().putExtra("page", "home").putExtra(LAUNCHER_SOFTWARE_FALLBACK, true))
        assertFalse(activity.isFinishing)
        val phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)

        backgroundListener().onSessionActive(phone)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertSessionUntouched()
    }

    @Test fun configurationRefreshCannotRepaintTheHomeAfterConnectionCompletes() {
        val home = activity.window.decorView.findViewWithTag<View>("autunn_home_root")
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))

        activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertSame(home, activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertSessionUntouched()
    }

    @Test fun aConnectedRecoveryPageHandsOffWithoutWaitingForItsPrimaryStream() {
        deliver(Intent().putExtra("page", "connection").putExtra("internal_connection_recovery", true))
        val phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        assertNull(controller.activeAirPlaySessionToken())

        backgroundListener().onSessionActive(phone)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertSessionUntouched()
    }

    @Test fun leavingHomePreventsAQueuedSessionActivationFromReturningOverTheVehicleDesktop() {
        val phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        backgroundListener().onSessionActive(phone)
        activity.javaClass.getDeclaredMethod("onUserLeaveHint").apply { isAccessible = true }.invoke(activity)

        // The OEM can delay onPause until after already-queued session notifications.
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertFalse(field("windowFocused").getBoolean(activity))
        assertSessionUntouched()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    @Implements(Activity::class)
    class RejectingActivityLaunch : ShadowActivity() {
        var rejectHost = false
        var hostAttempts = 0

        @Implementation
        protected fun startActivity(intent: Intent) {
            if (intent.component?.className == CarPlayHostActivity::class.java.name) {
                hostAttempts++
                if (rejectHost) throw SecurityException("Activity launch temporarily rejected")
            }
            Shadow.directlyOn<Void, Activity>(realActivity, Activity::class.java, "startActivity", from(Intent::class.java, intent))
        }
    }

    // Activity inherits this API through ContextWrapper. Intercept the underlying
    // ContextImpl that receives the real delegated service request.
    @Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
    class RejectingServiceLaunch : ShadowContextImpl() {
        var rejectService = false
        var serviceAttempts = 0
        var lastService: Intent? = null

        @Implementation
        protected override fun startForegroundService(intent: Intent): ComponentName? {
            serviceAttempts++
            lastService = intent
            if (rejectService) throw SecurityException("Foreground service temporarily rejected")
            return super.startForegroundService(intent)
        }
    }

    private fun backgroundListener(): AirPlaySessionListener = CarPlayBackgroundSession.javaClass
        .getDeclaredMethod("backgroundListener", CarPlayController::class.java).apply { isAccessible = true }
        .invoke(CarPlayBackgroundSession, controller) as AirPlaySessionListener

    private fun assertSessionUntouched() {
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    private fun queueLauncherAutoConnect() {
        lifecycle.pause()
        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        lifecycle.resume()
    }

    private fun checkEntry() = activity.javaClass.getDeclaredMethod("checkAutomaticProjectionEntry")
        .apply { isAccessible = true }.invoke(activity)

    private fun automaticConnect() = activity.javaClass.getDeclaredMethod("connectToPhone", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(activity, true)

    private fun deliver(intent: Intent) = activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
        .apply { isAccessible = true }.invoke(activity, intent)

    private fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun sessionState(): String = listOf("desired", "manualDisconnect", "terminated", "stopping",
        "starting", "intentRevision", "durableIntentRevision", "stopWaiters", "retiring").joinToString { name ->
        val value = org.robolectric.util.ReflectionHelpers.getStaticField<Any?>(CarPlayBackgroundSession::class.java, name)
        "$name=$value"
    }
}
