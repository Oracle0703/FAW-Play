package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayBonjourEndpoint
import com.shilapi.xcertplay.network.CarPlayBonjourEvent
import com.shilapi.xcertplay.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWirelessHandoffRegressionTest {
    private val display = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)
    private lateinit var controller: CarPlayController
    private lateinit var callbacks: AirPlaySessionListener
    private lateinit var proof: WirelessConnectionProof<AirPlaySession>
    private val sessions = mutableListOf<AirPlaySession>()
    private val channels = mutableListOf<Iap2Session>()
    private val logs = CopyOnWriteArrayList<String>()
    private val runtimeEvents = CopyOnWriteArrayList<String>()
    private val navigationEvents = CopyOnWriteArrayList<String>()
    @Volatile private var readyLogBarrier: (() -> Unit)? = null
    @Volatile private var navigationReplayBarrier: (() -> Unit)? = null
    private var confirmations = 0
    private val fallbackStarted = CountDownLatch(1)

    @Before fun setup() {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = identification), display, AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { navigationEvents += "active" }
                override fun onWirelessRuntimeReady(session: AirPlaySession) { runtimeEvents += "ready" }
                override fun onWirelessRuntimeUnavailable(session: AirPlaySession) {
                    runtimeEvents += "unavailable"
                    navigationEvents += "unavailable"
                }
                override fun onNavigationDataReceived(session: AirPlaySession) { navigationEvents += "navigation" }
                override fun onDebugLog(message: String) {
                    logs += message
                    if (message.contains("path=bluetooth-fallback")) fallbackStarted.countDown()
                    if (message.startsWith("wireless iAP2 tunnel ready;")) readyLogBarrier?.invoke()
                    if (message.startsWith("Navigation: replayed ")) navigationReplayBarrier?.invoke()
                }
            }, object : AirPlayMediaHandler {}, {})
        val phase = controller.javaClass.declaredClasses.single { it.simpleName == "Phase" }
            .enumConstants.single { it.toString() == "WIRELESS" }
        ReflectionHelpers.setField(controller, "phase", phase)
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
        proof = ReflectionHelpers.getField(controller, "wirelessConnectionProof")
        proof.begin(1) { confirmations++ }
        val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})
        callbacks = call("wirelessSessionListener", 1, watchdog) as AirPlaySessionListener
    }

    @After fun cleanup() {
        controller.close()
        assertTrue(controller.awaitClosed(3_000))
        channels.forEach { it.close() }
        sessions.forEach { it.close() }
    }

    @Test fun actualRecoveryAdmissionRequiresThePreviousVerifiedControllerAndCurrentGeneration() {
        val recovery = ReflectionHelpers.getField<WirelessFastReconnect<AirPlaySession>>(controller, "fastReconnect")
        recovery.begin("expected-pairing")
        val expected = phone().also { verifiedId(it, "expected-pairing") }
        val other = phone().also { verifiedId(it, "other-pairing") }
        assertFalse(callbacks.authorizeSession(phone()))
        assertFalse(callbacks.authorizeSession(other))
        assertTrue(callbacks.authorizeSession(expected))
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").incrementAndGet()
        assertFalse(callbacks.authorizeSession(expected))
    }

    @Test fun controllerControl200WithoutMediaProofExpiresAndFallsBackOnlyOnce() {
        var millis = 0L
        val recovery = WirelessFastReconnect<AirPlaySession> { millis * 1_000_000 }
        ReflectionHelpers.setField(controller, "fastReconnect", recovery)
        ReflectionHelpers.setField(controller, "fastReconnectBonjourRequest", 7)
        val attempt = recovery.begin("expected-pairing")
        call("onWirelessBonjourEvent", 1, CarPlayBonjourEvent.Probed(
            CarPlayBonjourEndpoint("test", "192.0.2.2", 7000, "AA:BB:CC:DD:EE:FF"),
            1, "HTTP/1.1 200 OK", null, requestId = 7))
        assertEquals(WirelessFastReconnect.Stage.CONTROL_ACCEPTED, recovery.stage())
        assertNotNull(recovery.attempt())
        assertFalse(logs.any { it.contains("reconnect completed") })
        millis = 6_001
        // Exercise the controller's actual scheduled expiry twice, not only the pure policy.
        call("scheduleFastReconnectDeadline", 1, attempt)
        call("scheduleFastReconnectDeadline", 1, attempt)
        assertTrue(fallbackStarted.await(2, TimeUnit.SECONDS))
        assertNull(recovery.attempt())
        assertEquals(1, logs.count { it.contains("path=bluetooth-fallback") })
        assertFalse(logs.any { it.contains("reconnect completed") })
    }

    @Test fun actualRecoveryRequiresCurrentRuntimeAuthenticationAndMainFrame() {
        val recovery = ReflectionHelpers.getField<WirelessFastReconnect<AirPlaySession>>(controller, "fastReconnect")
        recovery.begin("expected-pairing")
        val phone = phone().also { verifiedId(it, "expected-pairing") }
        callbacks.onSessionActive(phone)
        ready(phone, installRuntime(phone))
        callbacks.onVideoFrameRendered(phone, 111)
        assertNotNull(recovery.attempt())
        assertFalse(logs.any { it.contains("reconnect completed") })
        callbacks.onVideoFrameRendered(phone, 110)
        assertNull(recovery.attempt())
        assertTrue(logs.any { it.contains("reconnect completed proof=authenticated-runtime-and-main-frame") })
        assertEquals(1, ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get())
        assertEquals(0, logs.count { it.contains("path=bluetooth-fallback") })
    }

    @Test fun onlyTheCurrentRequestsExhaustedEndpointsCanTriggerOneEarlyFallback() {
        val recovery = ReflectionHelpers.getField<WirelessFastReconnect<AirPlaySession>>(controller, "fastReconnect")
        ReflectionHelpers.setField(controller, "fastReconnectBonjourRequest", 7)
        val attempt = recovery.begin("expected-pairing")
        call("onWirelessBonjourEvent", 1, CarPlayBonjourEvent.EndpointsExhausted(6))
        call("onWirelessBonjourEvent", 0, CarPlayBonjourEvent.EndpointsExhausted(7))
        assertSame(attempt, recovery.attempt())
        call("onWirelessBonjourEvent", 1, CarPlayBonjourEvent.EndpointsExhausted(7))
        call("onWirelessBonjourEvent", 1, CarPlayBonjourEvent.EndpointsExhausted(7))
        assertTrue(fallbackStarted.await(2, TimeUnit.SECONDS))
        assertNull(recovery.attempt())
        assertEquals(1, logs.count { it.contains("path=bluetooth-fallback") })
    }

    @Test fun coldProgressFollowsTheActualSessionMainSetupAndCurrentPictureCallbacks() {
        var now = 0L
        var due = 0L
        var expire: () -> Unit = {}
        val failures = mutableListOf<WirelessSessionProgress.Stage>()
        val progress = WirelessSessionProgress<AirPlaySession>(
            { delay, task -> due = now + delay; expire = task; {} }, failures::add, { now * 1_000_000 })
        ReflectionHelpers.setField(controller, "coldSessionProgress", progress)
        progress.accepted(0)
        val phone = phone()
        callbacks.onSessionActive(phone)
        assertEquals(10_000L, due)
        ReflectionHelpers.setField(phone, "mainScreenToken", Any())
        now = 1_000
        callbacks.onMainScreenReady(phone)
        assertEquals(16_000L, due)
        val source = Any()
        phone.videoSourceStarted(110, source)
        phone.videoFrameReceived(110, source)
        callbacks.onMainScreenVideoReceived(phone()) // Old/unadopted session cannot finish it.
        assertEquals(16_000L, due)
        callbacks.onMainScreenVideoReceived(phone)
        now = 31_000
        expire()
        assertTrue(failures.isEmpty())
        assertFalse(phone.hasRenderedMainScreen())
    }

    @Test fun runtimeLossWithdrawsItsOwnStatusWhileTheMainScreenSessionCanStayActive() {
        val states = mutableListOf<String>()
        controller.attachUi(object : AirPlaySessionListener {
            override fun onWirelessRuntimeReady(session: AirPlaySession) { states.add("ready") }
            override fun onWirelessRuntimeUnavailable(session: AirPlaySession) { states.add("unavailable") }
        }, {})
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        callbacks.onVideoFrameRendered(phone, 110)
        assertEquals(listOf("ready"), states)
        call("clearWirelessTunnel", runtime)
        assertEquals(listOf("ready", "unavailable"), states)
        assertSame(phone, controller.currentActiveAirPlaySession())
        assertTrue(proof.hasRenderedFrame(1, phone))
        assertFalse(proof.isAuthenticated(1, phone))
        call("clearWirelessTunnel", runtime)
        assertEquals(listOf("ready", "unavailable"), states)
    }

    @Test fun locallyClosedBootstrapKeepsTheAuthenticatedWifiSessionBeforeMainSetup() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        val bootstrapClosed = CountDownLatch(1)
        val bootstrap = channel(bootstrapClosed)
        ReflectionHelpers.setField(controller, "csm", bootstrap)
        ready(phone, runtime)
        callbacks.onCommand(phone, "disableBluetooth", emptyMap())
        assertTrue(bootstrapClosed.await(2, TimeUnit.SECONDS))
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertSame(phone, controller.currentActiveAirPlaySession())
        assertSame(runtime, ReflectionHelpers.getField(controller, "wirelessTunnelChannel"))
        assertFalse(runtime.isClosed)
        assertNull(phone.mainScreenSessionToken())
        assertEquals(0, confirmations)
        callbacks.onVideoFrameRendered(phone, 111)
        assertEquals(0, confirmations)
        callbacks.onVideoFrameRendered(phone, 110)
        assertEquals(1, confirmations)
    }

    @Test fun authenticatedTunnelCanSurviveBootstrapEofBeforeAirPlayRecord() {
        val phone = phone()
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertNull(controller.currentActiveAirPlaySession())
        assertFalse(runtime.isClosed)
        assertEquals(0, confirmations)
        callbacks.onSessionActive(phone)
        callbacks.onVideoFrameRendered(phone, 110)
        assertEquals(1, confirmations)
    }

    @Test fun anInstrumentFrameAloneCannotPreserveAnUnauthenticatedConnection() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        installRuntime(phone)
        callbacks.onVideoFrameRendered(phone, 111)
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        assertEquals(0, confirmations)
    }

    @Test fun aMainFrameKeepsVideoWithoutClaimingTheRuntimeControlIsReady() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        callbacks.onVideoFrameRendered(phone, 110)
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertSame(phone, controller.currentActiveAirPlaySession())
        assertEquals(0, confirmations)
        assertFalse(ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(controller,
            "wirelessTunnelReady").get())
        assertTrue(logs.any { it.contains("proof=current_session_first_frame") && it.contains("control=unavailable") })
        assertTrue(logs.any { it.startsWith("STEP wireless/media-ready:") })
        assertFalse(logs.any { it.contains("tunnel iAP2 ready") || it.startsWith("wireless iAP2 tunnel ready;") })
    }

    @Test fun aClosedPreviousSessionAwaitingCleanupCannotBlockTheNewAuthenticatedCandidate() {
        val old = phone()
        callbacks.onSessionActive(old)
        old.close() // Keep the old active reference until its slow cleanup reports onSessionEnded.
        val current = phone()
        val runtime = installRuntime(current)
        ready(current, runtime)
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertFalse(runtime.isClosed)
        callbacks.onSessionActive(current)
        callbacks.onSessionEnded(old)
        callbacks.onVideoFrameRendered(current, 110)
        assertEquals(1, confirmations)
    }

    @Test fun aBootstrapTimeoutPreservesAnAuthenticatedRuntimeButNotAnEmptyConnection() {
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.TIMED_OUT) }
        val phone = phone()
        callbacks.onSessionActive(phone)
        ready(phone, installRuntime(phone))
        finish(Iap2WirelessControlTerminal.TIMED_OUT)
        assertEquals(0, confirmations)
        assertSame(phone, controller.currentActiveAirPlaySession())
    }

    @Test fun aReplacedChannelsLateReadyAndCleanupCannotAffectTheCurrentRuntime() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val old = installRuntime(phone)
        val current = installRuntime(phone)
        ready(phone, old)
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        ready(phone, current)
        call("clearWirelessTunnel", old)
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertSame(current, ReflectionHelpers.getField(controller, "wirelessTunnelChannel"))
        assertFalse(current.isClosed)
    }

    @Test fun clearBetweenAuthenticationAndNotificationCannotResurrectRuntimeReadiness() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        pauseReadyBeforeNotification(phone, runtime) {
            call("clearWirelessTunnel", runtime)
            assertEquals(listOf("unavailable"), runtimeEvents.toList())
        }
        assertEquals(listOf("unavailable"), runtimeEvents.toList())
        assertFalse(proof.isAuthenticated(1, phone))
    }

    @Test fun aReplacedSameSessionTunnelCannotEmitItsOldReadyAfterTheNewOne() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val old = installRuntime(phone)
        var replacement: Iap2Session? = null
        pauseReadyBeforeNotification(phone, old) {
            call("clearWirelessTunnel", old)
            replacement = installRuntime(phone)
            ready(phone, replacement!!)
            assertEquals(listOf("unavailable", "ready"), runtimeEvents.toList())
        }
        assertSame(replacement, ReflectionHelpers.getField(controller, "wirelessTunnelChannel"))
        assertEquals(listOf("unavailable", "ready"), runtimeEvents.toList())
        assertTrue(proof.isAuthenticated(1, phone))
    }

    @Test fun channelClosedBetweenAuthenticationAndNotificationCannotReportReady() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        pauseReadyBeforeNotification(phone, runtime) { runtime.close() }
        assertTrue(runtimeEvents.isEmpty())
    }

    @Test fun runtimeInvalidationWhileAFrameNotificationWaitsCannotRestoreNavigationReadiness() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        navigationEvents.clear()
        pauseRouteNotification(phone, runtime) {
            call("clearWirelessTunnel", runtime)
            assertEquals(listOf("unavailable"), navigationEvents.toList())
        }
        assertEquals(listOf("unavailable"), navigationEvents.toList())
    }

    @Test fun anOldLinksWaitingNotificationCannotProveTheSameSessionsReplacementLink() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val old = installRuntime(phone)
        ready(phone, old)
        navigationEvents.clear()
        var replacement: Iap2Session? = null
        pauseRouteNotification(phone, old) {
            call("clearWirelessTunnel", old)
            replacement = installRuntime(phone)
            ready(phone, replacement!!)
            assertEquals(listOf("unavailable"), navigationEvents.toList())
        }
        assertEquals(listOf("unavailable"), navigationEvents.toList())
        route(replacement!!)
        assertEquals(listOf("unavailable", "navigation"), navigationEvents.toList())
    }

    @Test fun prefetchedFramesCannotReportNavigationAfterTheirRuntimeWasInvalidated() {
        val phone = phone()
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        route(runtime) // Prefetch before RECORD: no navigation fact may precede active.
        assertTrue(navigationEvents.isEmpty())
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        navigationReplayBarrier = { paused.countDown(); assertTrue(resume.await(3, TimeUnit.SECONDS)) }
        val worker = Thread {
            try { callbacks.onSessionActive(phone) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            worker.start()
            assertTrue(paused.await(3, TimeUnit.SECONDS))
            call("clearWirelessTunnel", runtime)
        } finally {
            resume.countDown()
            worker.join(3_000)
            navigationReplayBarrier = null
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf("unavailable", "active"), navigationEvents.toList())
    }

    @Test fun foregroundReplayCannotReportOldNavigationAfterItsReadyCallbackClearsTheRuntime() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        route(runtime)
        val adopted = mutableListOf<String>()
        val listener = object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) { adopted += "active" }
            override fun onWirelessRuntimeReady(session: AirPlaySession) {
                adopted += "ready"
                call("clearWirelessTunnel", runtime)
            }
            override fun onWirelessRuntimeUnavailable(session: AirPlaySession) { adopted += "unavailable" }
            override fun onNavigationDataReceived(session: AirPlaySession) { adopted += "navigation" }
        }
        controller.attachUi(listener, {})
        controller.replayActiveSession(listener)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("active", "ready", "unavailable"), adopted)
    }

    @Test fun oldSessionAndGenerationCannotProveTheReplacementSession() {
        val old = phone()
        callbacks.onSessionActive(old)
        val oldRuntime = installRuntime(old)
        ready(old, oldRuntime)
        val current = phone()
        callbacks.onSessionActive(current)
        callbacks.onVideoFrameRendered(old, 110)
        ready(old, oldRuntime)
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        val currentRuntime = installRuntime(current)
        call("onWirelessTunnelReady", 0, current, currentRuntime)
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        ready(current, currentRuntime)
        callbacks.onSessionEnded(old)
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertEquals(0, confirmations)
        callbacks.onVideoFrameRendered(current, 110)
        assertEquals(1, confirmations)
    }

    @Test fun lateAttachmentFromAnOldLiveSessionCannotReplaceTheAuthenticatedRuntime() {
        val old = phone()
        val current = phone()
        callbacks.onSessionActive(current)
        val runtime = installRuntime(current)
        ready(current, runtime)
        // Supply all prerequisites so a rejected attachment cannot merely be a missing-MFi exit.
        ReflectionHelpers.setField(controller, "wirelessRuntimeIdentification", identification)
        ReflectionHelpers.setField(controller, "wirelessAirPlayEndpoint", Iap2WirelessCarPlayEndpoint(
            "test", "", 6, Iap2WirelessSecurity.NONE, listOf("127.0.0.1"), 7000, "test", "test", "1"))
        ReflectionHelpers.setField(controller, "mfiSession", MfiSession(object : MfiAuthenticator {
            override fun protocolMajor(): Int = error("old attachment must not authenticate")
            override fun readCertificate(maximumOutputLength: Int): ByteArray = error("not used")
            override fun signChallenge(challenge: ByteArray): ByteArray = error("not used")
        }, null))
        assertEquals(false, call("startWirelessTunnelControl", 1, old, IdleStream()))
        assertSame(runtime, ReflectionHelpers.getField(controller, "wirelessTunnelChannel"))
        finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertFalse(runtime.isClosed)
    }

    @Test fun disconnectedOrClosedSessionsCannotKeepTheBootstrapAlive() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        ready(phone, installRuntime(phone))
        callbacks.onSessionDisconnected(phone)
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        phone.close()
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
    }

    @Test fun aClosedRuntimeCannotBePreservedByItsOldReadyFlag() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val runtime = installRuntime(phone)
        ready(phone, runtime)
        runtime.close()
        assertThrows(IOException::class.java) { finish(Iap2WirelessControlTerminal.CHANNEL_CLOSED) }
        assertEquals(0, confirmations)
    }

    private fun finish(terminal: Iap2WirelessControlTerminal) { call("finishWirelessBootstrap", 1, terminal) }
    private fun ready(phone: AirPlaySession, channel: Iap2Session) { call("onWirelessTunnelReady", 1, phone, channel) }
    private fun route(link: Iap2Session) {
        call("onRouteFrame", Iap2Messages.buildRaw(0x5201) { u8(1, 1) }, link)
    }
    private fun pauseRouteNotification(phone: AirPlaySession, link: Iap2Session, block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { route(link) } catch (error: Throwable) { failure.set(error) } }
        val resourceLock = ReflectionHelpers.getField<Any>(controller, "wirelessResourceLock")
        val navigationLock = ReflectionHelpers.getField<Any>(controller, "navigationFrameLock")
        try {
            synchronized(resourceLock) {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (true) {
                    val marked = synchronized(navigationLock) {
                        val reported = ReflectionHelpers.getField<Pair<Any, AirPlaySession>?>(controller, "reportedNavigationData")
                        reported != null && reported.first === link && reported.second === phone
                    }
                    if (marked) break
                    failure.get()?.let { throw it }
                    assertTrue("Current link did not reach its notification boundary", System.nanoTime() < deadline)
                    Thread.yield()
                }
                block()
            }
        } finally { worker.join(3_000) }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
    }
    private fun pauseReadyBeforeNotification(phone: AirPlaySession, channel: Iap2Session, block: () -> Unit) {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        readyLogBarrier = {
            if (first.compareAndSet(true, false)) {
                paused.countDown()
                assertTrue(resume.await(3, TimeUnit.SECONDS))
            }
        }
        val worker = Thread {
            try { ready(phone, channel) } catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        try {
            assertTrue(paused.await(3, TimeUnit.SECONDS))
            block()
        } finally {
            resume.countDown()
            worker.join(3_000)
            readyLogBarrier = null
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
    }
    private fun installRuntime(phone: AirPlaySession): Iap2Session = channel().also {
        ReflectionHelpers.setField(controller, "wirelessTunnelChannel", it)
        ReflectionHelpers.setField(controller, "wirelessTunnelSession", phone)
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(controller, "wirelessTunnelReady").set(false)
    }
    private fun phone(): AirPlaySession = AirPlaySession(Socket(), display, AirPlayIdentity.generate(),
        PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        .also(sessions::add)
    private fun verifiedId(session: AirPlaySession, id: String) {
        val verifier = ReflectionHelpers.getField<Any>(session, "pairVerify")
        ReflectionHelpers.setField(verifier, "controllerId", id)
    }
    private fun channel(closed: CountDownLatch = CountDownLatch(0)): Iap2Session =
        Iap2Session.openTunnel(IdleStream(closed)).also(channels::add)
    private fun call(name: String, vararg arguments: Any): Any? {
        val method = controller.javaClass.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }
        return try { method.invoke(controller, *arguments) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private class IdleStream(private val didClose: CountDownLatch = CountDownLatch(0)) : BlockingDuplexByteStream {
        private val lock = Object()
        private var closed = false
        override fun send(data: ByteArray) {}
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            if (!closed && timeoutMillis > 0) lock.wait(timeoutMillis)
            if (closed) byteArrayOf() else null
        }
        override fun close() = synchronized(lock) {
            closed = true
            didClose.countDown()
            lock.notifyAll()
        }
    }
}
