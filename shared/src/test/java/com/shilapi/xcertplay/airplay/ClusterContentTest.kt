package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live dashboard selections must not claim delivery before their commands reach the event channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterContentTest {
    private fun session(initialUrl: String, control: Socket = Socket(), receiverManagedClusterUi: Boolean = true,
        onTeardown: (AirPlaySession) -> Unit = {},
        onProgress: (ClusterContentProgress) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = control,
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600, initialUrl = initialUrl),
            receiverManagedClusterUi = receiverManagedClusterUi),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) { onProgress(progress) }
        }, media = object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            override fun onTeardown(session: AirPlaySession, type: Int) { onTeardown(session) }
        },
    )

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 111L)))
    }

    private fun teardown(session: AirPlaySession) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 111L)))))
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, request)
    }

    @Test fun aFailedShowUiMustNotClaimThatATurnCardHasBecomeAMap() {
        val initial = CarPlayClusterDisplay.Content.TURN_CARD.url
        val session = session(initial)
        try {
            setup(session)
            readyAfterSetup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true)) // No event connection.
            assertNull("No initial page or content switch was delivered", session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun writtenRequestsReceivedPicturesAndRenderedFramesRemainSeparateFacts() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val source = Any()
        try {
            session.videoSourceStarted(111, source)
            setup(session)
            eventReady(session)
            assertFalse(session.setClusterUiShown(true)) // SETUP has not been flushed.
            assertFalse(session.clusterContentProgress()!!.requestWritten)
            session.videoFrameReceived(111, source)
            assertFalse(session.clusterContentProgress()!!.validVideoReceived)
            readyAfterSetup(session)
            val written = session.clusterContentProgress()!!
            assertTrue(written.requestWritten)
            assertFalse(written.validVideoReceived)
            assertFalse(written.frameRendered)
            session.videoFrameReceived(111, Any())
            assertFalse(session.clusterContentProgress()!!.validVideoReceived)
            session.videoFrameReceived(111, source)
            assertTrue(session.clusterContentProgress()!!.validVideoReceived)
            assertFalse(session.clusterContentProgress()!!.frameRendered)
            session.videoFrameRendered(111, source)
            assertTrue(session.clusterContentProgress()!!.frameRendered)
        } finally { session.close() }
    }

    @Test fun sourceReplacementAndHideRejectLateProgressFromThePreviousRequest() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val oldSource = Any()
        val newSource = Any()
        try {
            session.videoSourceStarted(111, oldSource)
            setup(session)
            eventReady(session)
            readyAfterSetup(session)
            session.videoFrameReceived(111, oldSource)
            val oldRevision = session.clusterContentProgress()!!.revision
            session.videoSourceStarted(111, newSource)
            setup(session)
            readyAfterSetup(session)
            assertTrue(session.clusterContentProgress()!!.revision > oldRevision)
            session.videoFrameReceived(111, oldSource)
            session.videoFrameRendered(111, oldSource)
            assertFalse(session.clusterContentProgress()!!.validVideoReceived)
            assertFalse(session.clusterContentProgress()!!.frameRendered)
            assertTrue(session.setClusterUiShown(false))
            session.videoFrameReceived(111, newSource)
            session.videoFrameRendered(111, newSource)
            assertFalse(session.clusterContentProgress()!!.shown)
            assertFalse(session.clusterContentProgress()!!.validVideoReceived)
            assertTrue(session.setClusterUiShown(true))
            session.videoFrameReceived(111, newSource)
            assertTrue(session.clusterContentProgress()!!.validVideoReceived)
        } finally { session.close() }
    }

    @Test fun delayedProgressFromAnOldRequestCannotCancelTheNewRequestsTimer() {
        for (rendered in listOf(false, true)) {
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            var oldRevision = -1L
            val session = session(CarPlayClusterDisplay.MAP_URL, onProgress = { progress ->
                if (progress.revision == oldRevision && (if (rendered) progress.frameRendered else progress.validVideoReceived)) {
                    paused.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
            })
            val source = Any()
            val callback = Thread {
                try {
                    if (rendered) session.videoFrameRendered(111, source) else session.videoFrameReceived(111, source)
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                session.videoSourceStarted(111, source)
                setup(session)
                eventReady(session)
                readyAfterSetup(session)
                oldRevision = session.clusterContentProgress()!!.revision
                callback.start()
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
                val futureField = session.javaClass.getDeclaredField("clusterProgressCheck").apply { isAccessible = true }
                val currentTimer = futureField.get(session) as java.util.concurrent.ScheduledFuture<*>
                assertFalse(currentTimer.isCancelled)
                resume.countDown()
                callback.join(5_000)
                assertFalse(callback.isAlive)
                assertNull(failure.get())
                assertSame("Old progress may cancel only its own request timer", currentTimer, futureField.get(session))
                assertFalse(currentTimer.isCancelled)
            } finally { resume.countDown(); callback.join(5_000); session.close() }
        }
    }

    @Test fun aRecreatedStreamMustNotReportThePreviousStreamsUrlAsAlreadyShown() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            assertNull(session.clusterUrl())
            teardown(session)
            assertEquals(0, session.clusterStream)
            setup(session)
            assertEquals(2, session.clusterStream)
            assertNull("A new cluster stream has no confirmed content request yet", session.clusterUrl())
        } finally { session.close() }
    }

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private fun eventReady(session: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        return event
    }

    private fun readyAfterSetup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(session)
    }

    private fun commands(event: EventSocket): List<Map<String, Any?>> {
        val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(event.bytes.toByteArray()).data
        return RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<String, Any?> }
    }

    @Test fun aSuccessfulSwitchPublishesTheDeliveredUrlAndInvalidatesTheOldRoute() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            readyAfterSetup(session)
            val before = session.clusterContentRoute()!!.third
            val event = eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertNotSame(before, session.clusterContentRoute()!!.third)
            val commands = commands(event)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
            assertEquals(CarPlayClusterDisplay.MAP_URL, (commands.first()["params"] as Map<*, *>)["url"])
        } finally { session.close() }
    }

    @Test fun nativeModesUseTheAltScreenWithoutReplacingTheMainCarPlayScreen() {
        for (content in listOf(CarPlayClusterDisplay.Content.INSTRUMENTS, CarPlayClusterDisplay.Content.TURN_CARD)) {
            val session = session(CarPlayClusterDisplay.MAP_URL)
            try {
                setup(session)
                readyAfterSetup(session)
                val event = eventReady(session)
                assertTrue(session.setClusterUrl(content.url, send = true))
                val commands = commands(event)
                assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
                assertEquals(content.url, (commands.first()["params"] as Map<*, *>)["url"])
                assertEquals(AirPlayInfoPlist.ALT_UUID, (commands.first()["params"] as Map<*, *>)["uuid"])
                assertEquals(content.url, session.clusterUrl())
                assertEquals(1, session.clusterStream)
            } finally { session.close() }
        }
    }

    @Test fun aPausedSelectionStaysPendingUntilResume() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            readyAfterSetup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            assertTrue(session.setClusterUiShown(false))
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            readyAfterSetup(session) // A ready-channel notification must not undo a pause.
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertEquals(3, event.writes)
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            assertEquals(listOf("showUI", "forceKeyFrame", "stopUI", "showUI", "forceKeyFrame"),
                commands(event).map { it["type"] })
        } finally { session.close() }
    }

    @Test fun uiContextSuggestionCannotResumeAPausedClusterOrBypassItsContentRoute() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            val control = session.javaClass.getDeclaredField("viewAreaControl").apply { isAccessible = true }
                .get(session) as AirPlayViewAreaControl
            control.negotiate(listOf("altScreen", "uiContext"), true)
            setup(session)
            readyAfterSetup(session)
            val event = eventReady(session)
            assertTrue(session.setClusterUiShown(false))
            control.responseStarted()
            control.receive("changeUIContext", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
                "url" to CarPlayClusterDisplay.Content.TURN_CARD.url))
            control.responseSent()
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
            assertNull(session.clusterUrl())
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { session.close() }
    }

    @Test fun defaultNativeEtaPagesSurviveLiveSelectionPauseAndStreamRecreation() {
        for (content in listOf(CarPlayClusterDisplay.Content.INSTRUMENTS, CarPlayClusterDisplay.Content.MAP)) {
            val requested = content.url
            val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
            try {
                setup(session)
                readyAfterSetup(session)
                val event = eventReady(session)
                assertTrue(session.setClusterUrl(requested, send = true))
                assertTrue(session.setClusterUiShown(false))
                assertTrue(session.setClusterUiShown(true))
                teardown(session)
                setup(session)
                assertNull(session.clusterUrl())
                readyAfterSetup(session)

                val shown = commands(event).filter { it["type"] == "showUI" }
                assertEquals("Live selection, resume and recreated stream must each request ETA", 3, shown.size)
                for (command in shown) {
                    val params = command["params"] as Map<*, *>
                    assertEquals(requested, params["url"])
                    assertEquals(AirPlayInfoPlist.ALT_UUID, params["uuid"])
                }
                assertEquals(requested, session.clusterUrl())
                assertEquals(2, session.clusterStream)
            } finally { session.close() }
        }
    }

    @Test fun anUnavailableEventChannelKeepsTheOldUrlUntilRetryActuallyDelivers() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            readyAfterSetup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertNull(session.clusterUrl())
            eventReady(session)
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamReappliesTheSelectionAfterSetupWithoutClaimingItEarly() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            readyAfterSetup(session)
            eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
            teardown(session)
            assertNull(session.clusterContentRoute())
            setup(session)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aStreamReplacementDuringDeliveryCannotPublishTheOldResultForTheNewStream() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            readyAfterSetup(session)
            eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl()) // In-flight showUI may have reached either generation.
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aClosedSessionHasNoContentRouteAndCannotAcceptSelections() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        setup(session)
        val event = eventReady(session)
        session.close()
        assertEquals(0, session.clusterStream)
        assertNull(session.clusterContentRoute())
        assertFalse(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
        assertEquals(0, event.writes)
    }

    @Test fun aStopUiThatOutlastsStreamReplacementInvalidatesTheNewRoute() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            readyAfterSetup(session)
            val event = eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUiShown(false))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(2, event.writes) // Reapply the retained stop to the new stream, never a show.
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun teardownInvalidatesTheRouteBeforeAnUnsuccessfulMediaCallback() {
        var checked = false
        val session = session(CarPlayClusterDisplay.MAP_URL, onTeardown = {
            assertEquals(0, it.clusterStream)
            assertNull(it.clusterContentRoute())
            checked = true
            throw java.io.IOException("media teardown failed")
        })
        try {
            setup(session)
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { teardown(session) }
            assertTrue(failure.cause is java.io.IOException)
            assertTrue(checked)
            assertNull(session.clusterContentRoute())
        } finally { session.close() }
    }

    @Test fun legacyAlternateWithoutUiContextExplicitlyRequestsItsFirstPageOnceReady() {
        val session = session(CarPlayClusterDisplay.Content.INSTRUMENTS.url, receiverManagedClusterUi = false)
        try {
            val control = session.javaClass.getDeclaredField("viewAreaControl").apply { isAccessible = true }
                .get(session) as AirPlayViewAreaControl
            control.negotiate(listOf("viewAreas", "altScreen", "iAPChannel"), true)
            setup(session)
            assertNull(session.clusterUrl())
            readyAfterSetup(session) // No event connection yet; keep the request pending.
            val event = eventReady(session)
            readyAfterSetup(session)
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url,
                (commands(event).first()["params"] as Map<*, *>)["url"])
            control.receive("suggestUI", mapOf("urls" to listOf(CarPlayClusterDisplay.MAP_URL)))
            control.flush()
            assertEquals(2, event.writes) // Do not broaden an unnegotiated uiContext contract.
        } finally { session.close() }
    }

    @Test fun aHideBeforeSetupAndFailedStopSurvivesReadyEventsAndStreamReplacement() {
        val session = session(CarPlayClusterDisplay.Content.INSTRUMENTS.url)
        try {
            assertFalse(session.setClusterUiShown(false))
            setup(session)
            readyAfterSetup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
            teardown(session)
            setup(session)
            readyAfterSetup(session)
            assertEquals(listOf("stopUI", "stopUI"), commands(event).map { it["type"] })
            assertTrue(session.setClusterUiShown(true))
            assertEquals(listOf("stopUI", "stopUI", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun concurrentContentRequestsWaitForTheReplacementSetupResponseFlushAndKeepTheLatestIntent() {
        for (shown in listOf(false, true)) {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 111L))))
            val request = "SETUP /stream RTSP/1.0\r\nCSeq: 91\r\nContent-Length: ${body.size}\r\n\r\n"
                .toByteArray() + body
            val control = BlockedFlushSocket(request)
            val session = session(CarPlayClusterDisplay.Content.INSTRUMENTS.url, control)
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    session.javaClass.getDeclaredMethod("runControl").apply { isAccessible = true }.invoke(session)
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                setup(session)
                readyAfterSetup(session)
                val event = eventReady(session)
                worker.start() // The already-active session receives an actual alternate re-SETUP.
                assertTrue(control.flushing.await(3, TimeUnit.SECONDS))
                assertEquals(2, session.clusterStream)

                assertFalse(session.setClusterUiShown(false))
                assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
                assertFalse(session.restoreClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
                assertFalse(session.setClusterUiShown(true)) // Same page re-request, like reopen/route start.
                if (!shown) assertFalse(session.setClusterUiShown(false))
                assertEquals("No show, stop or keyframe before the SETUP response flush", 0, event.writes)
                assertNull(session.clusterUrl())
                assertNull(session.clusterContentRoute()!!.second)

                control.release.countDown()
                worker.join(3_000)
                assertFalse("Control receive loop must finish", worker.isAlive)
                assertNull(failure.get())
                assertEquals("200", RtspMessage.parseMessages(control.output.toByteArray()).messages.single().path)
                val written = commands(event)
                assertEquals(if (shown) listOf("showUI", "forceKeyFrame") else listOf("stopUI"),
                    written.map { it["type"] })
                if (shown) assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url,
                    (written.first()["params"] as Map<*, *>)["url"])
            } finally {
                control.release.countDown()
                worker.join(3_000)
                session.close()
            }
        }
    }

    @Test fun aVisibilityAdmissionForAnOldStreamCannotChangeTheReplacementStreamOrItsRetainedIntent() {
        val session = session(CarPlayClusterDisplay.Content.INSTRUMENTS.url)
        try {
            setup(session)
            readyAfterSetup(session)
            val oldStream = session.clusterStream
            setup(session)
            readyAfterSetup(session)
            val event = eventReady(session)
            assertFalse(session.setClusterUiShown(false, expectedStream = oldStream))
            assertEquals(0, event.writes)
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { session.close() }
    }

    private class BlockedFlushSocket(request: ByteArray) : Socket() {
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(request)
        override fun getInputStream() = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { output.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) { output.write(data, offset, length) }
            override fun flush() {
                flushing.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Test did not release the SETUP response" }
            }
        }
    }
}
