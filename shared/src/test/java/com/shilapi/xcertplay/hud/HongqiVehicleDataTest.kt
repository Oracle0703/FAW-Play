package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiVehicleDataTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val component = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
    private val fixture = VehicleReadOnlyFixture()
    private val leases = mutableListOf<Closeable>()

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3212")
        val info = PackageInfo().apply {
            packageName = "ltd.qisi.adapterservice"
            @Suppress("DEPRECATION")
            versionCode = 3167
            versionName = "20240905-114725"
            applicationInfo = ApplicationInfo().apply { packageName = "ltd.qisi.adapterservice" }
        }
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app).setComponentNameAndServiceForBindService(component, fixture.root)
    }

    @After fun cleanup() {
        leases.forEach(Closeable::close)
        settle()
        assertEquals(HongqiVehicleConnectionState.STOPPED, HongqiVehicleData.snapshot().status)
    }

    @Test fun multiplePageLeasesShareOneBindingAndOnlyTheLastCloseStopsPolling() {
        val first = acquire()
        val second = acquire()
        assertEquals(HongqiVehicleConnectionState.READY, HongqiVehicleData.snapshot().status)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        first.close()
        first.close()
        settle()
        assertEquals(HongqiVehicleConnectionState.READY, HongqiVehicleData.snapshot().status)
        assertTrue(shadowOf(app).unboundServiceConnections.isEmpty())
        second.close()
        settle()
        assertEquals(HongqiVehicleConnectionState.STOPPED, HongqiVehicleData.snapshot().status)
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        val completedCalls = fixture.calls.size
        shadowOf(worker().looper).idleFor(Duration.ofSeconds(10))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(completedCalls, fixture.calls.size)
    }

    @Test fun snapshotAndDiagnosticAccessNeverPerformAdditionalBinderWork() {
        acquire()
        val completedCalls = fixture.calls.size
        repeat(20) {
            assertEquals(HongqiVehicleConnectionState.READY, HongqiVehicleData.snapshot().status)
            val diagnostic = HongqiVehicleData.diagnosticReport()
            assertTrue(diagnostic.contains("mode=explicit-control"))
            assertTrue(diagnostic.contains("AMBIENT_TEMPERATURE: raw=-5.5"))
        }
        assertEquals(completedCalls, fixture.calls.size)
    }

    @Test fun aCompletedOldReadQueuedForMainCannotReviveADisconnectedSession() {
        acquire()
        val connection = currentConnection()
        connection.onServiceConnected(component, fixture.root)
        shadowOf(worker().looper).idle() // The complete snapshot is queued for main, not published yet.
        connection.onServiceDisconnected(component)
        assertEquals(HongqiVehicleConnectionState.UNAVAILABLE, HongqiVehicleData.snapshot().status)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HongqiVehicleConnectionState.UNAVAILABLE, HongqiVehicleData.snapshot().status)
        assertTrue(HongqiVehicleData.snapshot().readings.isEmpty())
    }

    @Test fun aCompletedOldReadQueuedForMainCannotReviveTheLastClosedLease() {
        val lease = acquire()
        currentConnection().onServiceConnected(component, fixture.root)
        shadowOf(worker().looper).idle()
        lease.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HongqiVehicleConnectionState.STOPPED, HongqiVehicleData.snapshot().status)
        assertTrue(HongqiVehicleData.snapshot().readings.isEmpty())
        acquire()
        assertEquals(HongqiVehicleConnectionState.READY, HongqiVehicleData.snapshot().status)
    }

    @Test fun unverifiedFirmwareProducesNoBindingOrVehicleTransaction() {
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3222")
        acquire()
        assertEquals(HongqiVehicleConnectionState.UNSUPPORTED, HongqiVehicleData.snapshot().status)
        assertEquals("firmware-not-verified", HongqiVehicleData.snapshot().reason)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertTrue(fixture.rootTransactions.isEmpty())
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun replacingTheOemPackageCannotReuseAnAlreadyVerifiedTransactionContract() {
        acquire()
        val calls = fixture.calls.size
        val queries = fixture.moduleQueries.size
        val pkg = app.packageManager.getPackageInfo("ltd.qisi.adapterservice", 0)
        pkg.versionName = "unverified-update"
        shadowOf(app.packageManager).installPackage(pkg)
        currentConnection().onServiceConnected(component, fixture.root)
        settle()
        shadowOf(worker().looper).idleFor(Duration.ofSeconds(10))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        settle()
        assertEquals(HongqiVehicleConnectionState.UNSUPPORTED, HongqiVehicleData.snapshot().status)
        assertEquals(calls, fixture.calls.size)
        assertEquals(queries, fixture.moduleQueries.size)
        assertTrue(HongqiVehicleData.snapshot().readings.isEmpty())
    }

    private fun acquire(): Closeable = HongqiVehicleData.acquire(app).also { leases += it; settle() }

    private fun currentConnection(): ServiceConnection = ReflectionHelpers.getField(HongqiVehicleData, "session")

    private fun worker(): Handler =
        ReflectionHelpers.getField<Lazy<Handler>>(HongqiVehicleData, "worker\$delegate").value

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(worker().looper).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
