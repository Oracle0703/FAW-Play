package com.shilapi.xcertplay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.view.Display
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowCanvas
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HongqiCarPlayLifecycleTest.NavigationStartup::class])
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiCarPlayLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val phone = ConnectedPhone()
    private val vehicle = VehicleViewService()
    private var host: ActivityController<CarPlayHostActivity>? = null
    private var displayId: Int? = null

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        ClusterLayoutTuning.preferences(app).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // Authentication and an actual phone connection are outside this lifecycle regression.
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.I2C)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName("ltd.qisi.systemservice", "ltd.qisi.systemservice.QisiSystemService"), vehicle)
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun tearDown() {
        host?.let {
            val activity = it.get()
            it.pause().stop().destroy()
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (field(activity, name) as ExecutorService).shutdownNow()
            }
        }
        HongqiWheelControls.detach(phone)
        settleVehicleWorker()
        displayId?.let(ShadowDisplayManager::removeDisplay)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test fun foregroundCarPlayAcquiresTheFactoryViewAndReleasesItInBackground() {
        val activity = createHost()
        HongqiWheelControls.attach(activity, phone)
        settleVehicleWorker()
        assertEquals(listOf(38), vehicle.requests)

        host!!.pause()
        settleVehicleWorker()
        assertEquals(1, vehicle.abandons)

        host!!.resume()
        settleVehicleWorker()
        assertEquals(listOf(38, 38), vehicle.requests)
    }

    @Test fun aMissingPrimaryPictureCannotIndefinitelyDeferTheAlreadyPresentInstrumentWindow() {
        addClusterDisplay()
        val activity = createHost(waitForInstrument = false)
        assertNull(field(activity, "clusterPresentation"))
        main.idleFor(java.time.Duration.ofMillis(1999))
        assertNull(field(activity, "clusterPresentation"))
        main.idleFor(java.time.Duration.ofMillis(1))
        val presentation = field(activity, "clusterPresentation") as ClusterMapPresentation?
        assertNotNull("The instrument display must be attached at the bounded fallback", presentation)
        assertTrue(presentation!!.isShowing)
        assertEquals(displayId, presentation.display.displayId)
    }

    @Test fun vendorOverlayPreflightDoesNotHideThePhysicalInstrumentDisplay() {
        addClusterDisplay()
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals(displayId, ClusterMapPresentation.findDisplay(app)?.displayId)
        val activity = createHost()
        val presentation = field(activity, "clusterPresentation") as ClusterMapPresentation
        assertTrue(presentation.isShowing)
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            presentation.window!!.attributes.type)
    }

    @Test fun reconnectStreamRestoresADismissedInstrumentWindowWithoutResumingTheActivity() {
        addClusterDisplay()
        val activity = createHost()
        val first = field(activity, "clusterPresentation") as ClusterMapPresentation
        first.dismiss()
        main.idle()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onScreenStreamStateChanged",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 111),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        val restored = field(activity, "clusterPresentation") as ClusterMapPresentation
        assertNotSame(first, restored)
        assertTrue(restored.isShowing)
    }

    @Test fun aStoppedWindowReferenceCannotBlockReconnectRecovery() {
        addClusterDisplay()
        val activity = createHost()
        val first = field(activity, "clusterPresentation") as ClusterMapPresentation
        first.setOnDismissListener(null)
        first.dismiss()
        assertSame(first, field(activity, "clusterPresentation"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "ensureClusterPresentation")
        val restored = field(activity, "clusterPresentation") as ClusterMapPresentation
        assertNotSame(first, restored)
        assertTrue(restored.isShowing)
    }

    @Test fun systemDismissalRecoversWhileCarPlayRemainsInTheBackground() {
        addClusterDisplay()
        val activity = createHost()
        val first = field(activity, "clusterPresentation") as ClusterMapPresentation
        host!!.pause().stop()
        first.dismiss()
        main.idleFor(java.time.Duration.ofSeconds(3))
        val restored = field(activity, "clusterPresentation") as ClusterMapPresentation
        assertNotSame(first, restored)
        assertTrue(restored.isShowing)
    }

    @Test fun displayBecomingEligibleThroughAChangeEventRestoresItsWindow() {
        val activity = createHost()
        assertNull(field(activity, "clusterPresentation"))
        addClusterDisplay()
        val listener = field(activity, "clusterDisplayListener") as DisplayManager.DisplayListener
        listener.onDisplayChanged(displayId!!)
        val restored = field(activity, "clusterPresentation") as ClusterMapPresentation
        assertEquals(displayId, restored.display.displayId)
        assertTrue(restored.isShowing)
    }

    @Test fun returningToCarPlayRecreatesAnInstrumentWindowRemovedByTheSystem() {
        addClusterDisplay()
        val activity = createHost()
        val first = field(activity, "clusterPresentation") as ClusterMapPresentation?
        assertNotNull(first)
        host!!.pause().stop()
        first!!.dismiss()
        settleVehicleWorker()
        assertNull(field(activity, "clusterPresentation"))

        host!!.start().resume()
        val restored = field(activity, "clusterPresentation") as ClusterMapPresentation?
        assertNotNull(restored)
        assertNotSame(first, restored)
        assertTrue(restored!!.isShowing)
    }

    @Test fun nativeDefaultStillAllowsAnExplicitPureMapChoice() {
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS,
            AirPlayPersistence.loadClusterContent(app))
        AirPlayPersistence.saveClusterContent(app, CarPlayClusterDisplay.Content.MAP)
        val content = AirPlayPersistence.loadClusterContent(app)
        assertEquals(CarPlayClusterDisplay.Content.MAP, content)
        assertEquals(CarPlayClusterDisplay.config(1920, 720),
            CarPlayClusterDisplay.config(1920, 720, content = content))
        assertEquals("MAP", app.getSharedPreferences("xcertplay_airplay", 0)
            .getString("cluster_content", null))
    }

    @Test fun nativeClusterUsesTheHs5LayoutWithoutDrawingAnotherNavigationCard() {
        addClusterDisplay()
        val activity = createHost()
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig")
            .apply { isAccessible = true }.invoke(activity) as AirPlayDisplayConfig
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, config.initialUrl)
        assertEquals(1600, config.widthPixels)
        assertEquals(600, config.heightPixels)
        assertEquals(AirPlayInsets(top = 12, bottom = 48, left = 464, right = 48), config.safeArea)
        assertEquals(30, config.fps)

        val presentation = field(activity, "clusterPresentation") as ClusterMapPresentation
        val card = findTurnCard(presentation.window!!.decorView)!!
        @Suppress("UNCHECKED_CAST")
        val listener = field(activity, "clusterTurnOverlayListener") as (ClusterTurnGuidance?) -> Unit
        listener(ClusterTurnGuidance(2, 0, 320, "Test road",
            arrivalEpochSeconds = 1900000000, remainingSeconds = 2820, remainingMeters = 25000))
        assertEquals(View.GONE, card.visibility)
        assertTrue(drawnText(card).isEmpty())
        assertNull(ClusterActivityOutput.guidance)
    }

    @Test fun officialInstructionStreamsDoNotGetADuplicateGuidanceCard() {
        for (content in listOf(CarPlayClusterDisplay.Content.TURN_CARD,
            CarPlayClusterDisplay.Content.INSTRUMENTS)) {
            AirPlayPersistence.saveClusterContent(app, content)
            assertEquals(content, AirPlayPersistence.loadClusterContent(app))
            assertFalse(CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(app)))
        }
    }

    @Test fun hs5TurnCardUsesTheConfirmedShippedProfileAtTheMeasured213Dpi() {
        val card = ClusterTurnCardView(app)
        val metrics = card.resources.displayMetrics
        val density = metrics.density
        try {
            metrics.density = 213f / 160f
            card.setLayout(8, 12, 35)
            card.setGuidance(ClusterTurnGuidance(2, 0, 320, "Test road", remainingSeconds = 2820))
            val drawing = drawnFrame(card)
            val rect = drawing.getDrawnRoundRect(0).rect
            assertEquals(457f, rect.width(), 0f)
            assertEquals(164f, rect.height(), 0f)
            assertEquals(1396f, rect.left, 0f)
            assertEquals(81f, rect.top, 0f)
            assertTrue(rect.right <= 1920 && drawing.lastDrawnRoundRect.rect.bottom <= 720)
            val info = (0 until drawing.textHistoryCount).map(drawing::getDrawnTextEvent)
                .first { app.getString(R.string.turn_card_info_duration_m, 47) in it.text }
            assertEquals(28f * 213f / 160f * 0.80f, info.paint.textSize, 0.01f)
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
            assertEquals(194f, drawnFrame(card).getDrawnRoundRect(0).rect.width(), 0f)
        } finally { metrics.density = density }
    }

    @Test fun activeRouteShowsTbtAndRemainingTimeAndRouteEndClearsTheCard() {
        AirPlayPersistence.saveClusterContent(app, CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        addClusterDisplay()
        val activity = createHost()
        settleVehicleWorker()
        val presentation = field(activity, "clusterPresentation") as ClusterMapPresentation
        val card = findTurnCard(presentation.window!!.decorView)!!
        assertEquals(View.GONE, card.visibility)
        val guidance = ClusterTurnGuidance(icon = 2, roundaboutExit = 0,
            distanceMeters = 320, road = "Test road", remainingSeconds = 2820, remainingMeters = 25000)
        @Suppress("UNCHECKED_CAST")
        val listener = field(activity, "clusterTurnOverlayListener") as (ClusterTurnGuidance?) -> Unit
        listener(guidance)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(drawnText(card).contains(app.getString(R.string.turn_card_distance_m, 320)))
        assertTrue(drawnText(card).any { app.getString(R.string.turn_card_info_duration_m, 47) in it })
        assertEquals(28f * card.resources.displayMetrics.density * 0.80f,
            (ClusterTurnCardView::class.java.getDeclaredField("infoPaint")
                .apply { isAccessible = true }.get(card) as android.graphics.Paint).textSize, 0.01f)
        assertEquals(R.drawable.ic_turn_card_turn_left,
            ClusterTurnCardView::class.java.getDeclaredField("glyphTag")
                .apply { isAccessible = true }.getInt(card))
        listener(guidance.copy(remainingSeconds = 2760, distanceMeters = 300))
        assertTrue(drawnText(card).any { app.getString(R.string.turn_card_info_duration_m, 46) in it })
        listener(null)
        assertEquals(View.GONE, card.visibility)
        assertTrue(drawnText(card).isEmpty())
    }

    @Test fun otherDevicesKeepTheirExistingPureMapDefault() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        assertEquals(CarPlayClusterDisplay.Content.MAP, AirPlayPersistence.loadClusterContent(app))
    }

    @Test fun factorySafetyViewCanRefuseCarPlayFocus() {
        vehicle.available = false
        val activity = createHost()
        HongqiWheelControls.attach(activity, phone)
        settleVehicleWorker()
        assertTrue(vehicle.requests.isEmpty())
    }

    @Test fun foregroundStateIsRestoredWhenReturningFromTheDesktop() {
        val activity = createHost()
        assertEquals(true, field(activity, "isActivityStarted"))
        host!!.pause().stop()
        assertEquals(false, field(activity, "isActivityStarted"))
        host!!.start().resume()
        assertEquals(true, field(activity, "isActivityStarted"))
    }

    @Test fun otherAndroidDevicesCannotAcquireHongqiViewFocus() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        val activity = createHost()
        HongqiWheelControls.attach(activity, phone)
        settleVehicleWorker()
        assertTrue(vehicle.requests.isEmpty())
    }

    @Test fun blockedFactoryRequestDoesNotBlockMainAndLateOwnershipIsReleasedAfterPause() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        vehicle.onRequest = { entered.countDown(); check(resume.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
        val activity = createHost()
        HongqiWheelControls.attach(activity, phone)
        try {
            repeat(40) { main.idle(); if (entered.await(25, java.util.concurrent.TimeUnit.MILLISECONDS)) return@repeat }
            assertEquals(0, entered.count)
            var uiResponsive = false
            android.os.Handler(Looper.getMainLooper()).post { uiResponsive = true }
            host!!.pause()
            main.idle()
            assertTrue("UI remains responsive while the remote Binder is blocked", uiResponsive)
        } finally { resume.countDown(); settleVehicleWorker() }
        assertEquals(1, vehicle.abandons)
    }

    @Test fun failedReleaseRetainsResponsibilityAndTheNextPollRetries() {
        val activity = createHost()
        HongqiWheelControls.attach(activity, phone)
        settleVehicleWorker()
        vehicle.abandonFailures = 1
        host!!.pause()
        settleVehicleWorker()
        val instance = ReflectionHelpers.getStaticField<Any>(HongqiWheelControls::class.java, "instance")
        assertEquals(true, ReflectionHelpers.getField<Boolean>(instance, "viewRequested"))
        main.idleFor(java.time.Duration.ofMillis(250))
        settleVehicleWorker()
        assertEquals(false, ReflectionHelpers.getField<Boolean>(instance, "viewRequested"))
        assertEquals(2, vehicle.abandons)
    }

    private fun createHost(waitForInstrument: Boolean = true): CarPlayHostActivity {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        host = controller
        controller.create().start().resume()
        assertFalse("The host must pass its startup checks", controller.get().isFinishing)
        if (waitForInstrument) main.idleFor(java.time.Duration.ofSeconds(2))
        return controller.get()
    }

    private fun addClusterDisplay() {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        displayId = id
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setName("Cluster-Display")
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
    }

    private fun field(activity: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun findTurnCard(view: View): ClusterTurnCardView? {
        if (view is ClusterTurnCardView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTurnCard(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun drawnText(card: ClusterTurnCardView): List<String> {
        val drawing = drawnFrame(card)
        return (0 until drawing.textHistoryCount).map { drawing.getDrawnTextEvent(it).text }
    }

    private fun drawnFrame(card: ClusterTurnCardView): ShadowCanvas {
        card.layout(0, 0, 1920, 720)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            card.draw(canvas)
            return shadowOf(canvas)
        } finally { bitmap.recycle() }
    }

    class ConnectedPhone {
        @JvmField val activeSession = Any()
        fun isClosed() = false
        fun activeAirPlaySessionToken(): Any = this
    }

    class VehicleViewService : Binder() {
        var available = true
        var onRequest: (() -> Unit)? = null
        var abandonFailures = 0
        val requests = mutableListOf<Int>()
        var abandons = 0

        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                10 -> assertEquals(38, data.readInt())
                5 -> {
                    requests += data.readInt()
                    assertNotNull(data.readStrongBinder())
                    onRequest?.invoke()
                }
                6 -> {
                    assertNotNull(data.readStrongBinder())
                    abandons++
                    if (abandonFailures-- > 0) throw android.os.RemoteException("injected release failure")
                }
                12, 13 -> Unit
                else -> return false
            }
            reply!!.writeNoException()
            if (code == 5 || code == 6 || code == 10) reply.writeInt(if (available) 1 else 0)
            return true
        }

        companion object {
            private const val DESCRIPTOR = "ltd.qisi.systemmanager.ISystemServiceInterface"
        }
    }

    /** The test supplies displays; it does not connect to an external vehicle adapter service. */
    @Implements(HongqiNavigation::class, isInAndroidSdk = false)
    class NavigationStartup {
        companion object {
            @JvmStatic @Implementation fun start(context: Context): Boolean = false
        }
    }
}
