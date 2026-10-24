package com.shilapi.xcertplay.hud

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.view.Display
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
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiUltraDisplayOutputTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val instrument = Module("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
    private val control = Module("ltd.qisi.adapterproxy.ICarSystemControlManager")
    private lateinit var presentation: Presentation
    private var lease: HongqiNavigation.UltraPresentationLease? = null
    private var yielded = 0

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setName("Cluster-Display")
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        presentation = Presentation(app, display)
        set("context", app)
        set("bound", true)
        set("instrument", instrument)
        set("control", control)
        set("hud", null)
        set("guidance", null)
        set("ultraPresentation", null)
        set("ultraOemFocus", 1)
        set("exitListener", HongqiNavigationExitListener(control) {})
        for (name in listOf("routeActive", "ownsMap", "ownsHud", "ownsInstrumentGuidance",
            "replayMap", "cleanupPending", "mapReady", "mapRecoveryExpired")) set(name, false)
        set("mapLoadStep", 0)
        set("mapInterfacesReady", true)
    }

    @After fun cleanup() {
        lease?.close()
        settle()
        presentation.dismiss()
        settle()
        get<Handler>("MAIN").removeCallbacksAndMessages(null)
    }

    @Test fun ultraUsesTheExistingFactorySequenceWithoutFakingANavigationRoute() {
        acquire()
        lease!!.setRequested(true)
        settle()
        assertEquals(listOf(2, 4), instrument.mapLoads)
        assertEquals(listOf(1), control.fullscreen)
        assertEquals(1f, presentation.window!!.attributes.alpha)
        assertFalse(HongqiNavigation.isNavigating())
        lease!!.setRequested(false)
        settle()
        assertEquals(listOf(2, 4, 0), instrument.mapLoads)
        assertEquals(listOf(1, 0), control.fullscreen)
        assertEquals(0f, presentation.window!!.attributes.alpha)
    }

    @Test fun factoryHardQuitLatchesYieldAndARepeatedReadySignalCannotReacquire() {
        acquire()
        lease!!.setRequested(true)
        settle()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ltd.qisi.adapterproxy.listener.IVehicleSystemControlListener")
            data.writeString("HardKeyQuit")
            get<HongqiNavigationExitListener>("exitListener").transact(15, data, reply, 0)
        } finally { data.recycle(); reply.recycle() }
        settle()
        assertTrue(lease!!.isPreempted)
        assertEquals(1, yielded)
        assertEquals(0, control.fullscreen.last())
        lease!!.setRequested(true)
        HongqiNavigation.onUltraOemViewFocus(1)
        settle()
        assertEquals(listOf(1, 0), control.fullscreen)
    }

    @Test fun transientOemPriorityYieldsAndAConfirmedGainAllowsThePolicyToRequestAgain() {
        acquire()
        lease!!.setRequested(true)
        settle()
        HongqiNavigation.onUltraOemViewFocus(-2)
        settle()
        assertTrue(lease!!.isPreempted)
        assertEquals(0f, presentation.window!!.attributes.alpha)
        assertEquals(listOf(1, 0), control.fullscreen)
        HongqiNavigation.onUltraOemViewFocus(1)
        settle()
        assertFalse(lease!!.isPreempted)
        // A GAIN alone cannot bypass the session's fresh-vehicle policy.
        assertEquals(listOf(1, 0), control.fullscreen)
        lease!!.setRequested(true)
        settle()
        assertEquals(listOf(1, 0, 1), control.fullscreen)
    }

    @Test fun closingRestoresTheClassicMapRouteAndAnOldLeaseCannotHideANewLease() {
        HongqiNavigation.preparePresentation(presentation)
        presentation.show()
        HongqiNavigation.streamActive(presentation, true)
        set("routeActive", true)
        set("mapReady", true)
        lease = HongqiNavigation.acquireUltraPresentation(app, presentation) { yielded++ }
        settle()
        lease!!.setRequested(true)
        settle()
        val old = lease!!
        lease = HongqiNavigation.acquireUltraPresentation(app, presentation) { yielded++ }
        lease!!.setRequested(true)
        settle()
        old.close()
        settle()
        assertEquals(1f, presentation.window!!.attributes.alpha)
        lease!!.close()
        settle()
        assertTrue(HongqiNavigation.isNavigating())
        assertTrue(get("ownsMap"))
    }

    @Test fun anExistingFactoryPriorityLossCannotBeForgottenByOpeningANewLease() {
        HongqiNavigation.onUltraOemViewFocus(-2)
        settle()
        acquire()
        assertTrue(lease!!.isPreempted)
        lease!!.setRequested(true)
        settle()
        assertTrue(control.fullscreen.isEmpty())
        HongqiNavigation.onUltraOemViewFocus(1)
        settle()
        assertFalse(lease!!.isPreempted)
        lease!!.setRequested(true)
        settle()
        assertEquals(listOf(1), control.fullscreen)
    }

    @Test fun factoryPriorityLossInsideTheEntrySequencePreventsTheFinalLoadCommand() {
        acquire()
        control.afterFullscreen = { if (it == 1) HongqiNavigation.onUltraOemViewFocus(-2) }
        lease!!.setRequested(true)
        settle()
        assertFalse(instrument.mapLoads.contains(4))
        assertEquals(listOf(2, 0), instrument.mapLoads)
        assertEquals(listOf(1, 0), control.fullscreen)
        assertEquals(0f, presentation.window!!.attributes.alpha)
    }

    private fun acquire() {
        lease = HongqiNavigation.acquireUltraPresentation(app, presentation) { yielded++ }
        presentation.show()
        settle()
        instrument.mapLoads.clear()
        control.fullscreen.clear()
    }

    private fun settle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            get<ExecutorService>("WRITER").submit {}.get(3, TimeUnit.SECONDS)
        }
    }

    private fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(HongqiNavigation::class.java, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getStaticField(HongqiNavigation::class.java, name)

    private class Module(private val token: String) : Binder() {
        val mapLoads = mutableListOf<Int>()
        val fullscreen = mutableListOf<Int>()
        var afterFullscreen: ((Int) -> Unit)? = null
        init { attachInterface(null, token) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(token)
            if (code == 10 && token.endsWith("InstrumentRelatedManager")) {
                assertEquals(1, data.readInt())
                assertEquals(0, data.readInt())
                mapLoads += data.readInt()
            } else if (code == 69) {
                fullscreen += data.readInt()
                assertEquals(0, data.readInt())
                afterFullscreen?.invoke(fullscreen.last())
            }
            reply!!.writeNoException()
            if (code != 64 && code != 65) reply.writeInt(0)
            return true
        }
    }
}
