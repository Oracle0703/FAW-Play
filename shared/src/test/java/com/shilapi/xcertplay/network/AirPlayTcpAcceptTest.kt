package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayTcpAcceptTest {
    @Test fun acceptedTcpSessionReportsPrimaryProgressWithoutAWindowOrRenderedFrame() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val allocated = LinkedBlockingQueue<AirPlaySession>()
        val ready = LinkedBlockingQueue<AirPlaySession>()
        val received = LinkedBlockingQueue<AirPlaySession>()
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        val address = InetAddress.getByName("127.0.0.1")
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                address, config, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onMainScreenReady(session: AirPlaySession) { ready.add(session) }
                    override fun onMainScreenVideoReceived(session: AirPlaySession) { received.add(session) }
                }, object : AirPlayMediaHandler {
                    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int {
                        allocated.add(session)
                        return 32000
                    }
                }, listenerIdentity = AirPlayListenerIdentity(1)))
            Socket(address, service.boundPort()!!).use { peer ->
                val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 110L))))
                peer.getOutputStream().apply {
                    write(("SETUP * RTSP/1.0\r\nCSeq: 1\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                val session = allocated.poll(3, TimeUnit.SECONDS)
                assertNotNull("Real TCP SETUP must reach the media owner", session)
                assertSame("Accepted primary stream must reach the startup owner", session,
                    ready.poll(3, TimeUnit.SECONDS))
                val producer = Any()
                session!!.videoSourceStarted(110, producer)
                session.videoFrameReceived(110, Any())
                assertTrue("A stale producer cannot complete startup", received.isEmpty())
                session.videoFrameReceived(110, producer)
                assertSame("Background reception must reach the startup owner", session,
                    received.poll(3, TimeUnit.SECONDS))
                assertTrue(session.hasReceivedMainScreenVideo())
                assertFalse("Receiving video must not require a Surface", session.hasRenderedMainScreen())
                session.videoFrameReceived(110, producer)
                assertTrue("Only the first picture advances startup", received.isEmpty())
            }
        } finally { service.detach() }
    }

    @Test fun bothAddressFamiliesReportOwnerAndOldCleanupPreservesNewListener() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val received = LinkedBlockingQueue<AirPlayTcpAccepted>()
        val address = InetAddress.getByName("127.0.0.1")
        val addresses = listOf(address, InetAddress.getByName("::1"))
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        fun attach(owner: AirPlayListenerIdentity) {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address, config,
                AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onTcpAccepted(event: AirPlayTcpAccepted) { received.add(event) }
                }, object : AirPlayMediaHandler {},
                additionalBindAddresses = addresses.drop(1), listenerIdentity = owner))
        }
        try {
            val old = AirPlayListenerIdentity(1)
            attach(old)
            addresses.forEach { peer ->
                Socket(peer, service.boundPort()!!).use {
                    val event = received.poll(2, TimeUnit.SECONDS)!!
                    assertSame(old, event.listener)
                    assertTrue(event.internal)
                    assertTrue(event.acceptedAtNanos > 0)
                }
            }
            val current = AirPlayListenerIdentity(2)
            service.detachWireless(old)
            attach(current)
            service.detachWireless(old)
            assertTrue(service.isAttached())
            addresses.forEach { peer ->
                Socket(peer, service.boundPort()!!).use {
                    assertSame(current, received.poll(2, TimeUnit.SECONDS)!!.listener)
                }
            }
            service.detachWireless(current)
            assertFalse(service.isAttached())
        } finally { service.detach() }
    }

    @Test fun serviceDestructionRevokesAdmissionWithoutWaitingForNativeMediaClose() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
            })
        ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "sessions").add(session)
        val closing = Thread { session.close() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            val start = System.nanoTime()
            service.onDestroy()
            assertTrue("Lifecycle must hand off native waits", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
            assertFalse(service.isAttached())
            assertTrue(service.attachWireless(InetAddress.getLoopbackAddress(), config,
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}) is CarPlayVpnService.AttachResult.Failed)
        } finally { release.countDown(); closing.join(2000); service.detach() }
    }

    @Test fun remotePhoneAddressIsExternal() {
        assertFalse(isInternalAirPlayPeer(InetAddress.getByName("192.0.2.12"), InetAddress.getByName("192.0.2.1")))
    }

    @Test fun detachWaitsForInflightCleanupWithoutLockingOutSessionRemoval() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val session = AirPlaySession(Socket(),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ReflectionHelpers.callInstanceMethod<Unit>(service, "removeSession",
                        ReflectionHelpers.ClassParameter.from(AirPlaySession::class.java, session))
                }
            }, object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    entered.countDown()
                    check(resume.await(3, TimeUnit.SECONDS))
                }
            })
        ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "sessions").add(session)
        val cleanup = Thread { session.close() }.apply { start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val detach = Thread { service.detach(); detached.countDown() }.apply { start() }
        try {
            assertFalse(detached.await(100, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(detached.await(3, TimeUnit.SECONDS))
            assertTrue(session.awaitClosed(10))
        } finally { resume.countDown(); cleanup.join(3_000); detach.join(3_000); service.detach() }
    }

    @Test fun interruptedDetachRetainsItsOwnerAndMediaUntilRetryReallyCompletes() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val owner = AirPlayListenerIdentity(1)
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
            InetAddress.getByName("127.0.0.1"), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, listenerIdentity = owner))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val retried = CountDownLatch(1)
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
            })
        ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "sessions").add(session)
        val cleanup = Thread { session.close() }.apply { start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val detach = Thread {
            try { service.detachWireless(owner) }
            catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        var retry: Thread? = null
        try {
            // Ensure the first detach reached the media wait before interrupting it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            val sessionsLock = ReflectionHelpers.getField<Any>(service, "sessionsLock")
            while (synchronized(sessionsLock) {
                ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "closingSessions").isEmpty()
            }) {
                check(System.nanoTime() < deadline)
                Thread.yield()
            }
            detach.interrupt()
            detach.join(3_000)
            assertNotNull(failure.get())
            assertSame(owner, ReflectionHelpers.getField(service, "releasingOwner"))
            retry = Thread { service.detachWireless(owner); retried.countDown() }.apply { start() }
            assertFalse(retried.await(100, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(retried.await(3, TimeUnit.SECONDS))
            assertTrue(session.awaitClosed(50))
            assertTrue(ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "closingSessions").isEmpty())
            assertNull(ReflectionHelpers.getField(service, "releasingOwner"))
        } finally {
            resume.countDown(); cleanup.join(3_000); detach.join(3_000); retry?.join(3_000); service.detach()
        }
    }
}
