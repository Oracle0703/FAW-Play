package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiParkedStateTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val serviceName = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
    private val gear = GearService()
    private var source: HongqiParkedState? = null

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        shadowOf(app).setComponentNameAndServiceForBindService(serviceName, Client(gear))
    }

    @After fun close() {
        source?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun gearIsReadWithoutLocationOrSpeedSubscriptionsAndOnlyReadTransactionsAreUsed() {
        val monitor = start()
        assertTrue(monitor.parked() == true)
        gear.value = 3
        poll(monitor)
        assertEquals(false, monitor.parked())
        gear.value = 0
        poll(monitor)
        assertTrue(monitor.parked() == true)
        assertTrue(gear.transactions.isNotEmpty())
        assertEquals(setOf(6), gear.transactions.toSet())
    }

    @Test fun unreadableGearAndDisconnectedServiceRevokeParkImmediately() {
        val monitor = start()
        gear.value = 99
        poll(monitor)
        assertNull(monitor.parked())
        gear.value = 0
        poll(monitor)
        assertTrue(monitor.parked() == true)
        val connection = ReflectionHelpers.getField<ServiceConnection>(monitor, "connection")
        connection.onServiceDisconnected(serviceName)
        assertNull(monitor.parked())
    }

    @Test fun binderErrorsCannotKeepTheLastParkReading() {
        val monitor = start()
        gear.fail = true
        poll(monitor)
        assertNull(monitor.parked())
    }

    @Test fun closingReleasesTheBindingAndCannotRestartTheMonitor() {
        val monitor = start()
        monitor.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(monitor.parked())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        monitor.start()
        assertNull(monitor.parked())
    }

    @Test fun aDisconnectedHandshakeCannotPublishTheOldService() {
        val monitor = start()
        val connection = ReflectionHelpers.getField<ServiceConnection>(monitor, "connection")
        connection.onServiceConnected(serviceName, Client(gear))
        connection.onServiceDisconnected(serviceName)
        val worker = ReflectionHelpers.getField<Handler>(monitor, "worker")
        shadowOf(worker.looper).idle()
        poll(monitor)
        assertNull(monitor.parked())
    }

    @Test fun unsupportedAndroidPhoneDoesNotBindOrAdvertiseParkingVideo() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        source = HongqiParkedState(app).also { it.start() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(source!!.parked())
        assertFalse(AutunnOutputSettings.videoWhileParkedActive(app))
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test fun verifiedHongqiEnablesVideoWithoutTheObsoleteOtherVendorProbe() {
        AutunnOutputSettings.setVideoWhileParked(app, false)
        assertTrue(AutunnOutputSettings.videoWhileParkedActive(app))
    }

    private fun start(): HongqiParkedState {
        val monitor = HongqiParkedState(app)
        source = monitor
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        val worker = ReflectionHelpers.getField<Handler>(monitor, "worker")
        shadowOf(worker.looper).idle()
        poll(monitor)
        return monitor
    }

    private fun poll(monitor: HongqiParkedState) {
        val worker = ReflectionHelpers.getField<Handler>(monitor, "worker")
        shadowOf(worker.looper).idleFor(Duration.ofMillis(100))
    }

    private class Client(private val gear: GearService) : Binder() {
        init { attachInterface(null, "ltd.qisi.adapterproxy.IVehicleRemoteClienter") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("ltd.qisi.adapterproxy.IVehicleRemoteClienter")
            assertEquals(1, code)
            assertEquals(15, data.readInt())
            reply!!.writeNoException()
            reply.writeStrongBinder(gear)
            return true
        }
    }

    private class GearService : Binder() {
        var value = 0
        var fail = false
        val transactions = mutableListOf<Int>()
        init { attachInterface(null, "ltd.qisi.adapterproxy.ICarVehicleStateManager") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("ltd.qisi.adapterproxy.ICarVehicleStateManager")
            transactions += code
            assertEquals(6, code)
            if (fail) throw RemoteException("injected gear failure")
            reply!!.writeNoException()
            reply.writeInt(value)
            return true
        }
    }
}
