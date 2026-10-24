package com.shilapi.xcertplay.hud

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiNavigationRouteLifetimeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val instrument = Module("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
    private val control = Module("ltd.qisi.adapterproxy.ICarSystemControlManager")
    private val hud = Module("ltd.qisi.adapterproxy.ICarHUDManager")
    private val requests = mutableListOf<Boolean>()
    private val streamControl: (Boolean) -> Unit = { requests += it }
    private val route: AutunnHudRouteState get() = ReflectionHelpers.getField(AutunnNavigationOutputs, "overlayRoute")

    @Before fun prepareReadyMap() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        AutunnNavigationOutputs.endNow()
        drainWriter()
        get<Handler>("MAIN").removeCallbacksAndMessages(null)
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        set("context", app)
        set("bound", true)
        set("guidance", null)
        set("routeActive", false)
        set("instrument", instrument)
        set("control", control)
        set("hud", hud)
        set("lastInstrumentGuidance", null)
        listOf("ownsMap", "ownsHud", "ownsInstrumentGuidance", "cleanupPending",
            "replayMap", "replayInstrumentGuidance").forEach { set(it, false) }
        listOf("mapReady", "mapInterfacesReady", "instrumentGuidanceSupported").forEach { set(it, true) }
        AutunnNavigationOutputs.setClusterStreamControl(streamControl)
    }

    @After fun stopWorkers() {
        AutunnNavigationOutputs.clearClusterStreamControl(streamControl)
        AutunnNavigationOutputs.endNow()
        apply()
        drainWriter()
        get<Handler>("MAIN").removeCallbacksAndMessages(null)
    }

    @Test fun missingNextManeuverDetailsClearHintsWithoutExitingOrRequestingTheMapAgain() {
        startRoute()
        assertTrue(get<Boolean>("ownsHud"))
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 2))
        assertTrue(route.isRouteActive())
        assertTrue(HongqiNavigation.isNavigating())
        assertNull(get<Any?>("guidance"))
        assertFalse(get<Boolean>("ownsHud"))
        assertTrue(get<Boolean>("ownsInstrumentGuidance"))
        assertTrue(get<Boolean>("ownsMap"))
        frame(0x5202, tlv(1, 0, 2) + tlv(3, 2))
        assertTrue(get<Boolean>("ownsHud"))
        assertEquals(listOf(2, 4), instrument.mapLoads)
        assertEquals(listOf(1), control.visibility)
        assertEquals(listOf(1), instrument.navigationStatus)
        assertEquals(listOf(true, true), requests)
    }

    @Test fun lastingEmptyListHidesOnlyHintsAndDoesNotReleaseTheMap() {
        startRoute()
        frame(0x5201, tlv(1, 5) + tlv(0x0d))
        ReflectionHelpers.setField(route, "emptyListSinceNs", System.nanoTime() - 9_000_000_000L)
        refresh()
        assertNull(get<Any?>("guidance"))
        assertTrue(HongqiNavigation.isNavigating())
        assertTrue(get<Boolean>("ownsMap"))
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 1))
        assertNotNull(get<Any?>("guidance"))
        assertEquals(listOf(2, 4), instrument.mapLoads)
        assertEquals(listOf(1), control.visibility)
    }

    @Test fun staleManeuverCannotEndAnOtherwiseLiveRoute() {
        startRoute()
        ReflectionHelpers.setField(route, "lastRouteUpdateNs", System.nanoTime() - 121_000_000_000L)
        refresh()
        assertNull(get<Any?>("guidance"))
        assertTrue(get<Boolean>("ownsMap"))
        assertTrue(HongqiNavigation.isNavigating())
        frame(0x5201, tlv(0x0a, 0, 0, 0, 20))
        assertNotNull(get<Any?>("guidance"))
        assertEquals(listOf(1), control.visibility)
    }

    @Test fun aRouteCanStartWithAnEmptyListAndNoManeuverDetails() {
        frame(0x5201, tlv(1, 1) + tlv(0x0d))
        assertTrue(route.isRouteActive())
        assertNull(get<Any?>("guidance"))
        assertTrue(get<Boolean>("ownsMap"))
        assertEquals(listOf(2, 4), instrument.mapLoads)
        frame(0x5201, tlv(1, 0))
        assertFalse(get<Boolean>("ownsMap"))
        assertFalse(HongqiNavigation.isNavigating())
        assertEquals(listOf(1, 0), control.visibility)
        assertEquals(listOf(1, 0), instrument.navigationStatus)
    }

    @Test fun explicitRouteEndDuringAManeuverGapStillReleasesTheMapOnlyOnce() {
        startRoute()
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 2))
        frame(0x5201, tlv(1, 2))
        refresh()
        assertFalse(HongqiNavigation.isNavigating())
        assertFalse(get<Boolean>("ownsMap"))
        assertEquals(listOf(2, 4, 0), instrument.mapLoads)
        assertEquals(listOf(1, 0), control.visibility)
    }

    @Test fun disconnectClearsTheMapAndCachedRouteEvenWhenHintsAreAlreadyMissing() {
        startRoute()
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 2))
        AutunnNavigationOutputs.endNow()
        apply()
        assertFalse(route.isRouteActive())
        assertFalse(HongqiNavigation.isNavigating())
        assertFalse(get<Boolean>("ownsMap"))
        frame(0x5202, tlv(1, 0, 2) + tlv(3, 2))
        assertEquals(listOf(1, 0), control.visibility)
    }

    @Test fun aUserHiddenMapStaysHiddenWhenManeuverDetailsRecover() {
        startRoute()
        HongqiNavigation.setMapShown(app, false)
        apply()
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 2))
        frame(0x5202, tlv(1, 0, 2) + tlv(3, 2))
        assertTrue(HongqiNavigation.isNavigating())
        assertFalse(get<Boolean>("ownsMap"))
        assertEquals(listOf(1, 0), control.visibility)
    }

    @Test fun thePresentationStaysVisibleThroughAManeuverGapAndHidesOnRouteEnd() {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp")
        val presentation = Presentation(app, app.getSystemService(DisplayManager::class.java).getDisplay(id))
        try {
            HongqiNavigation.preparePresentation(presentation)
            presentation.show()
            HongqiNavigation.streamActive(presentation, true)
            drainWriter()
            startRoute()
            shadowOf(Looper.getMainLooper()).idle()
            drainWriter()
            assertEquals(1f, presentation.window!!.attributes.alpha, 0f)
            frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 2))
            shadowOf(Looper.getMainLooper()).idle()
            drainWriter()
            assertEquals(1f, presentation.window!!.attributes.alpha, 0f)
            assertEquals(listOf(1), control.visibility)
            frame(0x5201, tlv(1, 0))
            shadowOf(Looper.getMainLooper()).idle()
            drainWriter()
            assertEquals(0f, presentation.window!!.attributes.alpha, 0f)
            assertEquals(listOf(1, 0), control.visibility)
        } finally {
            HongqiNavigation.presentationStopped(presentation)
            presentation.dismiss()
            drainWriter()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    private fun startRoute() {
        frame(0x5202, tlv(1, 0, 1) + tlv(3, 1))
        frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 1))
    }
    private fun refresh() { AutunnNavigationOutputs.refreshTurnOverlay(); apply() }
    private fun frame(id: Int, bytes: ByteArray) { AutunnNavigationOutputs.onFrame(Iap2Frame(id, bytes)); apply() }
    private fun tlv(id: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (id ushr 8).toByte(), id.toByte()) + bytes.map(Int::toByte).toByteArray()
    private fun drainWriter() { get<ExecutorService>("WRITER").submit {}.get(5, TimeUnit.SECONDS) }
    private fun apply() { HongqiNavigation::class.java.getDeclaredMethod("apply").apply { isAccessible = true }.invoke(null) }
    private fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(HongqiNavigation::class.java, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getStaticField(HongqiNavigation::class.java, name)

    private class Module(private val descriptor: String) : Binder() {
        val mapLoads = mutableListOf<Int>()
        val visibility = mutableListOf<Int>()
        val navigationStatus = mutableListOf<Int>()
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            if (code == 10) { data.readInt(); data.readInt(); mapLoads += data.readInt() }
            if (code == 69) visibility += data.readInt()
            if (code == 5) navigationStatus += data.readInt()
            reply!!.writeNoException()
            reply.writeInt(0)
            return true
        }
    }
}
