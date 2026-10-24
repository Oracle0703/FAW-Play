package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerCloseTest {
    private val airplay = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))

    private fun controller(
        context: Context = RuntimeEnvironment.getApplication(),
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
    ) = CarPlayController(context,
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                manufacturer = "test", serialNumber = "test", firmwareVersion = "1",
                hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        airplay, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), listener,
        object : AirPlayMediaHandler {}, {})

    @Test fun anOldSessionEndCannotFailARecoveryStartedByItsUiCallback() {
        lateinit var recovery: WirelessFastReconnect<AirPlaySession>
        val controller = controller(listener = object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) { recovery.begin("known-paired-phone") }
        })
        recovery = ReflectionHelpers.getField(controller, "fastReconnect")
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants.single { it.toString() == "WIRELESS" })
        val generation = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "wirelessGeneration")
        generation.set(1)
        val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})
        val method = controller.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
            FirstTcpWatchdog::class.java).apply { isAccessible = true }
        val callbacks = method.invoke(controller, 1, watchdog) as AirPlaySessionListener
        val session = AirPlaySession(Socket(), airplay, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        ReflectionHelpers.setField(controller, "activeSession", session)
        try {
            callbacks.onSessionEnded(session)
            assertNotNull("The next attempt must survive the old end callback", recovery.attempt())
            assertEquals("No spurious Bluetooth fallback should revoke this generation", 1, generation.get())
        } finally { session.close(); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun vehicleStopImmediatelyRevokesRecoveryAdmissionBeforeSlowNetworkTeardown() {
        val controller = controller()
        val recovery = ReflectionHelpers.getField<WirelessFastReconnect<AirPlaySession>>(controller, "fastReconnect")
        val attempt = recovery.begin("known-paired-phone")
        try {
            controller.pauseMediaForVehicleStop()
            assertNull(recovery.attempt())
            assertFalse(recovery.fail(attempt))
        } finally { controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun closeCompletionWaitsForTheActualHotspotRelease() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val results = LinkedBlockingQueue<CarPlayController.CloseResult>()
        val controller = controller()
        ReflectionHelpers.setField(controller, "hotspot", object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("not started")
            override fun close() { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        })
        controller.whenClosed(results::add)
        try {
            controller.close()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(controller.awaitClosed(50))
            assertNull(results.poll())
            controller.close() // An in-flight close remains one attempt.
            resume.countDown()
            assertTrue(results.poll(3, TimeUnit.SECONDS)!!.resourcesReleased)
            assertTrue(controller.awaitClosed(50))
            assertNull(results.poll())
        } finally { resume.countDown(); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun failedReleaseIsReportedAndItsHandleCanBeRetried() {
        val fail = AtomicBoolean(true)
        val results = LinkedBlockingQueue<CarPlayController.CloseResult>()
        val controller = controller()
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("not started")
            override fun close() { if (fail.get()) throw IOException("radio cleanup rejected") }
        }
        ReflectionHelpers.setField(controller, "hotspot", hotspot)
        try {
            controller.close()
            controller.whenClosed(results::add)
            val failed = results.poll(3, TimeUnit.SECONDS)!!
            assertFalse(failed.resourcesReleased)
            assertTrue(failed.failures.any { it.startsWith("wireless hotspot:") })
            assertFalse(controller.awaitClosed(50))
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail.set(false)
            controller.close()
            controller.whenClosed(results::add)
            assertTrue(results.poll(3, TimeUnit.SECONDS)!!.resourcesReleased)
            assertTrue(controller.awaitClosed(50))
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
        } finally { fail.set(false); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun internalWirelessCleanupFailureCannotBeMistakenForAReleasedStack() {
        val controller = controller()
        val fail = AtomicBoolean(true)
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("not started")
            override fun close() { if (fail.get()) throw IOException("radio cleanup rejected") }
        }
        ReflectionHelpers.setField(controller, "hotspot", hotspot)
        val cleanup = controller.javaClass.declaredMethods.single { it.name == "closeWirelessStack" }
            .apply { isAccessible = true }
        val retryMode = cleanup.parameterTypes[2].enumConstants.first()
        fun released(result: Any): Boolean = result.javaClass.getDeclaredMethod("getResourcesReleased")
            .apply { isAccessible = true }.invoke(result) as Boolean
        try {
            assertFalse(released(cleanup.invoke(controller, null, null, retryMode)))
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail.set(false)
            assertTrue(released(cleanup.invoke(controller, null, null, retryMode)))
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
        } finally { fail.set(false); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun requestingWirelessRecoveryDoesNotWaitForTheOldResourceLock() {
        val controller = controller()
        val lock = ReflectionHelpers.getField<Any>(controller, "wirelessResourceLock")
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val holder = Thread({ synchronized(lock) { entered.countDown(); resume.await() } }).apply { start() }
        val caller = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            caller.submit { ReflectionHelpers.callInstanceMethod<Any?>(controller, "restartWireless") }
                .get(500, TimeUnit.MILLISECONDS)
            assertEquals(1, ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "wirelessGeneration").get())
            controller.close()
        } finally {
            controller.close()
            resume.countDown()
            holder.join(1_000)
            caller.shutdownNow()
            assertTrue(controller.awaitClosed(3_000))
        }
    }

    @Test fun closingSocketDoesNotDeclareAnUnfinishedRfcommConnectWorkerReleased() {
        val controller = controller()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Thread({ entered.countDown(); resume.await() }, "test-rfcomm-connect").apply {
            isDaemon = true
            start()
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        ReflectionHelpers.setField(controller, "rfcommConnectWorker", worker)
        try {
            controller.close()
            assertFalse(controller.awaitClosed(50))
            assertSame(worker, ReflectionHelpers.getField(controller, "rfcommConnectWorker"))
            resume.countDown()
            worker.join(1_000)
            // The original close either observes the exit or reports a real failure that can
            // be retried. Both outcomes retain the same completion barrier.
            controller.awaitClosed(3_000)
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
        } finally { resume.countDown(); worker.join(1_000); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun workerThatIsStillRunningCannotPassTheOldTwoSecondTimeout() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val controller = controller()
        val worker = ReflectionHelpers.getField<ExecutorService>(controller, "executor")
        worker.execute {
            entered.countDown()
            while (resume.count > 0) {
                try { resume.await() }
                catch (_: InterruptedException) { /* Simulate a platform operation still returning. */ }
            }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            controller.close()
            val result = java.util.concurrent.atomic.AtomicReference<CarPlayController.CloseResult>()
            controller.whenClosed { result.set(it); completed.countDown() }
            assertTrue("A bounded attempt must report an unfinished worker", completed.await(3, TimeUnit.SECONDS))
            assertFalse(result.get().resourcesReleased)
            assertFalse(controller.awaitClosed(10))
            assertSame(worker, ReflectionHelpers.getField(controller, "executor"))
            resume.countDown()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
        } finally { resume.countDown(); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun theCarPlayApplicationIconOpensTheVehicleLauncherWithoutEndingTheSession() {
        val app = RuntimeEnvironment.getApplication()
        val launches = mutableListOf<Intent>()
        val homeComponent = ComponentName(app.packageName, "autunn.Home")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(homeComponent)
            addIntentFilterForActivity(homeComponent, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { launches.add(intent) }
        }
        var requested = 0
        val controller = controller(context, object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession) { requested++ }
        })
        val session = AirPlaySession(Socket(), airplay, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val callbacks = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        ReflectionHelpers.setField(controller, "activeSession", session)
        try {
            callbacks.onHostUiRequested(session)
            val home = launches.single()
            assertEquals(Intent.ACTION_MAIN, home.action)
            assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
            assertNull(home.component)
            assertNull(home.getStringExtra("page"))
            assertTrue(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(home.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
            assertSame(session, controller.currentActiveAirPlaySession())
            assertFalse(session.isClosed())
            val pendingResourceRequests = ReflectionHelpers.getField<MutableMap<*, *>>(session.resourceModes, "pending")
            assertTrue("The CarPlay Home action must retain screen resources", pendingResourceRequests.isEmpty())
            assertEquals(1, requested)
        } finally { session.close(); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun aClosedSessionsLateUiRequestCannotOpenHomeOrNotifyUi() {
        var homeLaunches = 0
        var requests = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { homeLaunches++ }
        }
        val controller = controller(context, object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession) { requests++ }
        })
        val session = AirPlaySession(Socket(), airplay, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val callbacks = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        ReflectionHelpers.setField(controller, "activeSession", session)
        try {
            // Model the retained identity between the early disconnect and slow end callback.
            session.close()
            assertTrue(session.isClosed())
            assertSame(session, ReflectionHelpers.getField<AirPlaySession>(controller, "activeSession"))
            callbacks.onHostUiRequested(session)
            assertEquals(0, homeLaunches)
            assertEquals(0, requests)
        } finally { session.close(); controller.close(); controller.awaitClosed(3_000) }
    }

    @Test fun callbacksFromTheClosedControllerCannotOpenHomeOrNotifyUi() {
        var homeLaunches = 0
        val events = mutableListOf<String>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { homeLaunches++ }
        }
        val controller = controller(context, object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession) { events.add("home") }
            override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) { events.add("device") }
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { events.add("command") }
            override fun onTransportError(message: String) { events.add("error") }
            override fun onSessionActive(session: AirPlaySession) { events.add("active") }
            override fun onSessionEnded(session: AirPlaySession) { events.add("ended") }
        })
        val session = AirPlaySession(Socket(), airplay, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val callbacks = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        ReflectionHelpers.setField(controller, "activeSession", session)
        try {
            controller.close()
            callbacks.onHostUiRequested(session)
            callbacks.onDeviceInfo(session, AirPlayDeviceInfo("test", "test", "test", "test"))
            callbacks.onCommand(session, "modesChanged", emptyMap())
            callbacks.onTransportError("late error")
            callbacks.onSessionActive(session)
            callbacks.onSessionEnded(session)
            assertEquals(0, homeLaunches)
            assertTrue(events.isEmpty())
            assertTrue(controller.awaitClosed(3_000))
        } finally { session.close(); controller.close(); controller.awaitClosed(3_000) }
    }
}
