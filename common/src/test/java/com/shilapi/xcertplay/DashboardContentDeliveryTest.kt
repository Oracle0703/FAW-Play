package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import android.os.Build
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardContentDeliveryTest {
    private class Fixture(initialUrl: String = CarPlayClusterDisplay.Content.TURN_CARD.url) : AutoCloseable {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(1600, 600, initialUrl = initialUrl))
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        private val phones = mutableListOf<AirPlaySession>()
        fun phone(setup: Boolean = true): AirPlaySession = AirPlaySession(Socket(), config,
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>) = 1
            }).also {
                phones += it
                if (setup) {
                    setup(it)
                    readyAfterSetup(it)
                }
            }
        fun activate(phone: AirPlaySession) = set(controller, "activeSession", phone)
        fun record(phone: AirPlaySession) {
            val listener = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
                .get(controller) as AirPlaySessionListener
            listener.onSessionActive(phone)
        }
        fun awaitCommands() {
            val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(3, TimeUnit.SECONDS)
        }
        override fun close() {
            controller.close()
            controller.awaitClosed(2_000)
            phones.forEach { it.close() }
        }
    }

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        var writes = 0
        private val bytes = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private class Completion {
        private val done = CountDownLatch(1)
        private val value = AtomicReference<Boolean?>()
        fun accept(result: Boolean) { value.set(result); done.countDown() }
        fun await(): Boolean {
            assertTrue("Delivery must complete", done.await(3, TimeUnit.SECONDS))
            return value.get()!!
        }
        fun pending() = done.count == 1L
    }

    private fun eventReady(phone: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        set(phone, "eventSocket", event)
        set(phone, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
        return event
    }

    private fun blockQueue(controller: CarPlayController): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
        (field.get(controller) as ExecutorService).execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        return release
    }

    @Test fun aMissingPhoneOrClusterReportsFailureImmediately() {
        Fixture().use { f ->
            val withoutPhone = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, withoutPhone::accept)
            assertFalse(withoutPhone.await())
            f.activate(f.phone(setup = false))
            val withoutCluster = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, withoutCluster::accept)
            assertFalse(withoutCluster.await())
        }
    }

    @Test fun anEnqueuedSwitchReportsActualFailureAndKeepsTurnCardZoomIneligible() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertFalse(complete.await()) // Accepted by the executor, but there is no event channel.
            assertNull(phone.clusterUrl())
            assertNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun successIsReportedOnlyAfterBothCommandsWereWritten() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val event = eventReady(phone)
            val complete = Completion()
            val writesAtCompletion = AtomicReference(0)
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL) {
                writesAtCompletion.set(event.writes)
                complete.accept(it)
            }
            assertTrue(complete.await())
            assertEquals(2, writesAtCompletion.get())
            assertEquals(CarPlayClusterDisplay.MAP_URL, phone.clusterUrl())
            assertNotNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun aQueuedSwitchCannotReachAReplacedPhone() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(f.controller)
            try {
                val complete = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
                assertTrue(complete.pending())
                f.activate(f.phone())
                release.countDown()
                assertFalse(complete.await())
                assertEquals(0, event.writes)
            } finally { release.countDown() }
        }
    }

    @Test fun aQueuedSwitchCannotReachAReplacementClusterStream() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(f.controller)
            try {
                val complete = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
                setup(phone)
                release.countDown()
                assertFalse(complete.await())
                assertEquals(0, event.writes)
                assertNull(phone.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aPhoneReplacedDuringDeliveryCannotReportSuccessForTheNewPhone() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val replacement = f.phone()
            eventReady(phone, EventSocket { f.activate(replacement) })
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertFalse(complete.await())
            assertNull(replacement.clusterUrl())
        }
    }

    @Test fun anUnknownRouteIsRedrawnEvenWhenTheControllerAssumedTheNewStreamWasVisible() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone, EventSocket { setup(phone) })
            assertFalse(phone.setClusterUiShown(false))
            assertNull(phone.clusterUrl())
            visibility(f.controller, true)
            assertEquals(1, event.writes) // The replacement SETUP response is still pending.
            readyAfterSetup(phone)
            assertEquals(3, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, phone.clusterUrl())
        }
    }

    @Test fun aPausedSelectionWaitsForResumeWithoutGrantingMapZoom() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val event = eventReady(phone)
            visibility(f.controller, false)
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await()) // The pending choice is safely retained while paused.
            assertEquals(1, event.writes)
            assertNull(phone.clusterUrl())
            assertNull(f.controller.dashboardMapRoute())
            visibility(f.controller, true)
            assertEquals(CarPlayClusterDisplay.MAP_URL, phone.clusterUrl())
            assertNotNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun aReplacementPhoneInheritsTheLastDeliveredSelectionWithoutBlockingActivation() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await())
            val second = f.phone()
            val event = eventReady(second)
            val release = blockQueue(f.controller)
            try {
                f.record(second) // Activation must return while command execution is still blocked.
                assertEquals(0, event.writes)
                assertNull(second.clusterUrl())
                release.countDown()
                f.awaitCommands()
                assertEquals(2, event.writes)
                assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aPhoneActivatedBeforeClusterSetupReappliesTheSelectionOnlyAfterSetup() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await())
            val second = f.phone(setup = false)
            val event = eventReady(second)
            f.record(second)
            f.awaitCommands()
            assertEquals(0, event.writes)
            assertNull(second.clusterUrl())
            setup(second)
            assertNull(second.clusterUrl())
            second.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(second)
            assertEquals(2, event.writes)
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aFailedSwitchCannotReplaceTheLastAcceptedSelectionForTheNextPhone() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            set(first, "eventSocket", null)
            val failed = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, failed::accept)
            assertFalse(failed.await())
            val second = f.phone()
            eventReady(second)
            f.record(second)
            f.awaitCommands()
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aStaleQueuedSwitchCannotReplaceTheChoiceRestoredToANewPhone() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            val second = f.phone()
            eventReady(second)
            val release = blockQueue(f.controller)
            try {
                val stale = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, stale::accept)
                f.record(second)
                release.countDown()
                assertFalse(stale.await())
                f.awaitCommands()
                assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aReplacementDuringDeliveryCannotOverwriteTheLastAcceptedChoice() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            val second = f.phone()
            eventReady(second)
            eventReady(first, EventSocket { f.record(second) })
            val stale = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, stale::accept)
            assertFalse(stale.await())
            f.awaitCommands()
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aRepeatedRecordDoesNotResumeAPausedSelection() {
        Fixture().use { f ->
            val first = f.phone()
            val event = eventReady(first)
            f.record(first)
            f.awaitCommands()
            visibility(f.controller, false)
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            f.record(first)
            f.awaitCommands()
            assertEquals(3, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, first.clusterUrl())
            visibility(f.controller, true)
            assertEquals(CarPlayClusterDisplay.MAP_URL, first.clusterUrl())
        }
    }

    @Test @Config(sdk = [28])
    fun hs5CloseAndReopenTraversesTheActualOutputsControllerAndPhoneChain() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().putBoolean("hongqi_cluster_visible", true).commit()
        AutunnNavigationOutputs.setClusterMapShown(true)
        Fixture(CarPlayClusterDisplay.Content.INSTRUMENTS.url).use { f ->
            val phone = f.phone()
            val event = eventReady(phone)
            f.record(phone)
            f.awaitCommands()
            val firstWrites = event.writes
            assertEquals(2, firstWrites)
            HongqiNavigation.setMapShown(app, false)
            f.awaitCommands()
            assertEquals(firstWrites + 1, event.writes)
            HongqiNavigation.setMapShown(app, true)
            f.awaitCommands()
            assertEquals(firstWrites + 3, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, phone.clusterUrl())

            HongqiNavigation.clear()
            HongqiNavigation.onGuidance(ClusterTurnGuidance(2, 0, 150, "test"))
            f.awaitCommands()
            val routeWrites = event.writes
            assertEquals(firstWrites + 5, routeWrites)
            HongqiNavigation.onGuidance(ClusterTurnGuidance(2, 0, 145, "test"))
            f.awaitCommands()
            assertEquals(routeWrites, event.writes) // Ordinary route updates do not request repeatedly.

            // A mirror still needs the video even while the physical instrument is hidden.
            AutunnNavigationOutputs.setClusterMapShown(false)
            f.awaitCommands()
            val beforeMirrorHide = event.writes
            HongqiNavigation.setMapShown(app, false)
            f.awaitCommands()
            assertEquals(beforeMirrorHide, event.writes)
            assertTrue(AutunnNavigationOutputs.clusterStreamRequestedShown())
        }
        AutunnNavigationOutputs.setClusterMapShown(false)
        HongqiNavigation.clear()
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().putBoolean("hongqi_cluster_visible", true).commit()
    }

    @Test fun aVisibilityRequestQueuedForAnOldStreamCannotWriteToItsReplacement() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(f.controller)
            try {
                f.controller.javaClass.getDeclaredMethod("queueClusterUi", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(f.controller, false)
                setup(phone)
                release.countDown()
                f.awaitCommands()
                assertEquals(0, event.writes)
                assertNull(phone.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aRetiringControllerCannotWriteItsQueuedVisibilityAfterAnotherOwnerRegisters() {
        Fixture().use { first ->
            val phone = first.phone()
            first.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(first.controller)
            try {
                first.controller.javaClass.getDeclaredMethod("queueClusterUi", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(first.controller, true)
                Fixture().use { replacement ->
                    release.countDown()
                    first.awaitCommands()
                    assertEquals(0, event.writes)
                    replacement.awaitCommands()
                }
            } finally { release.countDown() }
        }
    }

    @Test fun queuedVisibilityAndNewRouteRequestsRemainPendingUntilTheReplacementSetupWasAnswered() {
        AutunnNavigationOutputs.setClusterMapShown(false)
        Fixture(CarPlayClusterDisplay.Content.INSTRUMENTS.url).use { f ->
            val phone = f.phone()
            val event = eventReady(phone)
            f.activate(phone)
            setup(phone) // Re-SETUP is admitted; its control response has not been flushed.
            f.controller.javaClass.getDeclaredMethod("queueClusterUi", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(f.controller, false)
            f.awaitCommands()
            assertEquals(0, event.writes)
            AutunnNavigationOutputs.requestClusterContent() // The route-start/reopen request supersedes hide.
            f.awaitCommands()
            assertEquals(0, event.writes)
            assertNull(phone.clusterUrl())
            readyAfterSetup(phone)
            assertEquals(2, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, phone.clusterUrl())
        }
    }

    @Test fun aVisibilityRequestWaitingForTheUiLockCannotAdoptAReplacementPhone() {
        Fixture().use { f ->
            val first = f.phone()
            val firstEvent = eventReady(first)
            val replacement = f.phone()
            val replacementEvent = eventReady(replacement)
            f.activate(first)
            val executor = f.controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(f.controller) as ExecutorService
            val worker = executor.submit<Thread> { Thread.currentThread() }.get(3, TimeUnit.SECONDS)
            val lock = f.controller.javaClass.getDeclaredField("clusterUiLock").apply { isAccessible = true }
                .get(f.controller)
            synchronized(lock) {
                f.controller.javaClass.getDeclaredMethod("queueClusterUi", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(f.controller, true)
                // Robolectric shadows sleep/nanoTime; JDK timed awaits give the real worker time
                // to reach the held monitor, with at most three seconds of actual waiting.
                val pollingDelay = CountDownLatch(1)
                var remainingPolls = 300
                var observedState = worker.state
                while (observedState != Thread.State.BLOCKED && remainingPolls-- > 0) {
                    pollingDelay.await(10, TimeUnit.MILLISECONDS)
                    observedState = worker.state
                }
                // Assert the observation, not a second state read: monitor contention may briefly
                // make the worker RUNNABLE again although this thread still holds the monitor.
                if (observedState != Thread.State.BLOCKED) {
                    fail("Queued visibility did not block at clusterUiLock: observed=$observedState " +
                        "current=${worker.state} alive=${worker.isAlive}\n" + worker.stackTrace.joinToString("\n"))
                }
                f.activate(replacement)
            }
            f.awaitCommands()
            assertEquals(0, firstEvent.writes)
            assertEquals("An old admission must not write to the replacement session", 0, replacementEvent.writes)
            assertNull(replacement.clusterUrl())
        }
    }

    companion object {
        private fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        private fun setup(phone: AirPlaySession) {
            phone.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
                .invoke(phone, listOf(mapOf("type" to 111L)))
        }
        private fun readyAfterSetup(phone: AirPlaySession) {
            phone.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(phone)
        }
        private fun visibility(controller: CarPlayController, shown: Boolean) {
            controller.javaClass.getDeclaredMethod("applyClusterUi", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(controller, shown)
        }
    }
}
