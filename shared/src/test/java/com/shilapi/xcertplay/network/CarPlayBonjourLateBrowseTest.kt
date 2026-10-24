package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceEvent
import javax.jmdns.impl.LateBrowseRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayBonjourLateBrowseTest {
    @Test fun reconnectReusesRegistryAndAdvertisementButSelectsOnlyTheCurrentTarget() {
        val registry = LateBrowseRegistry()
        val probes = LinkedBlockingQueue<CarPlayBonjourEvent.Probed>()
        val sockets = AtomicInteger()
        val registries = AtomicInteger()
        val bonjour = CarPlayBonjour(
            RuntimeEnvironment.getApplication(),
            AirPlayConfig("test-car", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
                AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing"),
            advertisedHost = "192.0.2.10", useInterfaceMdns = true,
            onEvent = { if (it is CarPlayBonjourEvent.Probed) probes.offer(it) },
            createSocket = {
                sockets.incrementAndGet()
                object : Socket() {
                    override fun bind(bindpoint: SocketAddress?) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                }
            },
            createMdns = { _, _ -> registries.incrementAndGet(); registry },
        )
        try {
            bonjour.start()
            assertNotNull(registry.awaitQuery(3_000))
            registry.deliverLatePhoneResponse("AA:BB:CC:DD:EE:FF")
            assertEquals(0, probes.poll(3, TimeUnit.SECONDS)!!.requestId)
            val wrong = bonjour.requestReconnect("11:22:33:44:55:66")
            assertNull("Other phones must not receive a reconnect probe", probes.poll(650, TimeUnit.MILLISECONDS))
            val current = bonjour.requestReconnect("aabbccddeeff")
            bonjour.finishReconnect(wrong) // A queued old completion cannot revoke current discovery.
            val recovered = probes.poll(3, TimeUnit.SECONDS)
            assertNotNull("Live DNS service must be available to explicit recovery", recovered)
            assertEquals(current, recovered!!.requestId)
            assertEquals("HTTP/1.1 200 OK", recovered.statusLine)
            assertEquals(2, sockets.get())
            assertEquals("No new DNS registry or AP listener is required", 1, registries.get())
            bonjour.finishReconnect(current)
        } finally { bonjour.close() }
    }

    @Test fun realLibraryStopsInitialBrowseAndStableRefreshRetainsCacheWithoutReplayingBusinessEvents() {
        LateBrowseRegistry().use { registry ->
            val added = AtomicInteger()
            val resolved = LinkedBlockingQueue<ServiceEvent>()
            registry.addServiceListener(TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) { added.incrementAndGet() }
                override fun serviceRemoved(event: ServiceEvent) = Unit
                override fun serviceResolved(event: ServiceEvent) { resolved.offer(event) }
            })
            repeat(3) { assertNotNull("Initial JmDNS query missing", registry.awaitQuery(3_000)) }
            assertNull("The library has no continued browse after its initial burst", registry.awaitQuery(1_100))
            val noop = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) = Unit
                override fun serviceRemoved(event: ServiceEvent) = Unit
                override fun serviceResolved(event: ServiceEvent) = Unit
            }
            var now = 0L
            val browse = CarPlayMdnsBrowse({ registry.addServiceListener(TYPE, noop) }, { now }, { 0 })
            now = TimeUnit.SECONDS.toNanos(1)
            assertTrue(browse.refreshIfDue())
            assertNotNull("Public refresh must send a real PTR query", registry.awaitQuery(3_000))
            registry.deliverLatePhoneResponse()
            assertNotNull("Real DNS wire parsing must resolve the late peer", resolved.poll(3, TimeUnit.SECONDS))
            val listeners = registry.controlListenerCount()
            repeat(4) {
                now += TimeUnit.SECONDS.toNanos(60)
                assertTrue(browse.refreshIfDue())
            }
            assertEquals(3, listeners)
            assertEquals(listeners, registry.controlListenerCount())
            assertEquals(1, added.get())
            assertEquals(1, registry.list(TYPE, 0).size)
            browse.stop()
            now += TimeUnit.SECONDS.toNanos(60)
            assertFalse(browse.refreshIfDue())
        }
    }

    @Test fun productionWorkerFindsLatePhoneThroughRealDnsParserAndStopsRefreshingOnResolutionAndClose() {
        val registry = LateBrowseRegistry()
        val events = LinkedBlockingQueue<CarPlayBonjourEvent.Probed>()
        val sockets = AtomicInteger()
        val creations = AtomicInteger()
        val request = ByteArrayOutputStream()
        val bonjour = CarPlayBonjour(
            RuntimeEnvironment.getApplication(),
            AirPlayConfig("test-car", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
                AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing"),
            advertisedHost = "192.0.2.10",
            useInterfaceMdns = true,
            onEvent = { if (it is CarPlayBonjourEvent.Probed) events.offer(it) },
            createSocket = {
                sockets.incrementAndGet()
                object : Socket() {
                    override fun bind(bindpoint: SocketAddress?) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
                    override fun getOutputStream() = request
                    override fun getInputStream() = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                }
            },
            createMdns = { _, _ -> creations.incrementAndGet(); registry },
        )
        try {
            bonjour.start()
            // Only respond to a query after the original JmDNS three-query startup burst.
            repeat(4) { assertNotNull("Production worker did not continue browsing", registry.awaitQuery(3_000)) }
            registry.deliverLatePhoneResponse()
            val result = events.poll(3, TimeUnit.SECONDS)
            assertNotNull("Production callback must reach the existing control probe", result)
            assertEquals("HTTP/1.1 200 OK", result!!.statusLine)
            assertEquals(1, sockets.get())
            assertTrue(request.toString("US-ASCII").startsWith("GET /ctrl-int/1/connect HTTP/1.1\r\n"))
            assertTrue(bonjour.diagnosticSnapshot().contains("bonjourBrowseRefreshes=1"))
            // Drain the already-started library burst; no new application round is scheduled.
            assertNull(events.poll(1_300, TimeUnit.MILLISECONDS))
            assertTrue(bonjour.diagnosticSnapshot().contains("bonjourBrowseRefreshes=1"))
        } finally { bonjour.close() }
        val queriesAtClose = registry.queryCount.get()
        registry.clearQueries()
        assertNull(registry.awaitQuery(1_100))
        assertEquals(queriesAtClose, registry.queryCount.get())
        assertEquals(1, creations.get())
    }

    private companion object { const val TYPE = "_carplay-ctrl._tcp.local." }
}
