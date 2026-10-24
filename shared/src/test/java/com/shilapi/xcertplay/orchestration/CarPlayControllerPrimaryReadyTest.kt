package com.shilapi.xcertplay.orchestration

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real RTSP over TCP with the production controller listener, without a display or decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerPrimaryReadyTest {
    @Test fun permanentAndroidFocusLossDoesNotTakeAudioOrRewriteUserIntent() {
        Fixture().use { fixture ->
            val phone = fixture.phone(); phone.record()
            val output = captureEvents(phone)
            val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("carplay_last_user_mode", 0)
            val previous = preferences.all.toMap()
            val revision = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(fixture.controller, "mediaIntentRevision")
            val previousRevision = revision.get()
            fixture.controller.nativeAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS)
            drainInputs(fixture)
            assertEquals(0, output.size())
            assertEquals(previous, preferences.all)
            assertEquals(previousRevision, revision.get())
        }
    }

    @Test fun queuedNativeAudioBorrowRechecksPhoneStateBeforeSending() {
        Fixture().use { fixture ->
            val phone = fixture.phone(); phone.record()
            val output = captureEvents(phone)
            val executor = ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(fixture.controller, "touchExecutor")
            val blocked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            executor.execute { blocked.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
            try {
                assertTrue(blocked.await(3, java.util.concurrent.TimeUnit.SECONDS))
                fixture.controller.nativeAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                val command = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf(
                    "appStates" to listOf(mapOf("appStateID" to 2, "entity" to 1)))))
                assertEquals("200", phone.send("POST", command, "/command").path)
                assertEquals(true, AirPlayPhoneState.active(phone.session))
            } finally { release.countDown() }
            drainInputs(fixture)
            assertEquals(0, output.size())
        }
    }

    @Test fun nativeAudioBorrowStillReturnsDuringAPhoneCall() {
        Fixture().use { fixture ->
            val phone = fixture.phone(); phone.record()
            val output = captureEvents(phone)
            fixture.controller.nativeAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            drainInputs(fixture)
            val command = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf(
                "appStates" to listOf(mapOf("appStateID" to 2, "entity" to 1)))))
            assertEquals("200", phone.send("POST", command, "/command").path)
            fixture.controller.nativeAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_GAIN)
            drainInputs(fixture)
            val commands = RtspMessage.parseMessages(ControlCipher(ByteArray(32), ByteArray(32))
                .decrypt(output.toByteArray()).data).messages.map { BplistCodec.decode(it.body) as Map<*, *> }
            val transfers = commands.map {
                val resource = (((it["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>)
                assertEquals(2L, resource["resourceID"])
                resource["transferType"]
            }
            assertEquals(listOf(3L, 4L), transfers)
        }
    }

    @Test fun oldFocusLossSourceRecordIsNotReplayedAsTakeOnConnect() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(phone.session, "pairVerify"),
                "controllerId", "synthetic-authenticated-phone")
            val saved = CarPlayLastUserMode(RuntimeEnvironment.getApplication()
                .getSharedPreferences("carplay_last_user_mode", 0))
            val owner = Any()
            saved.begin(owner, "synthetic-authenticated-phone")
            saved.audio(owner, false)
            saved.end(owner)
            val output = captureEvents(phone)
            phone.record()
            drainInputs(fixture)
            assertEquals(0, output.size())
            assertTrue(fixture.controller.mainAudioAllowed())
        }
    }

    private fun captureEvents(phone: Phone): ByteArrayOutputStream = ByteArrayOutputStream().also { output ->
        ReflectionHelpers.setField(phone.session, "eventSocket", object : Socket() {
            override fun getOutputStream() = output
        })
        ReflectionHelpers.setField(phone.session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
    }

    private fun drainInputs(fixture: Fixture) =
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(fixture.controller, "touchExecutor")
            .submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

    @Test fun restoringATornDownPrimaryUsesTheLiveControlSessionAndSelectsOnlyTheScreen() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110); phone.setup(111); phone.record()
            val oldInputToken = fixture.controller.activeAirPlaySessionToken()!!
            assertEquals("200", phone.send("TEARDOWN", streams(110)).path)
            assertSame(phone.session, fixture.controller.currentActiveAirPlaySession())
            assertNull(fixture.controller.activeAirPlaySessionToken())
            assertFalse(fixture.controller.sendSiriButton(true, oldInputToken))
            val output = ByteArrayOutputStream()
            ReflectionHelpers.setField(phone.session, "eventSocket", object : Socket() {
                override fun getOutputStream() = output
            })
            ReflectionHelpers.setField(phone.session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))

            assertTrue(fixture.controller.selectProjectionScreen(true))
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(fixture.controller, "touchExecutor")
                .submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            val commands = RtspMessage.parseMessages(ControlCipher(ByteArray(32), ByteArray(32))
                .decrypt(output.toByteArray()).data).messages.map { BplistCodec.decode(it.body) as Map<*, *> }
            val selection = commands.single { it["type"] == "changeModes" }
            val resources = (selection["params"] as Map<*, *>)["resources"] as List<*>
            assertEquals(listOf(mapOf("resourceID" to 1L, "transferType" to 2L)), resources)
            assertEquals("200", phone.send("OPTIONS").path)
            assertFalse(phone.session.isClosed())
            phone.setup(110)
            assertNotNull(fixture.controller.activeAirPlaySessionToken())
            assertNotSame(oldInputToken, fixture.controller.activeAirPlaySessionToken())
        }
    }

    @Test fun physicalSiriReleaseWithAnOldTokenCannotReleaseTheNewPhonesPress() {
        Fixture().use { fixture ->
            fun event(phone: Phone): ByteArrayOutputStream {
                val output = ByteArrayOutputStream()
                ReflectionHelpers.setField(phone.session, "eventSocket", object : Socket() {
                    override fun getOutputStream() = output
                })
                ReflectionHelpers.setField(phone.session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
                return output
            }
            fun drain() = ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(fixture.controller, "touchExecutor")
                .submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val first = fixture.phone(); first.setup(110); first.record(); event(first)
            val firstToken = fixture.controller.activeAirPlaySessionToken()!!
            assertTrue(fixture.controller.sendSiriButton(true, firstToken)); drain()
            val next = fixture.phone(); next.setup(110); next.record()
            val nextEvents = event(next)
            val nextToken = fixture.controller.activeAirPlaySessionToken()!!
            assertTrue(fixture.controller.sendSiriButton(true, nextToken)); drain()
            assertFalse(fixture.controller.sendSiriButton(false, firstToken)); drain()
            fun actions() = RtspMessage.parseMessages(ControlCipher(ByteArray(32), ByteArray(32))
                .decrypt(nextEvents.toByteArray()).data).messages.map {
                ((BplistCodec.decode(it.body) as Map<*, *>)["params"] as Map<*, *>)["siriAction"]
            }
            assertEquals(listOf(2L), actions())
            assertTrue(fixture.controller.sendSiriButton(false, nextToken)); drain()
            assertEquals(listOf(2L, 3L), actions())
        }
    }
    @Test fun networkInvalidationKeepsAnAuthenticatedTcpSessionButRejectsNewSessions() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(phone.session, "pairVerify"),
                "controllerId", "synthetic-authenticated-phone")
            phone.setup(110); phone.record()
            var releases = 0
            val network = object : com.shilapi.xcertplay.network.WirelessHotspotManager {
                override fun start(timeoutMillis: Long): com.shilapi.xcertplay.network.WirelessHotspotInfo = error("unused")
                override fun close() { releases++ }
            }
            ReflectionHelpers.setField(fixture.controller, "hotspot", network)
            fixture.controller.javaClass.getDeclaredMethod("onHotspotRuntimeFailure",
                com.shilapi.xcertplay.network.WirelessHotspotManager::class.java, java.io.IOException::class.java)
                .apply { isAccessible = true }.invoke(fixture.controller, network,
                    com.shilapi.xcertplay.network.WirelessStartupException(
                        com.shilapi.xcertplay.network.WirelessStartupFailure.HOTSPOT_NOT_READY, "synthetic link loss"))
            ReflectionHelpers.getField<java.util.concurrent.ScheduledExecutorService>(fixture.controller, "startupTimer")
                .submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, releases)
            assertFalse(phone.session.isClosed())
            assertEquals("200", phone.send("OPTIONS").path)
            assertSame(phone.session, fixture.controller.currentActiveAirPlaySession())
            val newcomer = fixture.phone()
            assertEquals("403", newcomer.send("RECORD").path)
            phone.session.close()
            assertNull(fixture.controller.currentActiveAirPlaySession())
            assertFalse(fixture.callbacks.authorizeSession(newcomer.session))
        }
    }

    @Test fun actualModesChangedCommandReachesTheMediaOwnerThroughTheWirelessListener() {
        Fixture().use { fixture ->
            val phone = fixture.phone(); phone.record()
            val command = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf(
                "resources" to listOf(mapOf("resourceID" to 1, "entity" to 1),
                    mapOf("resourceID" to 2, "entity" to 2)))))
            assertEquals("200", phone.send("POST", command, "/command").path)
            assertEquals(AirPlayResourceModes.State(AirPlayResourceModes.Entity.IPHONE,
                AirPlayResourceModes.Entity.VEHICLE), fixture.resourceStates.last())
            assertTrue(fixture.controller.mainScreenAllowed())
            assertFalse(fixture.controller.mainAudioAllowed())
            assertEquals("200", phone.send("POST", command, "/command").path)
            assertFalse(fixture.controller.mainAudioAllowed())
        }
    }
    @Test fun setupBeforeRecordNotifiesAfterAdoptionWithoutWaitingForARenderedFrame() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110)
            assertTrue(fixture.events.isEmpty())
            assertNotNull(phone.session.mainScreenSessionToken())
            phone.record()
            assertEquals(listOf("active", "ready"), fixture.events)
            assertSame(phone.session, fixture.ready.single())
            assertFalse(phone.session.hasRenderedMainScreen())
        }
    }

    @Test fun recordBeforeSetupWaitsForPrimaryAndAnAcceptedAlternateDoesNotOpenTheHost() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.record()
            assertEquals(listOf("active"), fixture.events)
            val alternate = phone.setup(111)
            assertEquals(111L, ((BplistCodec.decode(alternate.body) as Map<*, *>)["streams"] as List<*>)
                .single().let { (it as Map<*, *>)["type"] })
            assertTrue(fixture.ready.isEmpty())
            assertNull(phone.session.mainScreenSessionToken())
            phone.setup(110)
            assertEquals(listOf("active", "ready"), fixture.events)
            assertFalse(phone.session.hasRenderedMainScreen())
        }
    }

    @Test fun rejectedAndUnauthorizedPrimaryStreamsCannotPublishReadiness() {
        Fixture(acceptPrimary = false).use { fixture ->
            val phone = fixture.phone()
            phone.record()
            phone.setup(110)
            assertTrue(fixture.ready.isEmpty())
            assertNull(phone.session.mainScreenSessionToken())
        }
        Fixture().use { fixture ->
            fixture.allowed = false
            val phone = fixture.phone()
            assertEquals("403", phone.send("SETUP", streams(110)).path)
            assertEquals("403", phone.send("RECORD").path)
            assertTrue(fixture.ready.isEmpty())
            assertTrue(fixture.events.isEmpty())
        }
    }

    @Test fun foregroundAdoptionReplaysReadyWithoutInventingARenderedFrame() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110)
            phone.record()
            val replay = CopyOnWriteArrayList<String>()
            val listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { replay += "active" }
                override fun onMainScreenReady(session: AirPlaySession) { replay += "ready" }
                override fun onVideoFrameRendered(session: AirPlaySession) { replay += "rendered" }
            }
            fixture.controller.attachUi(listener) {}
            fixture.controller.replayActiveSession(listener)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("active", "ready"), replay)
        }
    }

    @Test fun replayStopsWhenSessionActiveCallbackReplacesItsListener() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110)
            phone.record()
            val replay = CopyOnWriteArrayList<String>()
            val listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) {
                    replay += "active"
                    fixture.controller.attachUi(object : AirPlaySessionListener {}) {}
                }
                override fun onMainScreenReady(session: AirPlaySession) { replay += "ready" }
            }
            fixture.controller.attachUi(listener) {}
            fixture.controller.replayActiveSession(listener)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("active"), replay)
        }
    }

    @Test fun oldSessionAndStaleGenerationCannotPublishCurrentPrimaryReadiness() {
        Fixture().use { fixture ->
            val old = fixture.phone()
            old.setup(110)
            old.record()
            val current = fixture.phone()
            current.setup(110)
            current.record()
            fixture.ready.clear()
            old.setup(110)
            assertTrue(fixture.ready.isEmpty())
            fixture.callbacks.onMainScreenReady(old.session)
            assertTrue(fixture.ready.isEmpty())
            fixture.generation.incrementAndGet()
            fixture.callbacks.onMainScreenReady(current.session)
            assertTrue(fixture.ready.isEmpty())
        }
    }

    @Test fun activeCallbackCannotPublishReadinessAfterStartingAnotherWirelessGeneration() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110)
            fixture.onActive = { fixture.generation.incrementAndGet() }
            phone.record()
            assertEquals(listOf("active"), fixture.events)
            assertTrue(fixture.ready.isEmpty())
        }
    }

    @Test fun replayCannotRevivePrimaryReadinessFromAnInvalidatedWirelessGeneration() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.setup(110)
            phone.record()
            fixture.generation.incrementAndGet()
            val replay = CopyOnWriteArrayList<String>()
            val listener = object : AirPlaySessionListener {
                override fun onMainScreenReady(session: AirPlaySession) { replay += "ready" }
            }
            fixture.controller.attachUi(listener) {}
            fixture.controller.replayActiveSession(listener)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(replay.isEmpty())
        }
    }

    @Test fun teardownAndCloseWithdrawReadinessFromSubsequentReplays() {
        Fixture().use { fixture ->
            val phone = fixture.phone()
            phone.record()
            phone.setup(110)
            phone.send("TEARDOWN", streams(110))
            assertNull(phone.session.mainScreenSessionToken())
            fixture.ready.clear()
            fixture.callbacks.onMainScreenReady(phone.session)
            val replay = CopyOnWriteArrayList<String>()
            val listener = object : AirPlaySessionListener {
                override fun onMainScreenReady(session: AirPlaySession) { replay += "ready" }
            }
            fixture.controller.attachUi(listener) {}
            fixture.controller.replayActiveSession(listener)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(fixture.ready.isEmpty())
            assertTrue(replay.isEmpty())
            phone.session.close()
            fixture.controller.replayActiveSession(listener)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(replay.isEmpty())
        }
    }

    private class Fixture(private val acceptPrimary: Boolean = true) : Closeable {
        val events = CopyOnWriteArrayList<String>()
        val ready = CopyOnWriteArrayList<AirPlaySession>()
        val resourceStates = CopyOnWriteArrayList<AirPlayResourceModes.State>()
        @Volatile var allowed = true
        @Volatile var onActive: (() -> Unit)? = null
        private val phones = mutableListOf<Phone>()
        private val display = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(800, 480, initialUrl = "maps:cluster"))
        private val media = object : AirPlayMediaHandler {
            override fun onResourceModes(session: AirPlaySession, state: AirPlayResourceModes.State) { resourceStates += state }
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? =
                if (type == 110 && !acceptPrimary) null else 32000
        }
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            display, AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { events += "active"; onActive?.invoke() }
                override fun onMainScreenReady(session: AirPlaySession) { ready += session; events += "ready" }
                override fun onVideoFrameRendered(session: AirPlaySession) { events += "rendered" }
            }, media, {})
        val generation = ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration")
        val callbacks: AirPlaySessionListener

        init {
            val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            phase.set(controller, phase.type.enumConstants.single { it.toString() == "WIRELESS" })
            generation.set(1)
            callbacks = controller.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
                FirstTcpWatchdog::class.java).apply { isAccessible = true }
                .invoke(controller, 1, FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})) as AirPlaySessionListener
        }

        fun phone(): Phone {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val peer = Socket("127.0.0.1", server.localPort).apply { soTimeout = 3_000 }
            val socket = server.accept()
            server.close()
            val session = AirPlaySession(socket, display, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener by callbacks {
                    override fun authorizeSession(session: AirPlaySession): Boolean = allowed && callbacks.authorizeSession(session)
                }, media)
            return Phone(peer, session).also { phones += it; session.start() }
        }

        override fun close() {
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
            phones.forEach { it.close() }
        }
    }

    private class Phone(private val peer: Socket, val session: AirPlaySession) : Closeable {
        private var sequence = 0
        fun setup(type: Int): RtspMessage.Request = send("SETUP", streams(type)).also { assertEquals("200", it.path) }
        fun record() { assertEquals("200", send("RECORD").path) }

        fun send(method: String, body: ByteArray = ByteArray(0), path: String = "*"): RtspMessage.Request {
            val header = "$method $path RTSP/1.0\r\nCSeq: ${++sequence}\r\nContent-Length: ${body.size}\r\n\r\n"
            peer.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            val input = peer.getInputStream()
            val response = ByteArrayOutputStream()
            var ending = 0
            while (ending != 0x0d0a0d0a) {
                val byte = input.read()
                check(byte >= 0) { "Control connection ended before a complete response" }
                response.write(byte)
                ending = (ending shl 8) or byte
                check(response.size() <= RtspMessage.MAX_HEADER_BYTES)
            }
            val headers = response.toString("US-ASCII")
            val length = headers.lineSequence().first { it.startsWith("Content-Length:", ignoreCase = true) }
                .substringAfter(':').trim().toInt()
            repeat(length) {
                val byte = input.read()
                check(byte >= 0) { "Control connection ended before its response body" }
                response.write(byte)
            }
            return RtspMessage.parseMessages(response.toByteArray()).messages.single()
        }

        override fun close() {
            session.close()
            peer.close()
            assertTrue(session.awaitClosed(3_000))
        }
    }

    companion object {
        private fun streams(type: Int) = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to type.toLong()))))
    }
}
