package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlaySessionServiceTest {
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var lifecycle: ServiceController<DiPlaySessionService>
    private lateinit var service: DiPlaySessionService
    private lateinit var controller: CarPlayController
    private var stopCompletion: (() -> Unit)? = null
    private var destroyed = false

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        shadowOf(Looper.getMainLooper()).idle()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION)
        AirPlayPersistence.saveLocationReportingEnabled(context, true)
        CarPlayBackgroundSession.requestConnection(context)
        CarPlayBackgroundSession.watchIgnition(context, {})
        controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java),
            1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {
            stopCompletion = it
        }
        lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = lifecycle.get()
    }

    @After fun tearDown() {
        try {
            CarPlayBackgroundSession.clear()
            stopCompletion?.also { stopCompletion = null }?.invoke()
            CarPlayBackgroundSession.detachService(service)
            if (!destroyed) lifecycle.destroy()
            CarPlayBackgroundSession.terminate()
            shadowOf(Looper.getMainLooper()).idle()
        } finally { stateIoScope.close() }
    }

    @Test fun initialDeviceForegroundIsPublishedBeforeRecoveryIoBegins() {
        val stateIo = mock(ExecutorService::class.java)
        var recoveryStarted = false
        doAnswer { invocation ->
            if (!recoveryStarted) {
                recoveryStarted = true
                assertNotification("CarPlay待连接")
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
                assertFalse(shadowOf(service).isStoppedBySelf)
            }
            (invocation.arguments[0] as Runnable).run()
            null
        }.`when`(stateIo).execute(any(Runnable::class.java))
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "configuration", null)
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "configurationLoading", false)
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stateIo", stateIo)
        try {
            service.onStartCommand(null, 0, 1)
            assertTrue("The test must exercise recovery IO after the initial promotion", recoveryStarted)
            assertNotification("正在连接CarPlay")
            val liveTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            assertEquals(liveTypes, service.foregroundServiceType)
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stateIo", DirectSessionExecutor())
        }
    }

    @Test fun connectingAndConnectedNotificationsKeepTheRequiredLiveServiceTypes() {
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertNotification("正在连接CarPlay")
        val liveTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(liveTypes, service.foregroundServiceType)
        assertEquals("断开连接", shadowOf(service).lastForegroundNotification.actions.single().title)

        CarPlayBackgroundSession.active = true
        service.refreshSessionState()

        assertNotification("CarPlay已连接")
        assertEquals(liveTypes, service.foregroundServiceType)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun vehicleDataLeaseFollowsTheConnectedSessionAndIsReleasedWhenItCloses() {
        service.onStartCommand(null, 0, 1)
        assertNull(ReflectionHelpers.getField<java.io.Closeable?>(service, "vehicleData"))
        CarPlayBackgroundSession.active = true
        service.refreshSessionState()
        assertNotNull(ReflectionHelpers.getField<java.io.Closeable?>(service, "vehicleData"))
        service.onStartCommand(Intent().setAction(DiPlaySessionService.ACTION_STOP), 0, 2)
        assertNull(ReflectionHelpers.getField<java.io.Closeable?>(service, "vehicleData"))
    }

    @Test fun stopWaitsForRealCleanupAndKeepsOnlyTheIgnitionServiceAfterward() {
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(Intent().setAction(DiPlaySessionService.ACTION_STOP), 0, 2)

        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
        assertNotNull(stopCompletion)
        assertNotification("正在断开CarPlay连接")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        assertFalse(shadowOf(service).isStoppedBySelf)

        completeStop()

        assertNotification("CarPlay待连接")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun removingTheTaskTerminatesTheWatchAndStopsOnlyAfterCleanupCompletes() {
        service.onStartCommand(null, 0, 1)

        service.onTaskRemoved(null)

        assertNotification("正在断开CarPlay连接")
        assertFalse(shadowOf(service).isStoppedBySelf)
        completeStop()
        assertEquals("stopped", CarPlayBackgroundSession.serviceMode())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun oldTaskRemovalCompletionCannotStopAServiceReusedByANewLauncherEntry() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayPreferences.savePhone(context, "02:00:00:00:00:02", "Previously paired iPhone")
        DiPlayPreferences.saveAutoConnect(context, true)
        service.onStartCommand(null, 0, 1)
        service.onTaskRemoved(null)
        assertNotNull(stopCompletion)

        assertTrue(CarPlayBackgroundSession.reopenFromLauncher(context))
        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(context))
        service.onStartCommand(Intent(), 0, 2)
        completeStop()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
        assertNotEquals("stopped", CarPlayBackgroundSession.serviceMode())
    }

    @Test fun microphoneAndLocationTypesRequireTheirCurrentPermissions() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION)

        service.onStartCommand(null, 0, 1)

        assertNotification("正在连接CarPlay")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
    }

    @Test fun aDestroyedServiceCannotRecreateItsForegroundNotification() {
        service.onStartCommand(null, 0, 1)
        lifecycle.destroy()
        destroyed = true
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        service.refreshSessionState()

        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun aBackgroundMicrophoneUpgradeCanFallBackToTheDeviceConnectionType() {
        service = spy(service)
        val liveTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        doThrow(SecurityException("Background while-in-use upgrade denied"))
            .`when`(service).startForeground(eq(1), any(Notification::class.java), eq(liveTypes))

        service.onStartCommand(null, 0, 1)

        assertNotification("正在连接CarPlay")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        val promotions = mockingDetails(service).invocations.filter {
            it.method.name == "startForeground" && it.arguments.size == 3
        }
        assertTrue(promotions.size >= 3)
        val types = promotions.map { it.arguments[2] as Int }
        // First meet the service deadline with connectedDevice, then request live types;
        // only that rejected upgrade falls back while preserving the current notification.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, types.first())
        assertTrue(types.indexOf(liveTypes) > 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, types.last())
        assertEquals("CarPlay待连接", (promotions.first().arguments[1] as Notification).extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("正在连接CarPlay", (promotions.last().arguments[1] as Notification).extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun evenARejectedFallbackCannotAbortSessionOwnershipChanges() {
        shadowOf(service).setThrowInStartForeground(SecurityException("Foreground start denied"))

        service.onStartCommand(null, 0, 1)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java),
            1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {
            stopCompletion = it
        }

        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals("connecting", CarPlayBackgroundSession.serviceMode())
    }

    private fun completeStop() {
        CarPlayBackgroundSession.clear(controller)
        val completion = checkNotNull(stopCompletion)
        stopCompletion = null
        completion()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNotification(text: String) {
        assertEquals(text, shadowOf(service).lastForegroundNotification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }
}
