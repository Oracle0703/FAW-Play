package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.MfiAuthenticator
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
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual tunnel executor, identification, MFi, subscriptions and onIncoming hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWirelessNavigationIntegrationTest {
    private lateinit var controller: CarPlayController
    private lateinit var callbacks: AirPlaySessionListener
    private val display = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val sessions = mutableListOf<AirPlaySession>()
    private val peers = mutableListOf<PhonePeer>()
    private val lanes = AtomicReference<ClusterLaneGuidance?>()
    private val turns = AtomicReference<ClusterTurnGuidance?>()
    private val logs = CopyOnWriteArrayList<String>()
    private val ready = CountDownLatch(1)
    private var markerSequence = 0

    @Before fun setup() {
        AutunnNavigationOutputs.endNow()
        AutunnNavigationOutputs.setLaneOverlayListener { lanes.set(it) }
        AutunnNavigationOutputs.setTurnOverlayListener { turns.set(it) }
        val identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3,
            wireless = Iap2WirelessIdentification("02:11:22:33:44:55", "test"), laneGuidanceEnabled = true)
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = identification), display, AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) {
                    logs += message
                    if (message.startsWith("wireless iAP2 tunnel ready;")) ready.countDown()
                }
            }, object : AirPlayMediaHandler {}, {})
        val phase = controller.javaClass.declaredClasses.single { it.simpleName == "Phase" }
            .enumConstants.single { it.toString() == "WIRELESS" }
        ReflectionHelpers.setField(controller, "phase", phase)
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
        ReflectionHelpers.getField<WirelessConnectionProof<AirPlaySession>>(controller,
            "wirelessConnectionProof").begin(1) {}
        ReflectionHelpers.setField(controller, "wirelessRuntimeIdentification", identification)
        ReflectionHelpers.setField(controller, "wirelessAirPlayEndpoint", Iap2WirelessCarPlayEndpoint(
            "test", "", 6, Iap2WirelessSecurity.NONE, listOf("127.0.0.1"), 7000, "test", "test", "1"))
        ReflectionHelpers.setField(controller, "mfiSession", MfiSession(object : MfiAuthenticator {
            override fun protocolMajor(): Int = 2
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2, 3)
            override fun signChallenge(challenge: ByteArray): ByteArray {
                assertArrayEquals(byteArrayOf(4, 5, 6), challenge)
                return byteArrayOf(7, 8, 9)
            }
        }, null))
        callbacks = call("wirelessSessionListener", 1,
            FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})) as AirPlaySessionListener
    }

    @After fun cleanup() {
        controller.close()
        assertTrue(controller.awaitClosed(3_000))
        peers.forEach { it.close() }
        sessions.forEach { it.close() }
        AutunnNavigationOutputs.setLaneOverlayListener(null)
        AutunnNavigationOutputs.setTurnOverlayListener(null)
        AutunnNavigationOutputs.endNow()
    }

    @Test fun authenticatedRuntimeIncomingPublishesTurnAndLaneAndDisconnectRejectsLateFrames() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val peer = attach(phone)
        authenticate(peer)
        publish(peer, 7)
        drainIncoming(peer)
        assertEquals(140, turns.get()!!.distanceMeters)
        assertEquals(7, lanes.get()!!.index)
        assertSame(phone, ReflectionHelpers.getField(controller, "vehicleNavigationLinkSession"))
        assertTrue(peer.received.any { it.messageId == 0x5200 })
        callbacks.onSessionDisconnected(phone)
        assertNull(turns.get())
        assertNull(lanes.get())
        publish(peer, 8)
        drainIncoming(peer)
        assertNull(turns.get())
        assertNull(lanes.get())
        assertEquals(0, pendingFrames())
    }

    @Test fun authenticatedCandidatePrefetchWaitsForItsOwnAirPlayRecord() {
        val candidate = phone()
        val peer = attach(candidate)
        authenticate(peer)
        publish(peer, 8)
        drainIncoming(peer)
        assertNull(turns.get())
        assertNull(lanes.get())
        assertEquals(3, pendingFrames())
        assertSame(candidate, ReflectionHelpers.getField(controller, "vehicleNavigationLinkSession"))
        callbacks.onSessionActive(candidate)
        assertEquals(140, turns.get()!!.distanceMeters)
        assertEquals(8, lanes.get()!!.index)
        assertEquals(0, pendingFrames())
    }

    @Test fun aDifferentActiveSessionCannotAdoptAnAuthenticatedCandidatesPrefetch() {
        val candidate = phone()
        val peer = attach(candidate)
        authenticate(peer)
        publish(peer, 8)
        drainIncoming(peer)
        callbacks.onSessionActive(phone())
        assertSame(candidate, ReflectionHelpers.getField(controller, "vehicleNavigationLinkSession"))
        assertEquals(0, pendingFrames())
        assertNull(turns.get())
        assertNull(lanes.get())
        callbacks.onSessionActive(candidate)
        assertNull(turns.get())
        assertNull(lanes.get())
        publish(peer, 9)
        drainIncoming(peer)
        assertEquals(9, lanes.get()!!.index)
    }

    @Test fun aCandidateCannotTakeNavigationOwnershipBeforeMfiAndSubscriptionsComplete() {
        val phone = phone()
        callbacks.onSessionActive(phone)
        val existingLink = Any()
        assertEquals(true, call("beginVehicleNavigationLink", existingLink))
        navigation(7).forEach { call("onRouteFrame", it, existingLink) }
        assertEquals(7, lanes.get()!!.index)
        val peer = attach(phone)
        assertTrue(peer.challengeAnswered.await(3, TimeUnit.SECONDS))
        assertSame(existingLink, ReflectionHelpers.getField(controller, "vehicleNavigationLink"))
        assertEquals(7, lanes.get()!!.index)
        assertFalse(logs.any { it.startsWith("wireless iAP2 tunnel ready;") })
        authenticate(peer)
        val runtime: Any = ReflectionHelpers.getField(controller, "wirelessTunnelChannel")
        assertSame(runtime, ReflectionHelpers.getField(controller, "vehicleNavigationLink"))
        // Old cleanup and frames cannot revoke or contaminate the authenticated replacement.
        call("endVehicleNavigationLink", existingLink)
        call("onRouteFrame", navigation(8).last(), existingLink)
        publish(peer, 9)
        drainIncoming(peer)
        assertEquals(9, lanes.get()!!.index)
        assertSame(runtime, ReflectionHelpers.getField(controller, "vehicleNavigationLink"))
    }

    @Test fun staleGenerationAndAnotherLivePhonesTunnelCannotEnterTheRuntime() {
        val active = phone()
        callbacks.onSessionActive(active)
        val peer = PhonePeer().also(peers::add)
        assertEquals(false, call("startWirelessTunnelControl", 0, active, peer))
        assertEquals(false, call("startWirelessTunnelControl", 1, phone(), peer))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "wirelessTunnelChannel"))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "vehicleNavigationLink"))
    }

    private fun attach(phone: AirPlaySession): PhonePeer = PhonePeer().also {
        peers += it
        assertEquals(true, call("startWirelessTunnelControl", 1, phone, it))
    }

    private fun authenticate(peer: PhonePeer) {
        assertTrue(peer.challengeAnswered.await(3, TimeUnit.SECONDS))
        peer.authenticate()
        assertTrue("runtime did not become ready: $logs", ready.await(3, TimeUnit.SECONDS))
    }

    /** A metadata marker is forwarded by the same real onIncoming loop after navigation frames. */
    private fun drainIncoming(peer: PhonePeer) {
        val received = CountDownLatch(1)
        val title = "marker-${++markerSequence}"
        controller.nowPlayingListener = { if (it.title == title) received.countDown() }
        peer.emit(Iap2Messages.buildRaw(0x5001) { group(0) { string(1, title) } })
        assertTrue("incoming loop did not drain: $logs", received.await(3, TimeUnit.SECONDS))
        controller.nowPlayingListener = null
    }

    private fun publish(peer: PhonePeer, index: Int) = navigation(index).forEach(peer::emit)

    private fun navigation(index: Int) = listOf(
        Iap2Messages.buildRaw(0x5202) { u16(1, index); u8(3, 1) },
        Iap2Messages.buildRaw(0x5204) {
            u16(0, 42); u16(1, index)
            group(2) { u16(0, 0); u8(1, 2); i16(2, 0); i16(3, -90) }
        },
        Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u32(10, 140); u16(13, index); u16(16, index); bool(18, true)
        },
    )

    private fun pendingFrames() = ReflectionHelpers
        .getField<java.util.ArrayDeque<*>>(controller, "pendingNavigationFrames").size

    private fun phone() = AirPlaySession(Socket(), display, AirPlayIdentity.generate(), PairingStore(),
        null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(sessions::add)

    private fun call(name: String, vararg args: Any): Any? {
        val method = controller.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }
        return try { method.invoke(controller, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    /** Complete link negotiation and the phone side of identification/MFi, with AA05 controlled by the test. */
    private class PhonePeer : BlockingDuplexByteStream {
        private val engine = Iap2LinkEngine().apply { start(false, now()) }
        private val framer = Iap2CsmFramer()
        private var started = false
        private var closed = false
        val challengeAnswered = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Iap2Frame>()

        @Synchronized override fun send(data: ByteArray) {
            if (!closed) { engine.feed(data, now()); pump() }
        }

        @Synchronized override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (closed) return ByteArray(0)
            engine.advanceTime(now())
            pump()
            return engine.takeOutput().takeIf { it.isNotEmpty() }
        }

        @Synchronized fun authenticate() { emit(Iap2Frame(0xaa05, byteArrayOf())) }
        @Synchronized fun emit(frame: Iap2Frame) { engine.sendControl(frame.encodedFrame(), now()) }
        @Synchronized override fun close() { closed = true }

        private fun pump() {
            while (true) when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Writable -> if (event.value && !started) {
                    started = true
                    emit(Iap2Frame(0x1d00, byteArrayOf()))
                }
                is Iap2LinkEngine.Event.Control -> framer.offer(event.bytes).forEach { frame ->
                    received += frame
                    when (frame.messageId) {
                        0x1d01 -> { emit(Iap2Frame(0x1d02, byteArrayOf())); emit(Iap2Frame(0xaa00, byteArrayOf())) }
                        0xaa01 -> emit(Iap2Messages.buildRaw(0xaa02) { bytes(0, byteArrayOf(4, 5, 6)) })
                        0xaa03 -> challengeAnswered.countDown()
                    }
                }
                else -> Unit
            }
        }
    }

    companion object { private fun now() = System.nanoTime() / 1_000_000 }
}
