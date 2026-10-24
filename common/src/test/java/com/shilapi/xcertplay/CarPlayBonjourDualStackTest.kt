package com.shilapi.xcertplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayBonjourEvent
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.PairingStore
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourDualStackTest {
    private val context = mock(Context::class.java)
    private val wifi = mock(WifiManager::class.java)
    private val lock = mock(WifiManager.MulticastLock::class.java)
    private val ipv4 = InetAddress.getByName("192.0.2.10")
    private val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 7)
    private val config = AirPlayConfig(deviceName = "DiPlay", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing")

    private fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.NSD_SERVICE)).thenReturn(mock(NsdManager::class.java))
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
        `when`(wifi.createMulticastLock("carplay-bonjour")).thenReturn(lock)
        `when`(lock.isHeld).thenReturn(true)
    }

    @Test fun publishesAndBrowsesBothFamiliesAndClosesBothRegistries() {
        setup()
        val v4 = mock(JmDNS::class.java)
        val v6 = mock(JmDNS::class.java)
        `when`(v4.inetAddress).thenReturn(ipv4)
        `when`(v6.inetAddress).thenReturn(ipv6)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(v6)
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(v4)
            val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
                additionalAddresses = listOf(ipv4))
            bonjour.start()
            assertTrue(bonjour.diagnosticSnapshot().contains("mdnsFamilies=IPv6,IPv4"))
            listOf(v6, v4).forEach { dns ->
                verify(dns).registerService(any(ServiceInfo::class.java))
                verify(dns).addServiceListener(eq("_carplay-ctrl._tcp.local."), any(ServiceListener::class.java))
            }
            bonjour.close()
            bonjour.close()
            verify(v6).close()
            verify(v4).close()
            verify(lock).release()
        }
    }

    @Test fun failedRegistryCloseRetainsTheHandleForRetry() {
        setup()
        val dns = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(dns)
            val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true)
            try {
                bonjour.start()
                doThrow(IOException("temporary registry cleanup failure")).`when`(dns).close()
                assertThrows(IOException::class.java) { bonjour.close() }
                assertTrue(ReflectionHelpers.getField<List<JmDNS>>(bonjour, "interfaceMdns").contains(dns))
                doNothing().`when`(dns).close()
                bonjour.close()
                assertTrue(ReflectionHelpers.getField<List<JmDNS>>(bonjour, "interfaceMdns").isEmpty())
                bonjour.close()
                verify(dns, times(2)).close()
            } finally { doNothing().`when`(dns).close(); bonjour.close() }
        }
    }

    @Test fun aWorkerStillExitingCannotBeReportedAsClosed() {
        setup()
        `when`(lock.isHeld).thenReturn(false)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            while (resume.count > 0) {
                try { resume.await() }
                catch (_: InterruptedException) { /* Simulate native cleanup still returning. */ }
            }
        }.apply { isDaemon = true; start() }
        val bonjour = CarPlayBonjour(context, config, identity)
        ReflectionHelpers.setField(bonjour, "worker", worker)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            assertThrows(IOException::class.java) { bonjour.close() }
            assertSame(worker, ReflectionHelpers.getField(bonjour, "worker"))
            resume.countDown()
            worker.join(3_000)
            bonjour.close()
            assertNull(ReflectionHelpers.getField(bonjour, "worker"))
        } finally { resume.countDown(); worker.join(3_000); bonjour.close() }
    }

    @Test fun registryAddressControlsFamilyEvenWhenDeprecatedInterfaceReturnsTheOtherFamily() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        `when`(dns.getInterface()).thenReturn(ipv6) // Android's arbitrary interface address.
        val info = mock(ServiceInfo::class.java)
        `when`(info.inetAddresses).thenReturn(arrayOf(InetAddress.getByName("192.0.2.20")))
        `when`(info.port).thenReturn(7000)
        val event = mock(javax.jmdns.ServiceEvent::class.java)
        `when`(event.dns).thenReturn(dns)
        `when`(event.info).thenReturn(info)
        `when`(event.name).thenReturn("test-phone")
        CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4)).use { bonjour ->
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            listener.serviceResolved(event)
            val queue = ReflectionHelpers.getField<java.util.concurrent.LinkedBlockingQueue<*>>(bonjour, "interfaceServices")
            assertEquals(1, queue.size)
            verify(dns, never()).getInterface()
        }
    }

    @Test fun dualLanThenSingleP2pThenDualLanServesAirPlayInfo() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var activeSessions = 0
        try {
            val service = controller.get()
            for (extras in listOf(listOf(InetAddress.getByName("127.0.0.1")), emptyList(),
                                  listOf(InetAddress.getByName("127.0.0.1")))) {
                assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                    InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onDebugLog(message: String) { events.add(message) }
                        override fun onSessionActive(session: com.shilapi.xcertplay.airplay.AirPlaySession) { activeSessions++ }
                    }, object : AirPlayMediaHandler {}, extras))
                for (address in listOf(InetAddress.getByName("::1")) + extras) {
                    java.net.Socket(address, service.boundPort()!!).use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        assertEquals("HTTP/1.1 200 OK", input.readLine())
                        var length = 0
                        while (true) {
                            val line = input.readLine()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val read = input.read(body, offset, length - offset); assertTrue(read > 0); offset += read }
                        assertTrue(String(body).startsWith("bplist00"))
                    }
                }
                assertTrue(service.isAttached())
                service.detach()
            }
            assertEquals(0, activeSessions)
            assertEquals(5, events.count { it.startsWith("airplay rx GET /info") })
        } finally { controller.destroy() }
    }

    @Test fun probesSelectMatchingLocalFamilyAndApplyIpv6InterfaceScope() {
        setup()
        val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4))
        fun invoke(name: String, target: InetAddress): InetAddress = ReflectionHelpers.callInstanceMethod(
            bonjour, name, ClassParameter.from(InetAddress::class.java, target))
        assertEquals(ipv4, invoke("sourceAddressFor", InetAddress.getByName("192.0.2.20")))
        assertEquals(ipv6, invoke("sourceAddressFor", InetAddress.getByName("fe80::20")))
        assertEquals(7, (invoke("applyLocalScope", InetAddress.getByName("fe80::20")) as Inet6Address).scopeId)
        bonjour.close()
    }

    @Test fun defaultSingleAddressPublicationRemainsUnchanged() {
        setup()
        val dns = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(dns)
            CarPlayBonjour(context, config, identity, ipv6.hostAddress, true).use {
                it.start()
                assertTrue(it.diagnosticSnapshot().contains("mdnsFamilies=IPv6"))
                verify(dns).registerService(any(ServiceInfo::class.java))
            }
            factory.verify({ JmDNS.create(ipv6, "carplay-020000000002") }, times(1))
            factory.verifyNoMoreInteractions()
            verify(dns).close()
        }
    }

    @Test fun serviceDetachesBothSamePortListenersOnAndroid10() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listOf(InetAddress.getByName("127.0.0.1"))))
            val primary = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
            val extra = ReflectionHelpers.getField<List<ServerSocket>>(service, "additionalServers").single()
            assertEquals(primary.localPort, extra.localPort)
            service.detach()
            assertTrue(primary.isClosed)
            assertTrue(extra.isClosed)
            assertFalse(service.isAttached())
            service.detach()
        } finally { controller.destroy() }
    }

    @Test fun optionalRegistryFailurePreservesThePrimaryAndReleasesEverythingOnClose() {
        setup()
        val first = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(first)
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenThrow(IOException("bind failed"))
            val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                additionalAddresses = listOf(ipv6))
            bonjour.start()
            assertTrue(bonjour.diagnosticSnapshot().contains("mdnsFamilies=IPv4"))
            verify(first, never()).close()
            verify(lock, never()).release()
            bonjour.close()
            verify(first).close()
            verify(lock).release()
        }
    }

    @Test fun optionalRegistrationFailureClosesOnlyTheFailedRegistry() {
        setup()
        val first = mock(JmDNS::class.java)
        val failed = mock(JmDNS::class.java)
        doThrow(IOException("registration failed")).`when`(failed).registerService(any(ServiceInfo::class.java))
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(first)
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(failed)
            CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                additionalAddresses = listOf(ipv6)).use { bonjour ->
                bonjour.start()
                assertTrue(bonjour.diagnosticSnapshot().contains("mdnsFamilies=IPv4"))
                verify(failed).close()
                verify(first, never()).close()
            }
            verify(first).close()
            verify(failed, times(1)).close()
        }
    }

    @Test fun primaryRegistryFailureStillFailsAndReleasesTheLock() {
        setup()
        val first = mock(JmDNS::class.java)
        doThrow(IOException("registration failed")).`when`(first).registerService(any(ServiceInfo::class.java))
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(first)
            val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                additionalAddresses = listOf(ipv6))
            assertThrows(IOException::class.java) { bonjour.start() }
            verify(first).close()
            verify(lock).release()
            factory.verify({ JmDNS.create(ipv6, "carplay-020000000002") }, never())
        }
    }

    @Test fun optionalListenerFailureLeavesPrimaryUsableAndReportsOnlyBoundAddresses() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val primaryAddress = InetAddress.getByName("127.0.0.1")
        try {
            val service = controller.get()
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                primaryAddress, config.copy(port = 0), identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listOf(InetAddress.getByName("2001:db8::1234"))))
            assertEquals(listOf(primaryAddress), service.boundAddresses())
            assertTrue(service.isAttached())
            Socket(primaryAddress, service.boundPort()!!).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 200 OK", socket.getInputStream().bufferedReader().readLine())
            }
            service.detach()
            assertTrue(service.boundAddresses().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun stalledIpv6ProbeCannotDelayIpv4AndCloseCancelsBothWorkers() {
        setup()
        val v4 = mock(JmDNS::class.java)
        val v6 = mock(JmDNS::class.java)
        `when`(v4.inetAddress).thenReturn(ipv4)
        `when`(v6.inetAddress).thenReturn(ipv6)
        val v6Reading = CountDownLatch(1)
        val v4Probed = CountDownLatch(1)
        val sockets = CopyOnWriteArrayList<Socket>()
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(v4)
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(v6)
            val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                onEvent = { if (it is CarPlayBonjourEvent.Probed && ':' !in it.endpoint.host && it.error == null) v4Probed.countDown() },
                additionalAddresses = listOf(ipv6), createSocket = {
                    ProbeSocket(v6Reading).also { sockets.add(it) }
                })
            try {
                bonjour.start()
                val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
                listener.serviceResolved(resolvedEvent(v6, InetAddress.getByName("fe80::20")))
                assertTrue("IPv6 probe did not reach its blocked read", v6Reading.await(3, TimeUnit.SECONDS))
                listener.serviceResolved(resolvedEvent(v4, InetAddress.getByName("192.0.2.20")))
                assertTrue("IPv4 was held behind IPv6 probe retries", v4Probed.await(3, TimeUnit.SECONDS))
                val workers = listOf("worker", "ipv6Worker").map {
                    ReflectionHelpers.getField<Thread>(bonjour, it)
                }
                bonjour.close()
                assertEquals(2, sockets.size)
                assertTrue(sockets.all { it.isClosed })
                assertTrue(workers.none { it.isAlive })
            } finally { bonjour.close() }
        }
    }

    @Test fun closeBetweenSocketCreationAndRegistrationClosesTheUnregisteredSocket() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        val socketClosed = CountDownLatch(1)
        val connectCalls = java.util.concurrent.atomic.AtomicInteger()
        val socket = object : Socket() {
            override fun close() { socketClosed.countDown() }
            override fun connect(endpoint: SocketAddress?, timeout: Int) { connectCalls.incrementAndGet() }
        }
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(dns)
            lateinit var bonjour: CarPlayBonjour
            bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                createSocket = {
                    val incomplete = assertThrows(IOException::class.java) { bonjour.close() }
                    assertEquals("Bonjour callback worker is still running", incomplete.cause?.message)
                    assertFalse(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
                    socket
                })
            try {
                bonjour.start()
                val worker = ReflectionHelpers.getField<Thread>(bonjour, "worker")
                val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
                listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.20")))
                assertTrue("A socket created during close must be released", socketClosed.await(3, TimeUnit.SECONDS))
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertEquals(0, connectCalls.get())
            } finally { bonjour.close() }
        }
    }

    @Test fun changedTargetEndpointCancelsTheOldReadAndRejectsItsLateSuccess() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        val enteredOldRead = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val sockets = AtomicInteger()
        val results = LinkedBlockingQueue<CarPlayBonjourEvent.Probed>()
        val connected = CopyOnWriteArrayList<InetSocketAddress>()
        val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
            onEvent = { if (it is CarPlayBonjourEvent.Probed) results.offer(it) },
            targetBluetoothId = TARGET, createMdns = { _, _ -> dns }, createSocket = {
                val old = sockets.incrementAndGet() == 1
                object : Socket() {
                    private val response = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                    override fun bind(bindpoint: SocketAddress?) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) { connected.add(endpoint as InetSocketAddress) }
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream(): InputStream = if (!old) response else object : InputStream() {
                        override fun read(): Int {
                            enteredOldRead.countDown()
                            check(releaseOldRead.await(3, TimeUnit.SECONDS))
                            return response.read() // Deliberately return a late success even after close.
                        }
                    }
                    override fun close() { if (old) releaseOldRead.countDown() }
                }
            })
        try {
            bonjour.start()
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            val old = resolvedEvent(dns, InetAddress.getByName("192.0.2.20"), bluetoothId = TARGET)
            listener.serviceResolved(old)
            assertTrue(enteredOldRead.await(3, TimeUnit.SECONDS))
            listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.99"),
                port = 7999, bluetoothId = "11:22:33:44:55:66"))
            assertEquals("An unrelated TXT identity cannot cancel the selected phone", 1L, releaseOldRead.count)
            val changedPort = resolvedEvent(dns, InetAddress.getByName("192.0.2.20"), port = 7001,
                bluetoothId = TARGET)
            listener.serviceResolved(changedPort)
            val result = results.poll(3, TimeUnit.SECONDS)
            assertNotNull("The updated port must be probed", result)
            assertEquals(7001, result!!.endpoint.port)
            assertEquals(0L, releaseOldRead.count)
            listener.serviceResolved(changedPort)
            listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.21"), port = 7001,
                bluetoothId = TARGET))
            assertEquals("192.0.2.21", results.poll(3, TimeUnit.SECONDS)!!.endpoint.host)
            assertNull("The replaced endpoint's successful response must remain stale", results.poll(250, TimeUnit.MILLISECONDS))
            assertEquals(listOf(7000, 7001, 7001), connected.map { it.port })
            assertEquals(3, sockets.get())
        } finally { releaseOldRead.countDown(); bonjour.close() }
    }

    @Test fun finalFailureResumesBrowseWithoutRefillingTheSameEndpointAndSuccessCanStopDiscovery() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        val results = LinkedBlockingQueue<CarPlayBonjourEvent.Probed>()
        val sockets = AtomicInteger()
        val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
            onEvent = { if (it is CarPlayBonjourEvent.Probed) results.offer(it) },
            createMdns = { _, _ -> dns }, createSocket = {
                val fail = sockets.incrementAndGet() == 1
                object : Socket() {
                    override fun bind(bindpoint: SocketAddress?) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        if (fail) throw IOException("endpoint no longer reachable")
                    }
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                }
            })
        try {
            bonjour.start()
            val request = bonjour.requestReconnect(TARGET)
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            val failed = resolvedEvent(dns, InetAddress.getByName("192.0.2.20"), bluetoothId = TARGET)
            listener.serviceResolved(failed)
            assertNotNull(results.poll(3, TimeUnit.SECONDS)!!.error)
            verify(dns, timeout(3_000).times(3)).addServiceListener(eq("_carplay-ctrl._tcp.local."), any(ServiceListener::class.java))
            repeat(4) { listener.serviceResolved(failed) }
            listener.serviceRemoved(failed)
            listener.serviceResolved(failed) // Removal and reappearance still share the spent budget.
            assertNull(results.poll(250, TimeUnit.MILLISECONDS))
            assertEquals(1, sockets.get())
            listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.21"), bluetoothId = TARGET))
            assertNull(results.poll(3, TimeUnit.SECONDS)!!.error)
            val oldStop = bonjour.captureControlDiscoveryStop()!!
            bonjour.stopControlDiscovery()
            listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.22"), bluetoothId = TARGET))
            assertNull(results.poll(250, TimeUnit.MILLISECONDS))
            assertEquals(2, sockets.get())
            val next = bonjour.requestReconnect(TARGET)
            oldStop() // A delayed first-frame callback cannot cancel the next reconnect request.
            bonjour.finishReconnect(request) // An old completion cannot stop the next request.
            listener.serviceResolved(failed)
            assertEquals(next, results.poll(3, TimeUnit.SECONDS)!!.requestId)
            assertEquals(3, sockets.get())
            bonjour.captureControlDiscoveryStop()!!()
            assertNull(bonjour.captureControlDiscoveryStop())
            listener.serviceResolved(resolvedEvent(dns, InetAddress.getByName("192.0.2.23"), bluetoothId = TARGET))
            assertNull(results.poll(250, TimeUnit.MILLISECONDS))
            assertEquals(3, sockets.get())
        } finally { bonjour.close() }
    }

    @Test fun oneFailedFamilyCannotCancelAWorkingAlternateFamily() {
        exerciseEndpointExhaustion("HTTP/1.1 200 OK", false)
    }

    @Test fun allExplicitlyRejectedFamiliesEndTheCurrentRequestOnlyOnce() {
        exerciseEndpointExhaustion("HTTP/1.1 503 Unavailable", true)
    }

    private fun exerciseEndpointExhaustion(secondStatus: String, shouldExhaust: Boolean) {
        setup()
        val v4 = mock(JmDNS::class.java)
        val v6 = mock(JmDNS::class.java)
        `when`(v4.inetAddress).thenReturn(ipv4)
        `when`(v6.inetAddress).thenReturn(ipv6)
        val events = LinkedBlockingQueue<CarPlayBonjourEvent>()
        val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4), targetBluetoothId = TARGET,
            onEvent = { if (it is CarPlayBonjourEvent.Probed || it is CarPlayBonjourEvent.EndpointsExhausted) events.offer(it) },
            createMdns = { address, _ -> if (address is Inet6Address) v6 else v4 },
            createSocket = {
                object : Socket() {
                    private var ipv6Target = false
                    override fun bind(bindpoint: SocketAddress?) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        ipv6Target = (endpoint as InetSocketAddress).address is Inet6Address
                    }
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream(
                        ((if (ipv6Target) secondStatus else "HTTP/1.1 503 Unavailable") + "\r\n\r\n").toByteArray())
                }
            })
        try {
            bonjour.start()
            val request = bonjour.requestReconnect(TARGET)
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            listener.serviceResolved(resolvedEvent(v4, InetAddress.getByName("192.0.2.20"), bluetoothId = TARGET))
            assertTrue(events.poll(3, TimeUnit.SECONDS) is CarPlayBonjourEvent.Probed)
            assertNull("An undiscovered alternate family retains its chance", events.poll(200, TimeUnit.MILLISECONDS))
            val second = resolvedEvent(v6, InetAddress.getByName("fe80::20"), bluetoothId = TARGET)
            listener.serviceResolved(second)
            assertTrue(events.poll(3, TimeUnit.SECONDS) is CarPlayBonjourEvent.Probed)
            if (shouldExhaust) {
                val exhausted = events.poll(3, TimeUnit.SECONDS) as CarPlayBonjourEvent.EndpointsExhausted
                assertEquals(request, exhausted.requestId)
            }
            repeat(3) { listener.serviceResolved(second) }
            assertNull("Success prevents exhaustion, and exhaustion is emitted once", events.poll(250, TimeUnit.MILLISECONDS))
            val next = bonjour.requestReconnect(TARGET)
            bonjour.finishReconnect(request)
            listener.serviceResolved(second)
            assertEquals(next, (events.poll(3, TimeUnit.SECONDS) as CarPlayBonjourEvent.Probed).requestId)
            assertNull("An old completion cannot exhaust or cancel a new request", events.poll(200, TimeUnit.MILLISECONDS))
        } finally { bonjour.close() }
    }

    private fun resolvedEvent(dns: JmDNS, address: InetAddress, port: Int = 7000,
        bluetoothId: String? = null): javax.jmdns.ServiceEvent {
        val info = mock(ServiceInfo::class.java)
        `when`(info.inetAddresses).thenReturn(arrayOf(address))
        `when`(info.port).thenReturn(port)
        `when`(info.getPropertyString("id")).thenReturn(bluetoothId)
        val event = mock(javax.jmdns.ServiceEvent::class.java)
        `when`(event.dns).thenReturn(dns)
        `when`(event.info).thenReturn(info)
        `when`(event.name).thenReturn("test-phone")
        return event
    }

    private class ProbeSocket(private val v6Reading: CountDownLatch) : Socket() {
        private val released = CountDownLatch(1)
        @Volatile private var closed = false
        private var target: InetAddress? = null
        override fun bind(bindpoint: SocketAddress?) = Unit
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            target = (endpoint as InetSocketAddress).address
        }
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = if (target is Inet6Address) object : InputStream() {
            override fun read(): Int {
                v6Reading.countDown()
                if (!released.await(5, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("test read timed out")
                throw IOException("closed")
            }
        } else ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
        override fun isClosed() = closed
        override fun close() { closed = true; released.countDown() }
    }

    private companion object { const val TARGET = "AA:BB:CC:DD:EE:FF" }
}
