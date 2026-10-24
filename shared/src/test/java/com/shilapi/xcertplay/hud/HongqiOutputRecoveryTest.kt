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
class HongqiOutputRecoveryTest {
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
        set("mapLoadStep", 0)
        set("mapRecoveryExpired", false)
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

    @Test fun hudBusinessRejectionKeepsHealthyInstrumentModulesAndMode() {
        startRoute()
        drainWriter()
        assertEquals(listOf(2, 4), instrument.mapLoads)
        hud.failOnce = 70
        HongqiNavigation::class.java.getDeclaredMethod("enqueue").apply { isAccessible = true }.invoke(null)
        drainWriter()
        assertTrue(instrument.isBinderAlive)
        assertTrue(control.isBinderAlive)
        assertTrue(hud.isBinderAlive)
        assertTrue(HongqiNavigation.isNavigating())
        assertTrue(get<Boolean>("mapInterfacesReady"))
        assertSame(instrument, get<Any?>("instrument"))
        assertSame(control, get<Any?>("control"))
        apply()
        assertEquals(listOf(2, 4), instrument.mapLoads)
        assertEquals(listOf(1), control.visibility)
    }

    @Test fun aDeadHudModuleDoesNotDiscardTheHealthyMapEvenBeforeLivenessFlagChanges() {
        startRoute()
        hud.transportDead = true
        apply()
        assertNull(get<Any?>("hud"))
        assertSame(instrument, get<Any?>("instrument"))
        assertSame(control, get<Any?>("control"))
        assertTrue(get<Boolean>("mapInterfacesReady"))
        assertEquals(listOf(2, 4), instrument.mapLoads)
    }

    @Test fun aRejectedModeCommandDoesNotRepeatPreviouslyAcknowledgedLoad() {
        control.failOnce = 69
        startRoute()
        apply()
        assertEquals(listOf(2, 4), instrument.mapLoads)
        assertTrue(get<Boolean>("ownsMap"))
    }

    @Test fun temporaryStreamLossRecoversPixelsWithoutReenteringNativeMode() {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp")
        val presentation = Presentation(app, app.getSystemService(DisplayManager::class.java).getDisplay(id))
        try {
            HongqiNavigation.preparePresentation(presentation)
            presentation.show()
            HongqiNavigation.streamActive(presentation, true)
            drainWriter()
            startRoute()
            drainWriter()
            assertEquals(listOf(2, 4), instrument.mapLoads)
            HongqiNavigation.streamActive(presentation, false)
            drainWriter()
            assertTrue(HongqiNavigation.isNavigating())
            assertEquals(listOf(1), control.visibility)
            assertEquals(listOf(2, 4), instrument.mapLoads)
            HongqiNavigation.streamActive(presentation, true)
            drainWriter()
            assertEquals(listOf(1), control.visibility)
            assertEquals(listOf(2, 4), instrument.mapLoads)
            assertTrue(HongqiNavigation.isNavigating())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(11))
            drainWriter()
            assertEquals(listOf(1), control.visibility)
            HongqiNavigation.streamActive(presentation, false)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(11))
            drainWriter()
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
        var failOnce: Int? = null
        var transportDead = false
        val mapLoads = mutableListOf<Int>()
        val visibility = mutableListOf<Int>()
        val navigationStatus = mutableListOf<Int>()
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            if (transportDead) throw android.os.DeadObjectException()
            if (code == 10) { data.readInt(); data.readInt(); mapLoads += data.readInt() }
            if (code == 69) visibility += data.readInt()
            if (code == 5) navigationStatus += data.readInt()
            reply!!.writeNoException()
            if (failOnce == code) { failOnce = null; reply.writeInt(-2) } else reply.writeInt(0)
            return true
        }
    }
}
