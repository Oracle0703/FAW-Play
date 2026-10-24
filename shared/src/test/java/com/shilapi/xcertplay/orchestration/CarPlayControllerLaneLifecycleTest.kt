package com.shilapi.xcertplay.orchestration

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerLaneLifecycleTest {
    private lateinit var controller: CarPlayController
    private lateinit var callbacks: AirPlaySessionListener
    private var testLink: Any = Any()
    private val phones = mutableListOf<AirPlaySession>()
    private val displayed = AtomicReference<ClusterLaneGuidance?>()
    private val turnDisplayed = AtomicReference<ClusterTurnGuidance?>()
    private val stateEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))

    @Before fun setup() {
        AutunnNavigationOutputs.endNow()
        AutunnNavigationOutputs.setLaneOverlayListener { displayed.set(it) }
        AutunnNavigationOutputs.setTurnOverlayListener { turnDisplayed.set(it) }
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity(), PairingStore(), object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { stateEvents.add("active") }
                override fun onNavigationDataReceived(session: AirPlaySession) { stateEvents.add("navigation") }
            },
            object : AirPlayMediaHandler {}, {})
        callbacks = ReflectionHelpers.getField(controller, "sessionListener")
        assertTrue(beginLink(testLink))
    }

    @After fun cleanup() {
        controller.close()
        assertTrue(controller.awaitClosed(3_000))
        phones.forEach { it.close() }
        AutunnNavigationOutputs.setLaneOverlayListener(null)
        AutunnNavigationOutputs.setTurnOverlayListener(null)
        AutunnNavigationOutputs.endNow()
    }

    @Test fun firstLinkCanPrefetchDetailsBeforeTheFirstActiveSession() {
        frame(detail(7))
        assertNull(displayed.get())
        activate(phone())
        frame(selection(7))
        assertEquals(7, displayed.get()!!.index)
    }

    @Test fun prefetchedNavigationIsReportedAfterActiveAndOncePerCurrentLink() {
        frame(detail(7))
        assertTrue(stateEvents.isEmpty())
        activate(phone())
        assertEquals(listOf("active", "navigation"), stateEvents.toList())
        frame(selection(7))
        assertEquals(listOf("active", "navigation"), stateEvents.toList())
        val oldLink = testLink
        testLink = Any()
        assertTrue(beginLink(testLink))
        frame(oldLink, detail(8))
        assertEquals(2, stateEvents.size)
        frame(detail(8))
        assertEquals(listOf("active", "navigation", "navigation"), stateEvents.toList())
    }

    @Test fun foregroundAdoptionReplaysCurrentNavigationOnlyAfterSessionActive() {
        activate(phone())
        publish(7)
        val adopted = mutableListOf<String>()
        val listener = object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) { adopted.add("active") }
            override fun onNavigationDataReceived(session: AirPlaySession) { adopted.add("navigation") }
        }
        controller.attachUi(listener, {})
        controller.replayActiveSession(listener)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("active", "navigation"), adopted)
    }

    @Test fun earlyDisconnectClearsLanesAndBlocksTeardownFramesBeforeAndAfterEnded() {
        val phone = phone()
        activate(phone)
        publish(7)
        assertNotNull(displayed.get())
        callbacks.onSessionDisconnected(phone)
        assertNull(displayed.get())
        assertSame(phone, ReflectionHelpers.getField(controller, "activeSession"))
        publish(7)
        assertNull(displayed.get())
        callbacks.onSessionEnded(phone)
        publish(7)
        assertNull(displayed.get())
    }

    @Test fun endWithoutEarlyDisconnectAlsoWithdrawsPermissionUntilANewActiveSession() {
        val first = phone()
        activate(first)
        publish(7)
        callbacks.onSessionEnded(first)
        assertNull(displayed.get())
        publish(8)
        assertNull(displayed.get())
        activate(phone())
        frame(selection(8))
        assertNull(displayed.get()) // Rejected teardown details were not cached.
        frame(detail(8))
        assertEquals(8, displayed.get()!!.index)
    }

    @Test fun replacementReenablesFreshLanesAndIgnoresLateOldLifecycleCallbacks() {
        val first = phone()
        activate(first)
        publish(7)
        callbacks.onSessionDisconnected(first)
        val replacement = phone()
        activate(replacement)
        frame(selection(7))
        assertNull(displayed.get()) // Disconnect discarded the previous connection's cache.
        publish(8)
        callbacks.onSessionDisconnected(first)
        callbacks.onSessionEnded(first)
        assertEquals(8, displayed.get()!!.index)
        assertSame(replacement, ReflectionHelpers.getField(controller, "activeSession"))
    }

    @Test fun aReplacementLinkPreservesOneShotTurnAndLaneDetailsUntilActivation() {
        val first = phone()
        val oldLink = Any()
        beginLink(oldLink)
        activate(first)
        callbacks.onSessionDisconnected(first)

        val freshLink = Any()
        beginLink(freshLink)
        frame(freshLink, turnDetail(8))
        frame(freshLink, detail(8))
        frame(freshLink, turnAndLaneSelection(8))
        assertNull(turnDisplayed.get())
        assertNull(displayed.get())
        // Slow cleanup of the old AirPlay session must not discard the new link's prefetch.
        callbacks.onSessionEnded(first)
        activate(phone())
        assertEquals(140, turnDisplayed.get()!!.distanceMeters)
        assertEquals(8, displayed.get()!!.index)
        assertEquals(0, pendingFrames())
    }

    @Test fun anOldLinkCannotSupplyDetailsToTheReplacementBeforeOrAfterActivation() {
        val first = phone()
        val oldLink = Any()
        beginLink(oldLink)
        activate(first)
        callbacks.onSessionDisconnected(first)
        val freshLink = Any()
        beginLink(freshLink)
        frame(oldLink, detail(8))
        frame(oldLink, selection(8))
        frame(freshLink, selection(8))
        activate(phone())
        assertNull(displayed.get())
        frame(oldLink, detail(8))
        assertNull(displayed.get())
        frame(freshLink, detail(8))
        assertEquals(8, displayed.get()!!.index)
        frame(oldLink, selection(9))
        assertEquals(8, displayed.get()!!.index)
    }

    @Test fun replacingOrDisconnectingALinkDiscardsItsPendingAndLateFrames() {
        val first = phone()
        activate(first)
        callbacks.onSessionDisconnected(first)
        val abandonedLink = Any()
        beginLink(abandonedLink)
        frame(abandonedLink, detail(8))
        frame(abandonedLink, selection(8))
        val replacementLink = Any()
        beginLink(replacementLink)
        frame(replacementLink, selection(8))
        val replacement = phone()
        activate(replacement)
        assertNull(displayed.get())
        frame(replacementLink, detail(8))
        assertEquals(8, displayed.get()!!.index)
        callbacks.onSessionDisconnected(replacement)
        frame(replacementLink, detail(8))
        frame(replacementLink, selection(8))
        activate(phone())
        assertNull(displayed.get())
        assertEquals(0, pendingFrames())
    }

    @Test fun preActivationNavigationStorageHasBothFrameAndByteLimitsAndCloseClearsIt() {
        val first = phone()
        activate(first)
        callbacks.onSessionDisconnected(first)
        val freshLink = Any()
        beginLink(freshLink)
        repeat(300) { frame(freshLink, detail(it)) }
        assertEquals(256, pendingFrames())
        repeat(8) { frame(freshLink, Iap2Frame(0x5202, ByteArray(60_000))) }
        assertEquals(4, pendingFrames())
        assertEquals(240_000, ReflectionHelpers.getField<Int>(controller, "pendingNavigationBytes"))
        controller.close()
        assertEquals(0, pendingFrames())
        assertEquals(0, ReflectionHelpers.getField<Int>(controller, "pendingNavigationBytes"))
        assertFalse(beginLink(Any()))
    }

    @Test fun aClosedActiveSessionCannotFeedLanesBeforeItsDisconnectCallback() {
        val phone = phone()
        activate(phone)
        phone.close()
        publish(7)
        assertNull(displayed.get())
    }

    @Test fun probeDisconnectAndEndDoNotClearTheCurrentPhonesLanes() {
        val main = phone()
        activate(main)
        publish(7)
        val probe = phone()
        callbacks.onSessionDisconnected(probe)
        callbacks.onSessionEnded(probe)
        assertEquals(7, displayed.get()!!.index)
        assertSame(main, ReflectionHelpers.getField(controller, "activeSession"))
    }

    @Test fun closeClearsImmediatelyAndLateActiveCannotAuthorizeMoreFrames() {
        activate(phone())
        publish(7)
        controller.close()
        assertNull(displayed.get())
        activate(phone())
        publish(8)
        assertNull(displayed.get())
    }

    @Test fun inFlightNavigationPublicationCompletesBeforeDisconnectClearsIt() {
        val phone = phone()
        activate(phone)
        frame(detail(7))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnectStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        AutunnNavigationOutputs.setLaneOverlayListener {
            displayed.set(it)
            if (it != null) {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
        }
        try {
            val publication = workers.submit { frame(selection(7)) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val disconnect = workers.submit {
                disconnectStarted.countDown()
                callbacks.onSessionDisconnected(phone)
            }
            assertTrue(disconnectStarted.await(3, TimeUnit.SECONDS))
            release.countDown()
            publication.get(3, TimeUnit.SECONDS)
            disconnect.get(3, TimeUnit.SECONDS)
            assertNull(displayed.get())
            publish(8)
            assertNull(displayed.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun musicMetadataStillFlowsWhileVehicleNavigationIsDisabled() {
        val phone = phone()
        activate(phone)
        callbacks.onSessionDisconnected(phone)
        var music: CarPlayNowPlaying? = null
        controller.nowPlayingListener = { music = it }
        frame(Iap2Messages.buildRaw(0x5001) {
            group(0) { string(1, "Test song") }
            group(1) { u8(0, 1) }
        })
        assertEquals("Test song", music!!.title)
        assertTrue(music!!.playing)
        assertNull(displayed.get())
    }

    private fun publish(index: Int) {
        frame(detail(index))
        frame(selection(index))
    }

    private fun activate(session: AirPlaySession) {
        if (ReflectionHelpers.getField<Any?>(controller, "vehicleNavigationLink") == null) {
            beginLink(Any())
        }
        callbacks.onSessionActive(session)
    }

    private fun frame(frame: Iap2Frame) = frame(testLink, frame)

    private fun frame(link: Any, frame: Iap2Frame) {
        controller.javaClass.getDeclaredMethod("onRouteFrame", Iap2Frame::class.java, Any::class.java)
            .apply { isAccessible = true }.invoke(controller, frame, link)
    }

    private fun beginLink(link: Any): Boolean {
        val accepted = controller.javaClass
            .getDeclaredMethod("beginVehicleNavigationLink", Any::class.java)
            .apply { isAccessible = true }.invoke(controller, link) as Boolean
        if (accepted) testLink = link
        return accepted
    }

    private fun pendingFrames() = ReflectionHelpers
        .getField<java.util.ArrayDeque<*>>(controller, "pendingNavigationFrames").size

    private fun turnDetail(index: Int) = Iap2Messages.buildRaw(0x5202) {
        u16(1, index)
        u8(3, 1)
    }

    private fun turnAndLaneSelection(index: Int) = Iap2Messages.buildRaw(0x5201) {
        u8(1, 1)
        u32(10, 140)
        u16(13, index)
        u16(16, index)
        bool(18, true)
    }

    private fun detail(index: Int) = Iap2Messages.buildRaw(0x5204) {
        u16(0, 42)
        u16(1, index)
        group(2) { u16(0, 0); u8(1, 2); i16(2, 0); i16(3, -90) }
    }

    private fun selection(index: Int) = Iap2Messages.buildRaw(0x5201) {
        u8(1, 1)
        u16(16, index)
        bool(18, true)
    }

    private fun identity() = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    private fun phone() = AirPlaySession(Socket(), config, identity(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(phones::add)
}
