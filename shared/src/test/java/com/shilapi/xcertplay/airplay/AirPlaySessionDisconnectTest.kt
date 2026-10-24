package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class AirPlaySessionDisconnectTest {
    @Test fun disconnectInvalidatesInputBeforeSlowCleanupAndNotifiesOnlyOnce() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val disconnected = AtomicInteger()
        val ended = AtomicInteger()
        val socket = Socket()
        val session = session(socket, object : AirPlaySessionListener {
            override fun onSessionDisconnected(session: AirPlaySession) {
                assertNull(session.mainScreenSessionToken())
                assertFalse(session.sendTouch(emptyList()))
                assertTrue(socket.isClosed)
                disconnected.incrementAndGet()
            }
            override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
        }, object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
            }
        })
        AirPlaySession::class.java.getDeclaredField("mainScreenToken").apply { isAccessible = true }.set(session, Any())
        assertNotNull(session.mainScreenSessionToken())
        val worker = Thread { session.close() }.apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(1, disconnected.get())
            assertEquals(0, ended.get())
            assertFalse(session.awaitClosed(10))
            session.close()
            assertEquals(1, disconnected.get())
            resume.countDown()
            assertTrue(session.awaitClosed(3_000))
            assertEquals(1, ended.get())
        } finally { resume.countDown(); worker.join(3_000); session.close() }
    }

    @Test fun failingUiListenerAndMediaHandlerStillFinishCleanup() {
        val cleaned = AtomicInteger()
        val ended = AtomicInteger()
        val socket = Socket()
        val session = session(socket, object : AirPlaySessionListener {
            override fun onSessionDisconnected(session: AirPlaySession) { throw IllegalStateException("UI gone") }
            override fun onSessionEnded(session: AirPlaySession) {
                ended.incrementAndGet()
                throw IllegalStateException("UI gone")
            }
        }, object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) {
                cleaned.incrementAndGet()
                throw IllegalStateException("decoder failed")
            }
        })
        session.close()
        session.close()
        assertTrue(socket.isClosed)
        assertTrue(session.awaitClosed(10))
        assertEquals(1, cleaned.get())
        assertEquals(1, ended.get())
    }

    private fun session(socket: Socket, listener: AirPlaySessionListener, media: AirPlayMediaHandler) = AirPlaySession(
        socket, AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, listener, media,
    )
}
