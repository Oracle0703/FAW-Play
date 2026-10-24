package com.shilapi.xcertplay

import android.Manifest
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostDisplaySizeTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var controllerConstruction: MockedConstruction<CarPlayController>
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        // Keep durable-intent/configuration tasks deterministic on the same thread as Mockito's
        // construction scope. The viewport tests control teardown separately through closer.
        stateIoScope = SessionStateIoTestScope()
        previousCloser = backgroundField("closer")
        setBackgroundField("closer", closer)
        resetBackground()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        CarPlayBackgroundSession.requestConnection(activity)
        // Source-only tests have no provisioned local authentication identity.
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        // Exercise host startup without launching vendor-service workers or real transports.
        controllerConstruction = mockConstruction(CarPlayController::class.java) { controller, _ ->
            doAnswer { invocation ->
                invocation.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true))
                null
            }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        }
        setBackgroundField("owner", activity)
        setField("airPlayIdentity", AirPlayIdentity.generate())
        setField("activeDisplaySize", size(1920, 990))
        CarPlayBackgroundSession.setForegroundStartupReady(activity, false)
        CarPlayBackgroundSession.watchIgnition(activity, {}, {}, { invoke("maybeStartCarPlay") })
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        setField("isActivityResumed", false)
        CarPlayBackgroundSession.setProjectionUiVisible(activity, false)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setTurnOverlayListener(null)
        (getField("controller") as? CarPlayController)?.let {
            CarPlayMediaKeys.detach(it)
            it.close()
            it.awaitClosed(1000)
        }
        (getField("sink") as? AndroidMediaSink)?.close()
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.stopIgnitionWatch(activity)
        resetBackground()
        controllerConstruction.close()
        setBackgroundField("closer", previousCloser)
        closer.shutdownNow()
        stateIoScope.close()
    }

    @Test fun surroundViewResizeRenegotiatesInsteadOfTransformingTheOldCanvas() {
        startSession()
        applySize(1920, 942)
        assertEquals(size(1920, 942), getField("activeDisplaySize"))
        assertNull(getField("sessionDisplay"))
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertEquals(0, keepLogs())
        // A second resize during teardown updates the next negotiation, without a second teardown.
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun aNarrowWindowTriggersReconnectWithFixedPipAdaptation() {
        startSession()
        applySize(700, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun connectingInANarrowWindowRebuildsWhenTheCameraCloses() {
        startSession(windowWidth = 700)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("sessionDisplay"))
    }

    @Test fun connectingInAReducedHeightWindowRebuildsWhenTheCameraCloses() {
        startSession(windowHeight = 942)
        applySize(1920, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun legacyScaledCanvasReconnectsOnceWhenTheAspectChanges() {
        startSession(canvasWidth = 1536, canvasHeight = 792)
        applySize(700, 990)
        applySize(1920, 990)
        assertNull(getField("sessionDisplay"))
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun aScaledUpCanvasStillRebuildsWhenTheStartupWindowGrows() {
        startSession(windowWidth = 700, canvasWidth = 1400, canvasHeight = 1980)
        applySize(1000, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun actualScreenRotationStillRebuildsTheSession() {
        startSession(rotation = Surface.ROTATION_90)
        applySize(990, 1920)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("sessionDisplay"))
    }

    @Test fun rotationIsHandledEvenIfTheViewSizeIsUnchanged() {
        startSession(rotation = Surface.ROTATION_180)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun quickOpenAndCloseCancelsThePendingShrink() {
        startSession()
        scheduleSize(700, 990)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertNull(getField("pendingDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun anOngoingHandshakeResetOnlyRecordsTheNewSize() {
        setField("handshakeResetInProgress", true)
        applySize(700, 990)
        assertEquals(size(700, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun rotatingBackDuringTeardownStartsWithTheLatestSize() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        applySize(2250, 1080)

        finishTeardown()

        assertSessionSize(2250, 1080)
        assertEquals(1, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test @Config(sdk = [30]) fun teardownWaitsForAPendingRotationToSettle() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        scheduleSize(2250, 1080)

        finishTeardown()
        assertNull(getField("sessionDisplay"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSessionSize(2250, 1080)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun cancellingAPendingRotationResumesStartup() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        scheduleSize(2250, 1080)
        finishTeardown()
        assertNull(getField("sessionDisplay"))

        scheduleSize(1080, 2250)
        shadowOf(Looper.getMainLooper()).idle()

        assertSessionSize(1080, 2250)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun aPendingSameSizeRotationResumesAfterTeardown() {
        allowStartup()
        startSession(rotation = Surface.ROTATION_180)
        scheduleSize(1920, 990)
        activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Test reconnect")
        finishTeardown()
        assertNull(getField("sessionDisplay"))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSessionSize(1920, 990)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun teardownRechecksStartupPrerequisites() {
        allowStartup()
        startSession()
        applySize(990, 1920)
        setField("wirelessPermissionsReady", false)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        invoke("maybeStartCarPlay")

        finishTeardown()

        assertNull(getField("controller"))
        assertNull(getField("sessionDisplay"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun initialSizeDetectionKeepsTheNormalStartupPath() {
        setField("activeDisplaySize", null)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(0, keepLogs())
    }

    @Test fun resizeWithoutASessionRecordsTheSizeWithoutAnotherTeardown() {
        applySize(700, 990)
        assertEquals(size(700, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun visibleViewportIsFullyMappedEvenWhileTheOldCanvasIsBeingReplaced() {
        startSession(canvasWidth = 1536, canvasHeight = 792)
        val content = activity.javaClass.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1920, 942)
        assertEquals(CarPlayVideoLayout(0f, 0f, 1920f, 942f), content)
        val view = View(activity).apply { layout(0, 0, 1920, 942) }
        for ((x, y) in listOf(1f to 1f, 1919f to 941f)) {
            touch(view, MotionEvent.ACTION_DOWN, x, y)
            assertEquals(false, getField("touchOutsideContent"))
            touch(view, MotionEvent.ACTION_UP, x, y)
        }
        touch(view, MotionEvent.ACTION_DOWN, -1f, 471f)
        assertEquals(true, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_UP, -1f, 471f)
        assertEquals(false, getField("touchOutsideContent"))
    }

    @Test fun adoptingABackgroundSessionRenegotiatesWhenItsViewportChanges() {
        val display = CarPlaySessionDisplay(1536, 792, Surface.ROTATION_0, true, true, 1920, 990)
        val sink = AndroidMediaSink()
        val controller = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {})
        try {
            CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display) {}
            val adopted = activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
                .apply { isAccessible = true }.invoke(activity)
            assertEquals(true, adopted)
            applySize(1920, 990)
            assertSame(display, getField("sessionDisplay"))
            assertEquals(0, getField("restartGeneration"))
            applySize(1920, 942)
            assertEquals(1, getField("restartGeneration"))
            assertNull(getField("sessionDisplay"))
            assertTrue(getField("handshakeResetInProgress") as Boolean)
        } finally {
            controller.close()
            controller.awaitClosed(1000)
            sink.close()
        }
    }

    @Test fun adoptingBackgroundClusterCannotReactivateLegacyAdbRouting() {
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        val sink = AndroidMediaSink()
        val display = CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, true, true, 1920, 990)
        try {
            for (clusterSize in listOf(1920 to 720, 1280 to 720, null)) {
                val controller = org.mockito.Mockito.mock(CarPlayController::class.java)
                org.mockito.Mockito.`when`(controller.configuredClusterSize()).thenReturn(clusterSize)
                CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display) {}
                assertEquals(true, invoke("adoptBackgroundSession"))
                assertFalse("Adopted $clusterSize must not reactivate legacy ADB routing",
                    getField("adbClusterConfigured") as Boolean)
            }
        } finally {
            AirPlayPersistence.saveAdbClusterEnabled(activity, false)
            sink.close()
        }
    }

    @Test fun returningWindowFocusKeepsFixedFullscreenAndTheNegotiatedSession() {
        invoke("loadPersistedSettings")
        val display = startSession()
        AirPlayPersistence.saveHideTopBar(activity, false)
        AirPlayPersistence.saveHideBottomBar(activity, false)
        activity.onWindowFocusChanged(true)
        assertWindowBars(true, true)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun stoppingTheUiDoesNotTearDownTheNegotiatedSession() {
        val display = startSession()
        val controller = org.mockito.Mockito.mock(CarPlayController::class.java)
        setField("controller", controller)
        invoke("onPause")
        invoke("onStop")
        assertSame(controller, getField("controller"))
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).close()
    }

    private fun startSession(
        rotation: Int = Surface.ROTATION_0,
        windowWidth: Int = 1920,
        windowHeight: Int = 990,
        canvasWidth: Int = windowWidth,
        canvasHeight: Int = windowHeight,
    ): CarPlaySessionDisplay =
        CarPlaySessionDisplay(canvasWidth, canvasHeight, rotation, true, true, windowWidth, windowHeight).also {
            setField("activeDisplaySize", size(windowWidth, windowHeight))
            setField("sessionDisplay", it)
            val controller = mock(CarPlayController::class.java)
            val sink = mock(AndroidMediaSink::class.java)
            doAnswer { call ->
                call.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true)); null
            }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
            setField("controller", controller)
            setField("sink", sink)
            CarPlayBackgroundSession.store(controller, sink, windowWidth, windowHeight, activity, it) { it() }
        }

    private fun keepLogs(): Int = ShadowLog.getLogsForTag("xcertplay-usb").count {
        it.msg.contains("keeping CarPlay session")
    }

    private fun allowStartup() {
        // This lightweight fixture skips lifecycle callbacks. Supply the foreground
        // boundary established by onResume for cases that must restart the session.
        setField("isActivityResumed", true)
        CarPlayBackgroundSession.setProjectionUiVisible(activity, true)
        setField("airPlayIdentity", AirPlayIdentity.generate())
        setField("mfiTarget", MfiTarget.LOCAL)
        setField("wirelessPermissionsReady", true)
        setField("microphonePermissionResolved", true)
    }

    private fun finishTeardown() {
        closer.runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetBackground() {
        CarPlayBackgroundSession.terminate()
        closer.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        backgroundField<Handler>("main").removeCallbacksAndMessages(null)
        backgroundField<MutableList<*>>("retiring").clear()
        backgroundField<MutableList<*>>("stopWaiters").clear()
        setBackgroundField("stopping", false)
        setBackgroundField("starting", false)
        setBackgroundField("service", null)
        CarPlayBackgroundSession.clear()
        backgroundField<SessionLogFile?>("powerLog")?.let { AsyncDiagnosticLog.close(it) }
        setBackgroundField("powerLog", null)
        setBackgroundField("intentRevision", 0L)
        setBackgroundField("durableIntentRevision", 0L)
        setBackgroundField("configurationRevision", 0L)
        setBackgroundField("configurationLoading", false)
        setBackgroundField("preparationFailure", null)
    }

    private fun <T> backgroundField(name: String): T =
        ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)

    private fun setBackgroundField(name: String, value: Any?) =
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, value)

    private fun assertSessionSize(width: Int, height: Int) {
        val display = getField("sessionDisplay") as? CarPlaySessionDisplay
        assertNotNull("CarPlay must resume after the display settles", display)
        assertEquals(width, display!!.windowWidth)
        assertEquals(height, display.windowHeight)
        assertEquals(width, display.width)
        assertEquals(height, display.height)
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun scheduleSize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, view, event)
        } finally {
            event.recycle()
        }
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Suppress("DEPRECATION")
    private fun assertWindowBars(hideTop: Boolean, hideBottom: Boolean) {
        assertEquals(hideTop, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertEquals(hideBottom, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
