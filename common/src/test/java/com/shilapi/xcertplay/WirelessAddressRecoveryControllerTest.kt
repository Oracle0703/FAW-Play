package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessAddressRecovery
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the production timeout handoff while Android's group query is in flight. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessAddressRecoveryControllerTest {
    @Test fun aCurrentQueryMayReserveButNeverInventsAnAddressWhenTheInterfaceIsUnavailable() {
        Fixture().use { fixture ->
            fixture.query { }
            assertEquals(1, fixture.reservations())
            assertEquals(WirelessAddressRecovery.Snapshot(), fixture.recovery.snapshot())
        }
    }

    @Test fun aNewWirelessGenerationRevokesTheTimeoutWhileTheGroupQueryIsPending() {
        Fixture().use { fixture ->
            fixture.query { fixture.generation.incrementAndGet() }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun replacingTheListenerWatchdogRevokesThePendingTimeout() {
        Fixture().use { fixture ->
            fixture.query {
                ReflectionHelpers.setField(fixture.controller, "firstTcpWatchdog", fixture.newWatchdog())
            }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun anAdoptedAirPlaySessionCannotHaveItsAddressFamilyChangedByTheOldTimeout() {
        Fixture().use { fixture ->
            fixture.query {
                ReflectionHelpers.setField(fixture.controller, "activeSession", mock(AirPlaySession::class.java))
            }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun replacingTheHotspotOwnerRevokesTheOldGroupsPendingResult() {
        Fixture().use { fixture ->
            fixture.query {
                ReflectionHelpers.setField(fixture.controller, "hotspot", mock(WifiP2pGroupManager::class.java))
            }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun vehicleStopRevokesTheTimeoutBeforeNetworkCleanupCompletes() {
        Fixture().use { fixture ->
            fixture.query { fixture.controller.pauseMediaForVehicleStop() }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun closingTheControllerRevokesThePendingTimeoutBeforeItsQueryReturns() {
        Fixture().use { fixture ->
            fixture.query { fixture.controller.close() }
            assertEquals(0, fixture.reservations())
        }
    }

    @Test fun aRetainedHotspotReportsItsRuntimeFailureToTheCurrentWirelessRun() {
        Fixture().use { fixture ->
            // The manager was acquired in generation 1, then retained for a retry in 2.
            fixture.generation.set(2)
            fixture.runtimeFailure(fixture.manager)
            assertTrue(fixture.failureReported())
            assertEquals(2, fixture.generation.get())
        }
    }

    @Test fun aFormerHotspotOwnerCannotFailTheCurrentWirelessRun() {
        Fixture().use { fixture ->
            fixture.generation.set(2)
            fixture.runtimeFailure(mock(WifiP2pGroupManager::class.java))
            assertFalse(fixture.failureReported())
            assertEquals(2, fixture.generation.get())
        }
    }

    private class Fixture : Closeable {
        val recovery = spy(WirelessAddressRecovery())
        val controller = CarPlayController(
            context = RuntimeEnvironment.getApplication(),
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            airPlayConfig = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
            reportStatus = {},
            addressRecovery = recovery,
        )
        val generation = ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration")
        val manager = mock(WifiP2pGroupManager::class.java)
        private val queried = CountDownLatch(1)
        private val resumeQuery = CountDownLatch(1)
        private val caller = Executors.newSingleThreadExecutor()
        private val watchdog: Any

        init {
            generation.set(1)
            val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            phase.set(controller, requireNotNull(phase.type.enumConstants).single { it.toString() == "WIRELESS" })
            watchdog = newWatchdog()
            ReflectionHelpers.setField(controller, "firstTcpWatchdog", watchdog)
            ReflectionHelpers.setField(controller, "wirelessAddressToken", recovery.token())
            ReflectionHelpers.setField(controller, "wirelessPublishedAddress", InetAddress.getByName("192.0.2.1"))
            ReflectionHelpers.setField(controller, "hotspot", manager)
            `when`(manager.reuseHealthyGroup()).thenAnswer {
                queried.countDown()
                check(resumeQuery.await(3, TimeUnit.SECONDS))
                // An absent interface prevents this test from depending on the host's network.
                WirelessHotspotInfo("test", "test", Iap2WirelessSecurity.WPA_WPA2,
                    0, null, null, null, InetAddress.getByName("192.0.2.1"),
                    "unknown", WirelessHotspotBackend.WIFI_P2P)
            }
        }

        fun newWatchdog(): Any = requireNotNull(controller.javaClass.declaredMethods
            .single { it.name == "newFirstTcpWatchdog" }
            .apply { isAccessible = true }
            .invoke(controller, 1, AirPlayListenerIdentity(1)))

        fun runtimeFailure(expected: WifiP2pGroupManager) {
            resumeQuery.countDown()
            controller.javaClass.declaredMethods
                .single { it.name == "onHotspotRuntimeFailure" }
                .apply { isAccessible = true }
                .invoke(controller, expected, WirelessStartupException(
                    WirelessStartupFailure.HOTSPOT_NOT_READY, "P2P service unavailable"))
            // Observe only after the Controller's actual queued callback has committed.
            ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(controller, "startupTimer")
                .submit { }.get(3, TimeUnit.SECONDS)
        }

        fun failureReported(): Boolean = ReflectionHelpers
            .getField<AtomicBoolean>(controller, "wirelessFailureReported").get()

        fun query(duringQuery: () -> Unit) {
            val method = controller.javaClass.declaredMethods
                .single { it.name == "prepareAlternateWirelessAddress" }
                .apply { isAccessible = true }
            val result = caller.submit { method.invoke(controller, 1, watchdog) }
            try {
                assertTrue("The query must actually be in flight", queried.await(3, TimeUnit.SECONDS))
                duringQuery()
            } finally {
                resumeQuery.countDown()
            }
            result.get(3, TimeUnit.SECONDS)
        }

        fun reservations(): Int = mockingDetails(recovery).invocations
            .count { it.method.name == "reserveAlternate" }

        override fun close() {
            resumeQuery.countDown()
            caller.shutdownNow()
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
        }
    }
}
