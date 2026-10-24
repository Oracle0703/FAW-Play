package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Keep the real admission, retained listener, worker queue and recovery timer ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [
    WirelessFastReconnectOrderingTest.OwnedGroup::class,
    WirelessFastReconnectOrderingTest.AttachedService::class,
    WirelessFastReconnectOrderingTest.Discovery::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessFastReconnectOrderingTest {
    private val display = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)
    private val millis = AtomicLong()
    private val timer = ManualStartupTimer { millis.get() }
    private val recovery = WirelessFastReconnect<AirPlaySession> { millis.get() * 1_000_000 }
    private val listenerIdentity = AirPlayListenerIdentity(1)
    private val workerRelease = CountDownLatch(1)
    private val logs = CopyOnWriteArrayList<String>()
    private val uiEvents = CopyOnWriteArrayList<String>()
    private val statuses = CopyOnWriteArrayList<CarPlayStatus>()
    private val sessions = mutableListOf<AirPlaySession>()
    private val channels = mutableListOf<Iap2Session>()
    private lateinit var controller: CarPlayController
    private lateinit var executor: ExecutorService
    private lateinit var callbacks: AirPlaySessionListener
    private lateinit var proof: WirelessConnectionProof<AirPlaySession>

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val address = InetAddress.getByName("192.0.2.1")
        Boundary.info = WirelessHotspotInfo("DIRECT-owned", "test-password", Iap2WirelessSecurity.WPA_WPA2,
            6, 2437, "02:00:00:00:00:01", "p2p-test", address, "2.4 GHz",
            WirelessHotspotBackend.WIFI_P2P, listOf(address))
        Boundary.requests.set(0)
        Boundary.reuse = null
        Boundary.attached = null
        Boundary.addresses = null
        Boundary.beforeRequest = null
        Boundary.requestIo = null
        Boundary.offeredRequests.clear()
        Boundary.finishedRequests.clear()
        controller = CarPlayController(app,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = identification), display, AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { uiEvents += "active" }
                override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
                    if (screenType == 110) uiEvents += "main-frame"
                }
                override fun onDebugLog(message: String) { logs += message }
            }, object : AirPlayMediaHandler {}, statuses::add)
        val phase = controller.javaClass.declaredClasses.single { it.simpleName == "Phase" }
            .enumConstants!!.single { it.toString() == "WIRELESS" }
        ReflectionHelpers.setField(controller, "phase", phase)
        ReflectionHelpers.setField(controller, "started", true)
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
        ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(controller, "startupTimer").shutdownNow()
        ReflectionHelpers.setField(controller, "startupTimer", timer)
        ReflectionHelpers.setField(controller, "fastReconnect", recovery)
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(controller, "wirelessActiveReported").set(true)
        ReflectionHelpers.setField(controller, "mfiSession", MfiSession(object : MfiAuthenticator {
            override fun protocolMajor(): Int = error("No new MFi handshake is executed by this fixture")
            override fun readCertificate(maximumOutputLength: Int): ByteArray = error("Not used")
            override fun signChallenge(challenge: ByteArray): ByteArray = error("Not used")
        }, null))
        ReflectionHelpers.setField(controller, "hotspot", WifiP2pGroupManager(app))
        ReflectionHelpers.setField(controller, "vpnService", CarPlayVpnService())
        ReflectionHelpers.setField(controller, "bonjour", CarPlayBonjour(app, display,
            AirPlayIdentity.generate(), advertisedHost = "192.0.2.1"))
        ReflectionHelpers.setField(controller, "wirelessRuntimeIdentification", identification)
        ReflectionHelpers.setField(controller, "wirelessAirPlayEndpoint", Iap2WirelessCarPlayEndpoint(
            "DIRECT-owned", "test-password", 6, Iap2WirelessSecurity.WPA_WPA2,
            listOf("192.0.2.1"), 7000, "test", "test", "1"))
        val target = controller.javaClass.declaredClasses.single { it.simpleName == "WirelessRecoveryTarget" }
            .declaredConstructors.single().apply { isAccessible = true }
            .newInstance(1, "expected-pairing", "02:00:00:00:00:02", Boundary.info, listenerIdentity)
        ReflectionHelpers.setField(controller, "wirelessRecoveryTarget", target)
        proof = ReflectionHelpers.getField(controller, "wirelessConnectionProof")
        proof.begin(1) {}
        val watchdog = FirstTcpWatchdog(listenerIdentity, { _, _ -> {} }, {})
        ReflectionHelpers.setField(controller, "firstTcpWatchdog", watchdog)
        callbacks = call("wirelessSessionListener", 1, watchdog) as AirPlaySessionListener
        executor = ReflectionHelpers.getField(controller, "executor")
    }

    @After fun cleanup() {
        controller.close()
        workerRelease.countDown()
        assertTrue(controller.awaitClosed(3_000))
        channels.forEach { it.close() }
        sessions.forEach { it.close() }
        timer.shutdownNow()
    }

    @Test fun mainFrameBeforeRecordIsReconciledIntoTheSameSessionsFastRecoveryProof() {
        assertTrue(controller.tryReconnectWireless())
        executor.submit {}.get(2, TimeUnit.SECONDS)
        val phone = phone()
        ReflectionHelpers.setField(phone, "mainScreenToken", Any())
        phone.videoFrameRendered(110) // Decoder completes SETUP video before the control thread handles RECORD.
        assertTrue(phone.hasRenderedMainScreen())
        ready(phone, installRuntime(phone))
        callbacks.onSessionActive(phone)

        assertTrue("RECORD must reconcile this session's already-rendered current stream", proof.hasRenderedFrame(1, phone))
        assertNull("The same authenticated runtime and main frame already completed recovery", recovery.attempt())
        assertEquals("The background/UI must receive the reconciled frame after session activation",
            listOf("active", "main-frame"), uiEvents.toList())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(CarPlayStatus.WirelessActive, statuses.last())
        millis.set(20_001)
        timer.runDue()
        assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
    }

    @Test fun anExpiredQueuedPreparationCannotSendDiscoveryEvenWhenTimerDispatchIsDelayed() {
        occupyWorker()
        assertTrue(controller.tryReconnectWireless())
        millis.set(3_001)
        workerRelease.countDown()
        executor.submit {}.get(2, TimeUnit.SECONDS)

        assertEquals("An expired preparation cannot write a new reconnect request", 0, Boundary.requests.get())
        assertNull(recovery.attempt())
        assertEquals(1, logs.count { it.contains("path=bluetooth-fallback") })
    }

    @Test fun recordCannotReplayTheFrameOfAReplacedPrimaryStream() {
        assertTrue(controller.tryReconnectWireless())
        executor.submit {}.get(2, TimeUnit.SECONDS)
        val phone = phone()
        ReflectionHelpers.setField(phone, "mainScreenToken", Any())
        phone.videoFrameRendered(110)
        ReflectionHelpers.setField(phone, "mainScreenToken", Any()) // A new SETUP owns a new stream token.
        ready(phone, installRuntime(phone))
        callbacks.onSessionActive(phone)

        assertFalse(proof.hasRenderedFrame(1, phone))
        assertNotNull(recovery.attempt())
        assertEquals(listOf("active"), uiEvents.toList())
        phone.videoFrameRendered(110)
        assertNull(recovery.attempt())
    }

    @Test fun aQueuedRecoveryInstallsItsDeadlineBeforeTheBusyWorkerCanRunPreparation() {
        occupyWorker()
        assertTrue(controller.tryReconnectWireless())
        val phone = phone()
        assertTrue(callbacks.authorizeSession(phone))
        millis.set(3_001)
        assertFalse("An expired queued attempt must not admit SETUP or RECORD", callbacks.authorizeSession(phone))
        timer.runDue() // The real timer callback must revoke recovery even while the worker remains occupied.

        assertNull("A busy preparation worker cannot leave an expired attempt parked indefinitely", recovery.attempt())
        assertEquals(1, logs.count { it.contains("path=bluetooth-fallback") })
        assertTrue(ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get() > 1)
        assertEquals("The expired worker has not sent a discovery request", 0, Boundary.requests.get())
    }

    @Test fun runtimeReadyWhilePreparationIsQueuedSurvivesTheLaterPreparationTask() {
        occupyWorker()
        assertTrue(controller.tryReconnectWireless())
        val phone = phone()
        ready(phone, installRuntime(phone)) // Retained AirPlay listener is already live on the existing group.
        assertTrue(proof.isAuthenticated(1, phone))
        workerRelease.countDown()
        executor.submit {}.get(2, TimeUnit.SECONDS)

        assertTrue("Queued preparation must not erase a current runtime's completed authentication", proof.isAuthenticated(1, phone))
        callbacks.onSessionActive(phone)
        ReflectionHelpers.setField(phone, "mainScreenToken", Any())
        phone.videoFrameRendered(110)
        assertNull(recovery.attempt())
        millis.set(20_001)
        timer.runDue()
        assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
    }

    @Test fun completedProofDuringNetworkValidationCannotPublishOrOverwriteActiveStatus() {
        val validating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Boundary.addresses = { validating.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        try {
            occupyWorker()
            assertTrue(controller.tryReconnectWireless())
            val phone = phone()
            ready(phone, installRuntime(phone))
            callbacks.onSessionActive(phone)
            ReflectionHelpers.setField(phone, "mainScreenToken", Any())
            workerRelease.countDown()
            assertTrue(validating.await(2, TimeUnit.SECONDS))

            phone.videoFrameRendered(110)
            assertNull("A physical readiness check cannot hold back valid connection proof", recovery.attempt())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
            val completedStatusCount = statuses.size
            resume.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals(0, Boundary.requests.get())
            assertFalse(statuses.drop(completedStatusCount).contains(CarPlayStatus.ConnectingControl))
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
        } finally { resume.countDown() }
    }

    @Test fun completionAfterPublicationReservationStillRevokesBeforeBonjourIo() {
        val publishing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Boundary.beforeRequest = { publishing.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        try {
            occupyWorker()
            assertTrue(controller.tryReconnectWireless())
            val phone = phone()
            ready(phone, installRuntime(phone))
            callbacks.onSessionActive(phone)
            ReflectionHelpers.setField(phone, "mainScreenToken", Any())
            workerRelease.countDown()
            assertTrue(publishing.await(2, TimeUnit.SECONDS))

            phone.videoFrameRendered(110)
            assertNull(recovery.attempt())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
            resume.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals("The ownership callback must abort before discovery IO", 0, Boundary.requests.get())
            assertEquals(1, Boundary.finishedRequests.count { it == Boundary.offeredRequests.single() })
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
            assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
        } finally { resume.countDown() }
    }

    @Test fun slowGroupReadinessCannotHoldBackRecoveryDeadlineRevocation() {
        val checkingGroup = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Boundary.reuse = { checkingGroup.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(controller.tryReconnectWireless())
            assertTrue(checkingGroup.await(2, TimeUnit.SECONDS))
            millis.set(3_001)
            timer.runDue()

            assertNull("Deadline policy must revoke while the platform group check is still blocked", recovery.attempt())
            assertTrue(ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get() > 1)
            assertEquals(1, logs.count { it.contains("path=bluetooth-fallback") })
            assertEquals(0, Boundary.requests.get())
        } finally { resume.countDown() }
    }

    @Test fun proofDuringBlockedPublicationCompletesWithoutWaitingAndCannotTimeoutToBluetooth() {
        val publishing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Boundary.requestIo = { publishing.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        try {
            occupyWorker()
            assertTrue(controller.tryReconnectWireless())
            val phone = phone()
            ready(phone, installRuntime(phone))
            callbacks.onSessionActive(phone)
            ReflectionHelpers.setField(phone, "mainScreenToken", Any())
            workerRelease.countDown()
            assertTrue(publishing.await(2, TimeUnit.SECONDS))
            assertEquals(1, Boundary.requests.get())

            phone.videoFrameRendered(110)
            assertNull("A media callback must consume success without waiting for JmDNS IO", recovery.attempt())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
            millis.set(20_001)
            timer.runDue()
            assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
            assertEquals(1, ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get())
            assertEquals("Physical request cleanup waits for the publisher to leave its IO", 0,
                Boundary.finishedRequests.count { it == Boundary.offeredRequests.single() })

            resume.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, Boundary.finishedRequests.count { it == Boundary.offeredRequests.single() })
            assertNull(ReflectionHelpers.getField<Int?>(controller, "fastReconnectBonjourRequest"))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(CarPlayStatus.WirelessActive, statuses.last())
        } finally { resume.countDown() }
    }

    @Test fun aRecoveryBeginningBetweenRecordAdmissionAndAdoptionUsesThatAuthenticatedSession() {
        val recordWorker = Executors.newSingleThreadExecutor()
        val adoptionLock = ReflectionHelpers.getField<Any>(controller, "navigationFrameLock")
        try {
            occupyWorker()
            val phone = phone()
            ready(phone, installRuntime(phone))
            ReflectionHelpers.setField(phone, "mainScreenToken", Any())
            phone.videoFrameRendered(110)
            val record = synchronized(adoptionLock) {
                val record = recordWorker.submit { callbacks.onSessionActive(phone) }
                // The real wrapper activates its proof after admission, then the real base
                // waits for this navigation lock before it can publish activeSession.
                val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (synchronized(proof) {
                        ReflectionHelpers.getField<AirPlaySession?>(proof, "session") !== phone
                    }) {
                    if (System.nanoTime() >= admissionDeadline) fail("RECORD did not reach its real pre-adoption boundary")
                    Thread.yield()
                }
                assertNull(ReflectionHelpers.getField<AirPlaySession?>(controller, "activeSession"))
                assertTrue("In-flight RECORD must keep the controller available to the recovery caller", controller.tryReconnectWireless())
                assertNotNull(recovery.attempt())
                record
            }
            record.get(2, TimeUnit.SECONDS)

            assertSame(phone, ReflectionHelpers.getField<AirPlaySession?>(controller, "activeSession"))
            assertTrue(proof.isAuthenticated(1, phone))
            assertTrue(proof.hasRenderedFrame(1, phone))
            assertNull("The attempt begun after RECORD admission must activate its adopted matching session", recovery.attempt())
            millis.set(20_001)
            timer.runDue()
            assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
            assertEquals(0, Boundary.requests.get())
        } finally {
            workerRelease.countDown()
            recordWorker.shutdownNow()
        }
    }

    @Test fun aSessionAdoptedDuringRecoveryEligibilityKeepsItsControllerWithoutStartingAnAttempt() {
        val checkingAttachment = CountDownLatch(1)
        val resumeRecovery = CountDownLatch(1)
        val reconnectWorker = Executors.newSingleThreadExecutor()
        Boundary.attached = {
            checkingAttachment.countDown()
            check(resumeRecovery.await(5, TimeUnit.SECONDS))
        }
        try {
            occupyWorker()
            val phone = phone()
            ready(phone, installRuntime(phone))
            ReflectionHelpers.setField(phone, "mainScreenToken", Any())
            phone.videoFrameRendered(110)
            val reconnect = reconnectWorker.submit<Boolean> { controller.tryReconnectWireless() }
            assertTrue(checkingAttachment.await(2, TimeUnit.SECONDS))
            Boundary.attached = null
            callbacks.onSessionActive(phone)
            assertSame(phone, ReflectionHelpers.getField<AirPlaySession?>(controller, "activeSession"))

            resumeRecovery.countDown()
            assertTrue("The caller must retain a controller that adopted a live session during its eligibility check",
                reconnect.get(2, TimeUnit.SECONDS))
            assertNull("The begin commit must recheck the now-adopted session", recovery.attempt())
            millis.set(20_001)
            timer.runDue()
            assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
            assertEquals(1, ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get())
        } finally {
            Boundary.attached = null
            resumeRecovery.countDown()
            workerRelease.countDown()
            reconnectWorker.shutdownNow()
        }
    }

    private fun occupyWorker() {
        val occupied = CountDownLatch(1)
        executor.execute { occupied.countDown(); check(workerRelease.await(5, TimeUnit.SECONDS)) }
        assertTrue(occupied.await(2, TimeUnit.SECONDS))
    }

    private fun phone(): AirPlaySession = AirPlaySession(Socket(), display, AirPlayIdentity.generate(),
        PairingStore(), null, callbacks, object : AirPlayMediaHandler {}).also {
        val verifier = ReflectionHelpers.getField<Any>(it, "pairVerify")
        ReflectionHelpers.setField(verifier, "controllerId", "expected-pairing")
        sessions += it
    }

    private fun installRuntime(phone: AirPlaySession): Iap2Session = Iap2Session.openTunnel(IdleStream()).also {
        channels += it
        ReflectionHelpers.setField(controller, "wirelessTunnelChannel", it)
        ReflectionHelpers.setField(controller, "wirelessTunnelSession", phone)
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(controller, "wirelessTunnelReady").set(false)
    }

    private fun ready(phone: AirPlaySession, channel: Iap2Session) { call("onWirelessTunnelReady", 1, phone, channel) }

    private fun call(name: String, vararg arguments: Any): Any? = controller.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == arguments.size }.apply { isAccessible = true }
        .invoke(controller, *arguments)

    private object Boundary {
        lateinit var info: WirelessHotspotInfo
        val requests = AtomicInteger()
        val nextRequest = AtomicInteger(6)
        val offeredRequests = CopyOnWriteArrayList<Int>()
        val finishedRequests = CopyOnWriteArrayList<Int>()
        @Volatile var reuse: (() -> Unit)? = null
        @Volatile var attached: (() -> Unit)? = null
        @Volatile var addresses: (() -> Unit)? = null
        @Volatile var beforeRequest: (() -> Unit)? = null
        @Volatile var requestIo: (() -> Unit)? = null
    }

    /** Physical network readiness is supplied; ownership decisions remain in the real controller. */
    @Implements(WifiP2pGroupManager::class, isInAndroidSdk = false)
    class OwnedGroup {
        @Implementation fun reuseHealthyGroup(timeoutMillis: Long): WirelessHotspotInfo {
            Boundary.reuse?.invoke()
            return Boundary.info
        }
        @Implementation fun onCarPlayConfirmed() = Unit
        @Implementation fun close() = Unit
    }

    @Implements(CarPlayVpnService::class, isInAndroidSdk = false)
    class AttachedService {
        @Implementation fun isAttached(): Boolean {
            Boundary.attached?.invoke()
            return true
        }
        @Implementation fun boundAddresses(): List<InetAddress> {
            Boundary.addresses?.invoke()
            return Boundary.info.hostAddresses
        }
        @Implementation fun detachWireless(owner: AirPlayListenerIdentity) = Unit
    }

    @Implements(CarPlayBonjour::class, isInAndroidSdk = false)
    class Discovery {
        @Implementation fun requestReconnect(bluetoothId: String, onRequest: (Int) -> Unit): Int {
            val request = Boundary.nextRequest.incrementAndGet()
            Boundary.offeredRequests += request
            Boundary.beforeRequest?.invoke()
            onRequest(request)
            Boundary.requests.incrementAndGet()
            Boundary.requestIo?.invoke()
            return request
        }
        @Implementation fun finishReconnect(requestId: Int) { Boundary.finishedRequests += requestId }
        @Implementation fun close() = Unit
    }

    private class ManualStartupTimer(private val now: () -> Long) : ScheduledThreadPoolExecutor(1) {
        private val tasks = CopyOnWriteArrayList<ScheduledTask>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            ScheduledTask(now() + unit.toMillis(delay), command, now).also(tasks::add)
        fun runDue() {
            while (true) {
                val task = tasks.firstOrNull { !it.isDone && it.due <= now() } ?: return
                tasks.remove(task)
                task.run()
                task.get()
            }
        }
        override fun shutdownNow(): MutableList<Runnable> {
            tasks.forEach { it.cancel(false) }
            tasks.clear()
            return super.shutdownNow()
        }
    }

    private class ScheduledTask(val due: Long, command: Runnable, private val now: () -> Long) :
        FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(due - now(), TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS)
            .compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }

    private class IdleStream : BlockingDuplexByteStream {
        @Volatile private var closed = false
        override fun send(data: ByteArray) = Unit
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = if (closed) byteArrayOf() else null
        override fun close() { closed = true }
    }
}
