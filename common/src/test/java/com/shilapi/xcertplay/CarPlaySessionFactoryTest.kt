package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayUltraNegotiationMode
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlaySessionFactoryTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "retained-pairing")
    private val airPlay = AirPlayConfig(
        deviceName = "Autunn Play", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0", main = AirPlayDisplayConfig(1280, 480), cluster = AirPlayDisplayConfig(640, 240),
    )

    @Test fun createsDormantApplicationOwnedStackWithTheNegotiatedCanvasAndScreenCallbacks() {
        AirPlayPersistence.saveAudioFocusEnabled(app, false)
        AirPlayPersistence.saveMediaAudioChannel(app, 3)
        AirPlayPersistence.saveNavigationAudioChannel(app, 4)
        val listener = object : AirPlaySessionListener {}
        val statuses = mutableListOf<CarPlayStatus>()
        val report: (CarPlayStatus) -> Unit = { statuses.add(it) }
        val screens = mutableListOf<Pair<Int, Boolean>>()
        val config = configuration().copy(audioCaptureDirectory = File(app.cacheDir, "capture"))
        var arguments: List<*> = emptyList<Any>()
        mockConstruction(CarPlayController::class.java) { _, construction -> arguments = construction.arguments() }.use { construction ->
            val created = CarPlaySessionFactory.create(ContextWrapper(app), config, listener, report,
                { type, active -> screens.add(type to active) }, null)
            try {
                assertSame(construction.constructed().single(), created.controller)
                assertSame(app, arguments[0])
                assertSame(config.runtime, arguments[1])
                assertSame(airPlay, arguments[2])
                assertSame(identity, arguments[3])
                assertSame(listener, arguments[5])
                assertSame(report, arguments[7])
                assertSame(app, field(created.sink, "appContext"))
                assertNull(field<Any?>(created.sink, "defaultSurface"))
                assertEquals(1280, field<Int>(created.sink, "videoWidth"))
                assertEquals(480, field<Int>(created.sink, "videoHeight"))
                assertEquals(mapOf(111 to (640 to 240)), field<Map<Int, Pair<Int, Int>>>(created.sink, "videoSizes"))
                assertEquals(3, field<Int>(created.sink, "mediaChannel"))
                assertEquals(4, field<Int>(created.sink, "navigationChannel"))
                val media = arguments[6] as CarPlayMediaEngine
                assertSame(created.sink, field(media, "sink"))
                assertTrue(field(media, "microphoneEnabled"))
                assertEquals(config.audioCaptureDirectory, field<File?>(media, "audioCaptureDirectory"))
                created.sink.onScreenStreamActive(111, true)
                created.sink.onScreenStreamActive(111, false)
                assertEquals(listOf(111 to true, 111 to false), screens)
                assertTrue(statuses.isEmpty())
                verify(created.controller, never()).start()
            } finally { created.sink.close() }
        }
    }

    @Test fun pairingAndLockdownCallbacksContinueUsingApplicationPersistence() {
        val oldKey = ByteArray(32) { (it + 1).toByte() }
        AirPlayPersistence.savePairing(app, "existing-phone", oldKey)
        var arguments: List<*> = emptyList<Any>()
        mockConstruction(CarPlayController::class.java) { _, construction -> arguments = construction.arguments() }.use {
            val created = create(configuration())
            try {
                val pairings = arguments[4] as PairingStore
                assertArrayEquals(oldKey, pairings.get("existing-phone"))
                val nextKey = ByteArray(32) { (it + 4).toByte() }
                pairings.save("new-phone", nextKey)
                assertArrayEquals(nextKey, AirPlayPersistence.loadPairings(app) { _, _ -> }.get("new-phone"))
                @Suppress("UNCHECKED_CAST") val load = arguments[8] as () -> LockdownPairRecord?
                @Suppress("UNCHECKED_CAST") val save = arguments[9] as (LockdownPairRecord) -> Unit
                @Suppress("UNCHECKED_CAST") val clear = arguments[10] as () -> Unit
                val record = LockdownPairRecord.restore("host", "system", "02:00:00:00:00:01",
                    byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))
                save(record)
                assertEquals(record.hostId, load()!!.hostId)
                assertArrayEquals(record.rootPrivateKeyPem, load()!!.rootPrivateKeyPem)
                clear()
                assertNull(load())
            } finally { created.sink.close() }
        }
    }

    @Test fun locationProvidersMatchRetainedConfigurationWithoutStartingTheirSensors() {
        val arguments = mutableListOf<List<*>>()
        mockConstruction(CarPlayController::class.java) { _, construction -> arguments.add(construction.arguments()) }.use {
            for ((enabled, wheelSpeed) in listOf(false to false, true to false, true to true)) {
                val created = create(configuration(enabled, wheelSpeed))
                try {
                    val provider = arguments.last()[11]
                    when {
                        !enabled -> assertNull(provider)
                        wheelSpeed -> {
                            assertTrue(provider is VehicleSpeedLocationProvider)
                            val position = field<AndroidCarPlayLocationProvider>(provider!!, "position")
                            assertFalse(field(position, "started"))
                            val speed = field<Any>(provider, "speed")
                            assertNull(field<Any?>(speed, "session"))
                        }
                        else -> {
                            assertTrue(provider is AndroidCarPlayLocationProvider)
                            assertFalse(field(provider!!, "started"))
                        }
                    }
                    verify(created.controller, never()).start()
                } finally { created.sink.close() }
            }
        }
    }

    @Test fun mediaDiagnosticsRemainBoundToTheCapturedSessionLog() {
        val log = SessionLogFile(File(app.cacheDir, "factory-session.log"))
        mockConstruction(CarPlayController::class.java).use {
            val created = CarPlaySessionFactory.create(app, configuration(), object : AirPlaySessionListener {}, {}, { _, _ -> }, log)
            try {
                field<(String) -> Unit>(created.sink, "diagnosticListener")("Audio: factory retained-session diagnostic")
                assertTrue(AsyncDiagnosticLog.awaitIdle(3000))
                assertTrue(log.file.readText().contains("Audio: factory retained-session diagnostic"))
            } finally { created.sink.close(); log.close() }
        }
    }

    @Test fun failedControllerConstructionClosesTheAlreadyCreatedSink() {
        mockConstruction(AndroidMediaSink::class.java).use { sinks ->
            mockConstruction(CarPlayController::class.java) { _, _ -> throw IllegalStateException("construction failed") }.use {
                assertThrows(RuntimeException::class.java) { create(configuration()) }
                verify(sinks.constructed().single()).close()
            }
        }
    }

    @Test fun everyResumptionCreatesANewUltraRuntimeFromTheFactory() {
        val captured = mutableListOf<AirPlayConfig>()
        var builds = 0
        val config = configuration().copy(ultraProvisioning = {
            builds++
            com.shilapi.xcertplay.airplay.AirPlayUltraProvisioning()
        })
        mockConstruction(CarPlayController::class.java) { _, call ->
            captured += call.arguments()[2] as AirPlayConfig
        }.use {
            repeat(2) { create(config).sink.close() }
        }
        assertEquals(2, builds)
        assertNotNull(captured[0].ultra)
        assertNotSame(captured[0].ultra!!.runtime, captured[1].ultra!!.runtime)
        assertFalse(captured[0].ultra!!.readiness.ready)
    }

    @Test fun aRetainedRuntimeCannotBypassFreshProvisioningOnResume() {
        var captured: AirPlayConfig? = null
        val config = configuration().copy(airPlay = airPlay.copy(ultra =
            com.shilapi.xcertplay.airplay.AirPlayUltraConfig(cluster = checkNotNull(airPlay.cluster),
                runtime = com.shilapi.xcertplay.airplay.AirPlayUltraRuntime.empty())))
        mockConstruction(CarPlayController::class.java) { _, call ->
            captured = call.arguments()[2] as AirPlayConfig
        }.use { create(config).sink.close() }
        assertNull(captured!!.ultra)
    }

    @Test fun realHs5ProfileBuildsFreshExperimentalVehicleAndUiHandlersForEveryController() {
        val captured = mutableListOf<AirPlayConfig>()
        val hs5 = Hs5UltraProfile.factory(ContextWrapper(app))
        var builds = 0
        val config = configuration().copy(ultraProvisioning = { builds++; hs5() })
        mockConstruction(CarPlayController::class.java) { _, call ->
            captured += call.arguments()[2] as AirPlayConfig
        }.use {
            repeat(2) { create(config).sink.close() }
        }
        assertEquals(2, builds)
        assertEquals(2, captured.size)
        assertNotSame(captured[0].ultra!!.runtime, captured[1].ultra!!.runtime)
        captured.forEach { value ->
            val ultra = value.ultra!!
            assertEquals(AirPlayUltraNegotiationMode.EXPERIMENTAL, ultra.negotiationMode)
            assertTrue(ultra.negotiationAllowed)
            assertFalse(ultra.readiness.ready)
            assertEquals(setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL, AirPlayFeature.UI_SYNC), ultra.readyFeatures)
            assertTrue(ultra.implementation.complete)
            assertNotNull(ultra.vehicleStateProtocolInfo)
            assertNotNull(ultra.uiSyncInfo)
            assertFalse(ultra.readiness.targetPhoneVerified)
            val noProposal = AirPlayFeatureNegotiation.propose(value, emptyList<String>(), true)
            assertTrue(noProposal.enabledFeatureSet.isEmpty())
            val requested = AirPlayFeatureNegotiation.propose(value,
                listOf("vehicleStateProtocol", "uiSync"), true)
            assertEquals(setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL, AirPlayFeature.UI_SYNC),
                requested.enabledFeatureSet)
        }
    }

    private fun create(config: CarPlayResumeConfiguration) = CarPlaySessionFactory.create(
        app, config, object : AirPlaySessionListener {}, {}, { _, _ -> }, null,
    )

    private fun configuration(location: Boolean = false, wheelSpeed: Boolean = false): CarPlayResumeConfiguration {
        val runtime = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, locationReportingEnabled = location,
            identification = Iap2IdentificationConfig("Autunn Play", "test", "Autunn Play", "serial", "1", "1", 1,
                locationInformationEnabled = location, vehicleSpeedEnabled = wheelSpeed))
        return CarPlayResumeConfiguration(runtime, airPlay, identity, 1920, 720,
            CarPlaySessionDisplay(1280, 480, 0, true, true, 1920, 720),
            preferSoftwareHevcDecoder = true, advancedAudioChannelMapping = true,
            navigationStreamType = 14, microphoneEnabled = true)
    }

    private fun <T> field(target: Any, name: String): T = ReflectionHelpers.getField(target, name)
}
