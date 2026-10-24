package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
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
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiNavigationRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val serviceName = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
    private val connection get() = get<ServiceConnection>("CONNECTION")
    private val client = Client()

    @Before fun prepareVehicle() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        set("context", app)
        set("bound", true)
        set("guidance", ClusterTurnGuidance(8, 0, 150, "测试道路"))
        listOf("client", "hud", "instrument", "control", "exitListener", "lastInstrumentGuidance")
            .forEach { set(it, null) }
        listOf("ownsHud", "ownsMap", "ownsInstrumentGuidance", "cleanupPending", "mapReady",
            "mapInterfacesReady", "replayMap", "replayInstrumentGuidance").forEach { set(it, false) }
    }

    @After fun stopWorkers() {
        drainWriter()
        get<Handler>("MAIN").removeCallbacksAndMessages(null)
    }

    @Test fun modulesThatStartAfterTheServiceRecoverWithoutAnotherCarPlaySessionOrBinding() {
        connection.onServiceConnected(serviceName, client)
        drainWriter()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(get("mapInterfacesReady"))
        assertFalse(get("ownsHud"))
        assertEquals(listOf(23, 8, 13), client.queries)

        client.available += setOf(23, 8, 13)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        drainWriter()
        assertTrue(get("mapInterfacesReady"))
        assertTrue(get("ownsHud"))
        assertTrue(get("ownsInstrumentGuidance"))
        assertTrue(get("bound"))
        assertEquals(listOf(5, 70, 6), client.hud.transactions)
        assertEquals(listOf(5, 6), client.instrument.transactions)

        // The retained map stream can activate as soon as its presentation is ready.
        set("mapReady", true)
        apply()
        assertTrue(get("ownsMap"))
        assertEquals(listOf(64, 69), client.control.transactions)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        drainWriter()
        assertEquals(listOf(23, 8, 13, 23, 8, 13), client.queries)
    }

    @Test fun aLateMapModuleDoesNotInterruptTheWorkingHudAndOnlyMissingModulesAreQueriedAgain() {
        client.available += setOf(23, 8)
        connection.onServiceConnected(serviceName, client)
        drainWriter()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(get("ownsHud"))
        assertTrue(get("ownsInstrumentGuidance"))
        assertFalse(get("mapInterfacesReady"))

        client.available += 13
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        drainWriter()
        assertEquals(listOf(23, 8, 13, 13), client.queries)
        assertTrue(get("mapInterfacesReady"))
        assertEquals(1, client.instrument.transactions.count { it == 5 })
        assertEquals(1, client.instrument.transactions.count { it == 6 })
        assertEquals(2, client.hud.transactions.count { it == 70 })
    }

    @Test fun disconnectDiscardsTheOldClientSoItsPendingModuleRetryCannotRestoreOldState() {
        connection.onServiceConnected(serviceName, client)
        drainWriter()
        shadowOf(Looper.getMainLooper()).idle()
        connection.onServiceDisconnected(serviceName)
        drainWriter()
        assertNull(get<Any?>("client"))
        assertFalse(get("mapInterfacesReady"))

        client.available += setOf(23, 8, 13)
        // Exercise an already dispatched timer as well as cancellation on MAIN.
        get<Runnable>("RETRY_MODULES").run()
        drainWriter()
        assertEquals(listOf(23, 8, 13), client.queries)
        assertFalse(get("ownsHud"))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aReplacementServiceReplaysAnOwnedMapAndHudAndRouteEndStillClearsThem() {
        client.available += setOf(23, 8, 13)
        connection.onServiceConnected(serviceName, client)
        drainWriter()
        set("mapReady", true)
        apply()
        assertTrue(get("ownsMap"))
        val replacement = Client().apply { available += setOf(23, 8, 13) }
        connection.onServiceConnected(serviceName, replacement)
        drainWriter()
        assertEquals(listOf(5, 10, 10, 6), replacement.instrument.transactions)
        assertEquals(listOf(64, 69), replacement.control.transactions)
        assertEquals(listOf(5, 70, 6), replacement.hud.transactions)

        set("guidance", null)
        apply()
        assertFalse(get("ownsMap"))
        assertFalse(get("ownsHud"))
        assertFalse(get("ownsInstrumentGuidance"))
        assertFalse(get("cleanupPending"))
    }

    private fun drainWriter() {
        get<ExecutorService>("WRITER").submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun apply() {
        HongqiNavigation::class.java.getDeclaredMethod("apply").apply { isAccessible = true }.invoke(null)
    }

    private fun set(name: String, value: Any?) {
        ReflectionHelpers.setStaticField(HongqiNavigation::class.java, name, value)
        if (name == "guidance") ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "routeActive", value != null)
    }
    private fun <T> get(name: String): T = ReflectionHelpers.getStaticField(HongqiNavigation::class.java, name)

    private class Client : Binder() {
        val available = mutableSetOf<Int>()
        val queries = mutableListOf<Int>()
        val hud = Module("ltd.qisi.adapterproxy.ICarHUDManager")
        val instrument = Module("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
        val control = Module("ltd.qisi.adapterproxy.ICarSystemControlManager")
        init { attachInterface(null, "ltd.qisi.adapterproxy.IVehicleRemoteClienter") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("ltd.qisi.adapterproxy.IVehicleRemoteClienter")
            assertEquals(1, code)
            val module = data.readInt().also { queries += it }
            reply!!.writeNoException()
            reply.writeStrongBinder(if (module !in available) null else when (module) {
                23 -> hud
                8 -> instrument
                13 -> control
                else -> error("Unexpected module $module")
            })
            return true
        }
    }

    private class Module(private val descriptor: String) : Binder() {
        val transactions = mutableListOf<Int>()
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            transactions += code
            reply!!.writeNoException()
            if (code != 64 && code != 65) reply.writeInt(0)
            return true
        }
    }
}
