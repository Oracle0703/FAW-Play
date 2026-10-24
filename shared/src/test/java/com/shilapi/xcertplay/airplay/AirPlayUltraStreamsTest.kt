package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class AirPlayUltraStreamsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val secret = ByteArray(32) { (it + 11).toByte() }
    private val base = AirPlayConfig(
        "test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
        AirPlayDisplayConfig(1920, 720), cluster = AirPlayDisplayConfig(1920, 720),
        receiverManagedClusterUi = false, videoInCar = true,
    )
    private val complete = AirPlayUltraReadiness(true, true, true, true, true, true)

    @Test
    fun unreadyOrUnproposedFeatureDoesNotReadKeysCreateHandlerOrAllocateStream() {
        val creations = AtomicInteger()
        val config = configured { creations.incrementAndGet(); RecordingHandler() }
        val reads = AtomicInteger()
        val allocations = AtomicInteger()
        fun streams(c: AirPlayConfig) = AirPlayUltraStreams(
            c, { reads.incrementAndGet(); secret }, { loopback }, { loopback },
            { allocations.incrementAndGet(); 3L }, {},
        )
        streams(config.copy(ultra = config.ultra!!.copy(readiness = AirPlayUltraReadiness()))).use {
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 1L), setOf("uiSync")))
            it.responseSent()
        }
        streams(config).use {
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 1L), emptySet()))
            assertNull(it.setup(request(RcsClientTypes.IAP_CHANNEL, 1L), setOf("iAPChannel")))
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 1.5), setOf("uiSync")))
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, BigInteger.ONE.shiftLeft(65)), setOf("uiSync")))
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 2L) +
                ("streamConnectionID" to 3.5), setOf("uiSync")))
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 2L) +
                ("type" to 130.5), setOf("uiSync")))
        }
        assertEquals(0, reads.get())
        assertEquals(0, creations.get())
        assertEquals(0, allocations.get())
    }

    @Test
    fun callbacksWaitForResponseAndAreNeverAttachedAfterClose() {
        val handlers = Collections.synchronizedList(mutableListOf<RecordingHandler>())
        val streams = manager(configured { RecordingHandler().also { handlers += it } })
        val response = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 11L), setOf("uiSync"))!!
        peer(response, 11L).use { peer ->
            // The socket and decryption can run before SETUP is flushed. Application callbacks
            // must remain suspended until responseSent, including already-received messages.
            peer.sendComm(byteArrayOf(4))
            val handler = handlers.single()
            assertFalse(handler.opened.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, handler.messages.get())
            streams.responseSent()
            assertTrue(handler.opened.await(3, TimeUnit.SECONDS))
            assertTrue(handler.received.await(3, TimeUnit.SECONDS))
            streams.responseSent()
            assertEquals(1, handler.opens.get())
            streams.close()
            assertTrue(handler.closed.await(3, TimeUnit.SECONDS))
            assertEquals(1, handler.closes.get())
            streams.responseSent()
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 12L), setOf("uiSync")))
        }
        streams.close()

        val neverAttached = RecordingHandler()
        manager(configured { neverAttached }).use { closedBeforeResponse ->
            assertNotNull(closedBeforeResponse.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 13L), setOf("uiSync")))
            closedBeforeResponse.close()
            closedBeforeResponse.responseSent()
            assertFalse(neverAttached.opened.await(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun uniqueIdsReplaceOnlyTheirClientAndOldTeardownCannotCloseReplacement() {
        val handlers = Collections.synchronizedList(mutableListOf<RecordingHandler>())
        val next = AtomicLong(3)
        // The same allocator is used by the host for remote-control streams. Reserve one of its
        // IDs between RCS allocations to prove these streams do not assume a dense local range.
        manager(configured { RecordingHandler().also { handlers += it } }, next::getAndIncrement).use { streams ->
            val first = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 21L), setOf("uiSync"))!!
            val videoId = next.getAndIncrement()
            val second = streams.setup(request(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 22L), setOf("vehicleStateProtocol"))!!
            val replacement = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 23L), setOf("uiSync"))!!
            assertEquals(3L, first["streamID"])
            assertEquals(4L, videoId)
            assertEquals(5L, second["streamID"])
            assertEquals(6L, replacement["streamID"])
            streams.responseSent()
            peer(second, 22L).use { cafPeer ->
                peer(replacement, 23L).use { uiPeer ->
                    val caf = handlers[1]
                    val ui = handlers[2]
                    assertTrue(caf.opened.await(3, TimeUnit.SECONDS))
                    assertTrue(ui.opened.await(3, TimeUnit.SECONDS))
                    streams.teardown(teardown(1L)) // iAP
                    streams.teardown(teardown(2L)) // video settings
                    streams.teardown(teardown(videoId)) // video remote control
                    streams.teardown(teardown(first["streamID"]!!)) // old UI Sync stream
                    streams.teardown(teardown(6.0)) // malformed ID must not truncate to 6
                    streams.teardown(mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to null))))
                    caf.stream!!.send(RcsMessage.comm(byteArrayOf(7)))
                    ui.stream!!.send(RcsMessage.comm(byteArrayOf(8)))
                    assertArrayEquals(byteArrayOf(7), cafPeer.receiveNext(3_000)!!.body)
                    assertArrayEquals(byteArrayOf(8), uiPeer.receiveNext(3_000)!!.body)
                    streams.teardown(teardown(second["streamID"]!!))
                    assertTrue(caf.closed.await(3, TimeUnit.SECONDS))
                    assertEquals(0, ui.closes.get())
                    ui.stream!!.send(RcsMessage.comm(byteArrayOf(9)))
                    assertArrayEquals(byteArrayOf(9), uiPeer.receiveNext(3_000)!!.body)
                }
            }
        }
    }

    @Test
    fun usedSeedCannotResetEncryptionCountersAfterReplacementOrTeardown() {
        manager(configured { RecordingHandler() }).use { streams ->
            val first = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 31L), setOf("uiSync"))!!
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 31L), setOf("uiSync")))
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 31L), setOf("vehicleStateProtocol")))
            streams.teardown(teardown(first["streamID"]!!))
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 31L), setOf("uiSync")))
            assertNotNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 32L), setOf("uiSync")))
        }
    }

    @Test
    fun reservedOrDuplicateAllocatedIdRejectsNewStreamWithoutReplacingLiveOne() {
        val creations = AtomicInteger()
        manager(configured { creations.incrementAndGet(); RecordingHandler() }, { 1L }).use {
            assertNull(it.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 41L), setOf("uiSync")))
        }
        val handlers = Collections.synchronizedList(mutableListOf<RecordingHandler>())
        manager(configured { RecordingHandler().also { handlers += it } }, { 3L }).use { streams ->
            val first = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 42L), setOf("uiSync"))!!
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 43L), setOf("vehicleStateProtocol")))
            streams.responseSent()
            peer(first, 42L).use { peer ->
                assertTrue(handlers[0].opened.await(3, TimeUnit.SECONDS))
                handlers[0].stream!!.send(RcsMessage.comm(byteArrayOf(1)))
                assertArrayEquals(byteArrayOf(1), peer.receiveNext(3_000)!!.body)
            }
        }
    }

    @Test
    fun seedReservedByAnotherDataChannelRejectsBeforeHandlerOrPortAllocation() {
        val creations = AtomicInteger()
        val allocations = AtomicInteger()
        val reserved = mutableListOf<String>()
        AirPlayUltraStreams(configured { creations.incrementAndGet(); RecordingHandler() },
            { secret }, { loopback }, { loopback }, { allocations.incrementAndGet(); 3L }, {},
            reserveSeed = { reserved += it; false },
        ).use { streams ->
            assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 51L), setOf("uiSync")))
            assertEquals(listOf("51"), reserved)
            assertEquals(0, creations.get())
            assertEquals(0, allocations.get())
        }
    }

    @Test
    fun managerCloseReturnsAndRejectsWorkWhileStreamCloseHandlerIsBlocked() {
        val opened = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closes = AtomicInteger()
        val handler = object : RcsDataStreamHandler {
            override fun onStreamOpened(stream: RcsDataStream) { opened.countDown() }
            override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                closes.incrementAndGet()
                closeEntered.countDown()
                var interrupted = false
                try {
                    while (true) {
                        try { releaseClose.await(); return } catch (_: InterruptedException) { interrupted = true }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
        val streams = manager(configured { handler })
        val closingThread = Thread({ streams.close(); closeReturned.countDown() }, "test-ultra-close-caller")
            .apply { isDaemon = true }
        try {
            val response = streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 61L), setOf("uiSync"))!!
            streams.responseSent()
            peer(response, 61L).use { peer ->
                assertTrue(opened.await(3, TimeUnit.SECONDS))
                closingThread.start()
                assertTrue(closeEntered.await(3, TimeUnit.SECONDS))
                assertTrue("manager must not wait for application close callbacks", closeReturned.await(1, TimeUnit.SECONDS))
                assertNull(peer.receiveNext(1_000))
                assertTrue(peer.isPeerEnded)
                streams.responseSent()
                assertNull(streams.setup(request(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 62L), setOf("uiSync")))
                assertEquals(1, closes.get())
            }
        } finally {
            releaseClose.countDown()
            streams.close()
            if (closingThread.state != Thread.State.NEW) closingThread.join(3_000)
        }
    }

    private fun configured(create: () -> RcsDataStreamHandler): AirPlayConfig {
        val factory = RcsDataStreamHandlerFactory { create() }
        val vehicleInfo = AirPlayVehicleStateProtocolInfo(pluginConfigs = listOf(
            AirPlayVehicleStateProtocolPlugin(7L),
        ))
        val runtime = AirPlayUltraRuntime.builder()
            .sidecar(AirPlayFeature.UI_SYNC, emptyMap<String, Any?>())
            .sidecar(AirPlayFeature.VEHICLE_STATE_PROTOCOL, vehicleInfo.toInfoResponseMap())
            .rcs(listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2), factory)
            .build()
        return base.copy(ultra = AirPlayUltraConfig(
            cluster = base.cluster!!,
            vehicleStateProtocolInfo = vehicleInfo,
            readyFeatures = setOf(AirPlayFeature.UI_SYNC, AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            runtime = runtime, readiness = complete,
        ))
    }

    private fun manager(config: AirPlayConfig, allocate: () -> Long = AtomicLong(3)::getAndIncrement) =
        AirPlayUltraStreams(config, { secret }, { loopback }, { loopback }, allocate, {})

    private fun request(client: RcsClientType, seed: Any): Map<String, Any?> = mapOf(
        "type" to 130, "clientTypeUUID" to client.uuid.toString(), "seed" to seed,
    )

    private fun teardown(id: Any) = mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to id)))

    private fun peer(response: Map<String, Any?>, seed: Long): RcsChannel {
        fun key(label: String) = AirPlayCrypto.hkdfSha512(secret,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32)
        return RcsChannel.connect(InetSocketAddress(loopback, (response["dataPort"] as Number).toInt()),
            key("DataStream-Input-Encryption-Key"), key("DataStream-Output-Encryption-Key"))
    }

    private class RecordingHandler : RcsDataStreamHandler {
        val opened = CountDownLatch(1)
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val messages = AtomicInteger()
        @Volatile var stream: RcsDataStream? = null
        override fun onStreamOpened(stream: RcsDataStream) {
            this.stream = stream
            opens.incrementAndGet()
            opened.countDown()
        }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            messages.incrementAndGet()
            received.countDown()
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            closes.incrementAndGet()
            closed.countDown()
        }
    }
}
