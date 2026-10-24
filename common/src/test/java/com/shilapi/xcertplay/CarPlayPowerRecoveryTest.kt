package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.ClusterContentProgress
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WifiRadioStartup
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.IOException
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPowerRecoveryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var ignitionMonitors: MockedConstruction<HongqiIgnitionMonitor>
    private val ignitionSampleCallbacks = mutableListOf<(Boolean?) -> Unit>()
    private lateinit var phoneMonitors: MockedConstruction<SelectedPhoneMonitor>
    private val phoneSampleCallbacks = mutableListOf<(SelectedPhoneState) -> Unit>()
    private var observedPhoneState = SelectedPhoneState.CONNECTED
    private var ignitionClock = 0L
    private val sampleMethod = CarPlayBackgroundSession::class.java
        .getDeclaredMethod("onIgnitionSample", Boolean::class.javaObjectType).apply { isAccessible = true }
    private val display = CarPlaySessionDisplay(1280, 480, 0, true, true, 1920, 720)

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        // Binder polling has dedicated integration tests. This fixture supplies vehicle samples.
        ignitionMonitors = mockConstruction(HongqiIgnitionMonitor::class.java) { _, construction ->
            @Suppress("UNCHECKED_CAST")
            val callback = construction.arguments()[1] as (Boolean?) -> Unit
            ignitionSampleCallbacks.add(callback)
        }
        // Existing recovery cases assume the configured phone is available. Keep the real
        // session admission path while independently controlling system Bluetooth events.
        phoneMonitors = mockConstruction(SelectedPhoneMonitor::class.java) { monitor, construction ->
            @Suppress("UNCHECKED_CAST")
            val callback = construction.arguments()[1] as (SelectedPhoneState) -> Unit
            var reported: SelectedPhoneState? = null
            val publish: (SelectedPhoneState) -> Unit = { value ->
                reported = value
                callback(value)
            }
            phoneSampleCallbacks.add(publish)
            doAnswer {
                if (reported != observedPhoneState) publish(observedPhoneState)
                null
            }.`when`(monitor).start()
            doAnswer {
                if (reported != observedPhoneState) publish(observedPhoneState)
                null
            }.`when`(monitor).refresh()
        }
        previousCloser = field("closer")
        set("closer", closer)
        reset()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        DiPlayPreferences.saveAutoConnect(app, true)
        // Recovery cases model an already configured vehicle and paired phone. First-use
        // configuration has separate entry coverage and must not be mistaken for recovery.
        DiPlayPreferences.savePhone(app, "02:11:22:33:44:44", "Previously paired phone")
        CarPlayBackgroundSession.requestConnection(app)
        sample(true)
        CarPlayBackgroundSession.configureRecovery(app, configuration())
    }

    @After fun tearDown() {
        try {
            reset()
            set("closer", previousCloser)
            closer.shutdownNow()
            stateIoScope.close()
        } finally {
            phoneMonitors.close()
            ignitionMonitors.close()
        }
    }


    @Test fun wifiReadinessResumesInBackgroundAfterMinutesWithoutConsumingTheFreshConnectionDeadline() {
        withWaitingWifi { radio, controllers ->
            main.idleFor(Duration.ofMinutes(4))
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
            assertFalse(CarPlayBackgroundSession.retryStopped())
            assertEquals(0, CarPlayBackgroundSession.startupRetryBudget().retries)
            readyWifi(radio)
            val next = controllers.constructed().single()
            verify(next, times(1)).start()
            assertNull(field<BroadcastReceiver?>("wifiReadinessReceiver"))
            val deadline = CarPlayBackgroundSession.startupRetryBudget().deadlineMillis
            assertNotNull(deadline)
            assertEquals(deadline, SessionRecoveryRecord.load(app)!!.startupDeadlineMillis)
            repeat(10) { broadcastWifiReady() }
            main.idle()
            assertEquals(1, controllers.constructed().size)
            assertEquals(deadline, CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        }
    }

    @Test fun p2pReadinessAlsoResumesThePendingBackgroundPreparation() {
        withWaitingWifi { radio, controllers ->
            radio.`when`<Int> { WifiRadioStartup.requestEnable(app) }.thenReturn(WifiRadioStartup.READY)
            app.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                .putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED))
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun wifiReadinessIsRecheckedAndCannotBypassMissingPermissions() {
        withWaitingWifi { radio, controllers ->
            // An enabled-looking broadcast cannot make a refused radio usable.
            broadcastWifiReady()
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            readyWifi(radio)
            assertTrue(controllers.constructed().isEmpty())
            assertEquals("permissions", field<String?>("backgroundSetupRequired"))
            assertNull(field<BroadcastReceiver?>("wifiReadinessReceiver"))
            assertNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        }
    }

    @Test fun disabledWifiEventsDoNotResumeThePendingConnection() {
        withWaitingWifi { radio, controllers ->
            radio.`when`<Int> { WifiRadioStartup.requestEnable(app) }.thenReturn(WifiRadioStartup.READY)
            app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED))
            app.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                .putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED))
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            readyWifi(radio)
            assertEquals(1, controllers.constructed().size)
        }
    }

    @Test fun staleWifiReadinessCannotUndoManualStop() = assertWifiReadinessBlocked("manual-stop") {
        CarPlayBackgroundSession.stop()
    }
    @Test fun staleWifiReadinessCannotUndoConfirmedParking() = assertWifiReadinessBlocked("parked") {
        confirmOff()
    }
    @Test fun staleWifiReadinessCannotUndoTermination() = assertWifiReadinessBlocked("manual-stop") {
        CarPlayBackgroundSession.terminate()
    }
    @Test fun staleWifiReadinessCannotUndoDisabledAutomaticConnection() = assertWifiReadinessBlocked("idle") {
        set("explicitUse", false)
        set("explicitRequestPending", false)
        DiPlayPreferences.saveAutoConnect(app, false)
        CarPlayBackgroundSession.resumeService(app)
    }

    @Test fun wifiReadinessCannotReplenishAnExistingAttemptDeadline() {
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        val due = budget.begin(android.os.SystemClock.elapsedRealtime())
        withWaitingWifi { radio, controllers ->
            main.idleFor(Duration.ofSeconds(119))
            assertEquals(due, budget.deadlineMillis)
            readyWifi(radio)
            verify(controllers.constructed().single(), times(1)).start()
            assertEquals(due, budget.deadlineMillis)
            main.idleFor(Duration.ofSeconds(1))
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun aRadioAlreadyReadyAtReceiverRegistrationCannotLoseTheWakeup() {
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
        mockStatic(WifiRadioStartup::class.java).use { radio ->
            radio.`when`<Int> { WifiRadioStartup.requestEnable(app) }
                .thenReturn(WifiRadioStartup.USER_ACTION, WifiRadioStartup.READY)
            withNativeFactory { controllers, _ ->
                CarPlayBackgroundSession.resumeService(app)
                main.idle()
                verify(controllers.constructed().single(), times(1)).start()
                assertNull(field<BroadcastReceiver?>("wifiReadinessReceiver"))
            }
        }
    }

    private fun assertWifiReadinessBlocked(reason: String, block: () -> Unit) {
        withWaitingWifi { radio, controllers ->
            val stale = field<BroadcastReceiver>("wifiReadinessReceiver")
            block()
            main.idle()
            assertNull(field<BroadcastReceiver?>("wifiReadinessReceiver"))
            readyWifi(radio)
            stale.onReceive(app, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            assertEquals(reason, CarPlayBackgroundSession.startBlockReason())
        }
    }
    private fun withWaitingWifi(block: (MockedStatic<WifiRadioStartup>, MockedConstruction<CarPlayController>) -> Unit) {
        app.getSystemService(WifiManager::class.java).isWifiEnabled = false
        mockStatic(WifiRadioStartup::class.java).use { radio ->
            radio.`when`<Int> { WifiRadioStartup.requestEnable(app) }.thenReturn(WifiRadioStartup.USER_ACTION)
            withNativeFactory { controllers, _ ->
                CarPlayBackgroundSession.resumeService(app)
                main.idle()
                assertEquals("wifi", field<String?>("backgroundSetupRequired"))
                assertFalse(field<Boolean>("starting"))
                assertTrue(controllers.constructed().isEmpty())
                assertNotNull(field<BroadcastReceiver?>("wifiReadinessReceiver"))
                block(radio, controllers)
            }
        }
    }
    private fun readyWifi(radio: MockedStatic<WifiRadioStartup>) {
        radio.`when`<Int> { WifiRadioStartup.requestEnable(app) }.thenReturn(WifiRadioStartup.READY)
        broadcastWifiReady()
        main.idle()
    }
    private fun broadcastWifiReady() {
        app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
    }

    @Test fun newConnectionAndStopCompletionWaitForDurableControlIntent() {
        // Remove only the runtime viewport; this test verifies admission, not stack construction.
        set("configuration", null)
        val queuedIo = PausedExecutorService()
        set("stateIo", queuedIo)
        try {
            CarPlayBackgroundSession.requestConnection(app)
            assertEquals("saving-control", CarPlayBackgroundSession.startBlockReason())
            assertFalse(CarPlayBackgroundSession.canStart())
            queuedIo.runAll()
            main.idle()
            assertTrue(CarPlayBackgroundSession.canStart())
            var stopped = false
            CarPlayBackgroundSession.stop { stopped = true }
            assertFalse(stopped)
            queuedIo.runAll()
            main.idle()
            assertTrue(stopped)
        } finally { queuedIo.shutdownNow(); set("stateIo", DirectSessionExecutor()) }
    }

    @Test fun aPausedRetainedHostCannotReclaimTheForegroundPreparationGateFromLateCallbacks() {
        withPreparingHost { host, _ ->
            ReflectionHelpers.setField(host, "isActivityResumed", false)
            CarPlayBackgroundSession.setProjectionUiVisible(host, false)

            // Retained Activities can still receive viewport and vehicle callbacks after
            // onPause. Neither the setter nor maybeStartCarPlay may reclaim the old gate.
            CarPlayBackgroundSession.setForegroundStartupReady(host, false)
            startHost(host)
            notifyPreparationReady()

            assertNull(field<Any?>("projectionUiOwner"))
            assertNull(field<Any?>("startupOwner"))
            assertTrue(field<Boolean>("foregroundStartupReady"))
            assertTrue("The background owner can prepare while the old Host remains paused",
                field<Boolean>("starting"))
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun repeatedHostReadinessNotificationsKeepOneInFlightConstructionValid() {
        withPreparingHost { host, queuedIo ->
            withNativeFactory { controllers, _ ->
                startHost(host)
                main.idle()
                assertTrue(field("starting"))
                assertNull(CarPlayBackgroundSession.snapshot())
                val revision = field<Long>("configurationRevision")

                // Each Host submission reconstructs runtime/MAC/icon byte arrays. Actual UI
                // notifications must remain harmless while the queued native build is in flight.
                repeat(6) { notifyPreparationReady() }
                assertEquals(revision, field<Long>("configurationRevision"))
                assertTrue(controllers.constructed().isEmpty())
                queuedIo.runAll()
                main.idle()

                val resumed = controllers.constructed().single()
                assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
                verify(resumed, times(1)).start()
                verify(resumed, never()).close()
                repeat(3) { notifyPreparationReady(); queuedIo.runAll(); closer.runAll(); main.idle() }
                assertEquals(1, controllers.constructed().size)
                verify(resumed, never()).close()
            }
        }
    }

    @Test fun aRealHostViewportChangeDiscardsOnlyTheOldConstructionAndStartsTheLatest() {
        withPreparingHost { host, queuedIo ->
            val created = mutableListOf<Pair<CarPlayRuntimeConfig, AirPlayConfig>>()
            withNativeFactory(created) { controllers, _ ->
                startHost(host)
                main.idle()
                val revision = field<Long>("configurationRevision")
                setHostSize(host, 1600, 600)
                notifyPreparationReady()
                assertEquals(revision + 1, field<Long>("configurationRevision"))

                queuedIo.runAll()
                val obsolete = controllers.constructed().single()
                verify(obsolete, never()).start()
                closer.runAll()
                main.idle()
                // finishAttempt -> notifyUi -> Host.maybeStartCarPlay runs while the replacement
                // construction is queued. This is the exact feedback edge seen on the vehicle.
                assertTrue(field("starting"))
                assertEquals(revision + 1, field<Long>("configurationRevision"))
                queuedIo.runAll()
                main.idle()

                assertEquals(2, controllers.constructed().size)
                val resumed = controllers.constructed().last()
                verify(obsolete, times(1)).close()
                verify(obsolete, never()).start()
                verify(resumed, times(1)).start()
                verify(resumed, never()).close()
                assertEquals(1600, created.last().second.main.widthPixels)
                assertEquals(600, created.last().second.main.heightPixels)
                repeat(3) { notifyPreparationReady(); queuedIo.runAll(); closer.runAll(); main.idle() }
                assertEquals(2, controllers.constructed().size)
            }
        }
    }

    @Test fun realIdentityReplacementInvalidatesTheInFlightHostPreparation() {
        withPreparingHost { host, queuedIo ->
            withNativeFactory { controllers, _ ->
                startHost(host)
                main.idle()
                val previous = AirPlayPersistence.loadIdentity(app)
                val replacement = AirPlayIdentity.generate().copy(pairingId = previous.pairingId)
                fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                    .putString("identity_private", hex(replacement.privateKey))
                    .putString("identity_public", hex(replacement.publicKey))
                    .putString("pairing_id", replacement.pairingId).commit()
                ReflectionHelpers.setField(host, "airPlayIdentity", replacement)
                val revision = field<Long>("configurationRevision")
                notifyPreparationReady()
                assertEquals(revision + 1, field<Long>("configurationRevision"))

                queuedIo.runAll()
                val obsolete = controllers.constructed().single()
                closer.runAll()
                main.idle()
                queuedIo.runAll()
                main.idle()

                val resumed = controllers.constructed().last()
                assertEquals(2, controllers.constructed().size)
                verify(obsolete, never()).start()
                verify(obsolete, times(1)).close()
                verify(resumed, times(1)).start()
                val current = field<CarPlayResumeConfiguration>("configuration")
                assertEquals(previous.pairingId, current.identity.pairingId)
                assertArrayEquals(replacement.privateKey, current.identity.privateKey)
                assertArrayEquals(replacement.publicKey, current.identity.publicKey)
            }
        }
    }

    @Test fun stoppingAnInFlightHostConstructionCannotBeUndoneByReadinessNotifications() {
        withPreparingHost { host, queuedIo ->
            withNativeFactory { controllers, _ ->
                startHost(host)
                main.idle()
                var stopped = false
                CarPlayBackgroundSession.stop { stopped = true }
                repeat(4) { notifyPreparationReady() }
                assertFalse(stopped)
                queuedIo.runAll()
                closer.runAll()
                main.idleFor(Duration.ofMillis(300))

                val obsolete = controllers.constructed().single()
                verify(obsolete, never()).start()
                verify(obsolete, times(1)).close()
                assertTrue(stopped)
                assertNull(CarPlayBackgroundSession.snapshot())
                assertEquals("manual-stop", CarPlayBackgroundSession.startBlockReason())
                repeat(3) { notifyPreparationReady(); queuedIo.runAll(); closer.runAll(); main.idle() }
                assertEquals(1, controllers.constructed().size)
                assertNull(CarPlayBackgroundSession.snapshot())
            }
        }
    }

    @Test fun confirmedOffRetiresInFlightPreparationAndNextOnStartsOnlyAfterRelease() {
        withPreparingHost { host, queuedIo ->
            withNativeFactory { controllers, _ ->
                startHost(host)
                main.idle()
                confirmOff()
                main.idle()
                queuedIo.runAll()
                val obsolete = controllers.constructed().single()
                verify(obsolete, never()).start()
                assertEquals("closing", CarPlayBackgroundSession.serviceMode())

                sample(true)
                main.idle()
                queuedIo.runAll()
                main.idle()
                assertEquals(1, controllers.constructed().size)
                closer.runAll()
                main.idle()
                queuedIo.runAll()
                main.idle()

                assertEquals(2, controllers.constructed().size)
                verify(obsolete, times(1)).close()
                verify(obsolete, never()).start()
                val resumed = controllers.constructed().last()
                verify(resumed, times(1)).start()
                assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
            }
        }
    }

    @Test fun offCandidateKeepsPreparedResourcesAndCancellationResumesTheSameConstruction() {
        withPreparingHost { host, queuedIo ->
            withNativeFactory { controllers, _ ->
                startHost(host)
                main.idle()
                assertTrue(field("starting"))
                sample(false)
                main.idle()
                assertEquals("checking-vehicle-stop", CarPlayBackgroundSession.startBlockReason())
                queuedIo.runAll()
                main.idle()
                val prepared = controllers.constructed().single()
                verify(prepared, never()).start()
                verify(prepared, never()).close()
                assertNull(CarPlayBackgroundSession.snapshot())
                repeat(3) { notifyPreparationReady(); queuedIo.runAll(); closer.runAll(); main.idle() }
                assertEquals(1, controllers.constructed().size)
                sample(true)
                queuedIo.runAll()
                main.idle()
                assertSame(prepared, CarPlayBackgroundSession.snapshot()?.controller)
                verify(prepared, times(1)).start()
                verify(prepared, never()).close()
                assertEquals(1, controllers.constructed().size)
                assertFalse(CarPlayBackgroundSession.ignitionOff)
            }
        }
    }

    @Test fun sameUseStateSaveDuringPreparationRetainsTheBuiltResourcesUntilDurable() {
        withPreparingHost { host, queuedIo ->
            var saveQueued = false
            withNativeFactory(onConstructed = {
                if (!saveQueued) {
                    saveQueued = true
                    ReflectionHelpers.callInstanceMethod<Unit>(CarPlayBackgroundSession, "persistIntent")
                }
            }) { controllers, _ ->
                startHost(host)
                main.idle()
                repeat(10) {
                    if (controllers.constructed().isEmpty()) {
                        queuedIo.runNext()
                        main.idle()
                    }
                }
                val prepared = controllers.constructed().single()
                assertNull(CarPlayBackgroundSession.snapshot())
                verify(prepared, never()).start()
                verify(prepared, never()).close()
                queuedIo.runAll() // Durable-completion events admit the retained resources.
                main.idle()
                assertSame(prepared, CarPlayBackgroundSession.snapshot()?.controller)
                verify(prepared, times(1)).start()
                verify(prepared, never()).close()
                assertEquals(1, controllers.constructed().size)
            }
        }
    }

    @Test fun duplicateConfigurationRetainsFailureButTrustContentChangesInvalidateConstruction() {
        val initial = configuration()
        CarPlayBackgroundSession.configureRecovery(app, initial)
        val revision = field<Long>("configurationRevision")
        set("preparationFailure", "saved identity unavailable")
        // New objects with the same bytes must not reset failure or advance the generation.
        CarPlayBackgroundSession.configureRecovery(app, configuration())
        assertEquals(revision, field<Long>("configurationRevision"))
        assertEquals("saved identity unavailable", CarPlayBackgroundSession.preparationError())

        val privateKeyChanged = initial.copy(identity = initial.identity.copy(
            privateKey = initial.identity.privateKey.copyOf().also { it[0] = 1 }))
        CarPlayBackgroundSession.configureRecovery(app, privateKeyChanged)
        assertEquals(revision + 1, field<Long>("configurationRevision"))
        assertNull(CarPlayBackgroundSession.preparationError())
        val publicKeyChanged = privateKeyChanged.copy(identity = privateKeyChanged.identity.copy(
            publicKey = privateKeyChanged.identity.publicKey.copyOf().also { it[0] = 2 }))
        CarPlayBackgroundSession.configureRecovery(app, publicKeyChanged)
        assertEquals(revision + 2, field<Long>("configurationRevision"))
        set("preparationFailure", "retryable preparation failure")
        withNativeFactory { controllers, _ ->
            // Persisting a new request can synchronously reconcile through DirectSessionExecutor.
            // Keep its real retry/admission path, but never let the fixture bind a native VPN.
            CarPlayBackgroundSession.requestConnection(app)
            main.idle()
            assertNull(CarPlayBackgroundSession.preparationError())
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun configurationAdmissionComparesIconMacAndRuntimeCredentialContents() {
        fun candidate(iconByte: Byte = 1, macByte: Byte = 2, token: String = "test-token") = configuration().let {
            it.copy(airPlay = it.airPlay.copy(icons = listOf(
                com.shilapi.xcertplay.airplay.AirPlayIcon(1, 1, byteArrayOf(iconByte)))),
                runtime = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                    identification = it.runtime.identification, transport = CarPlayTransport.WIRELESS,
                    hostMac = ByteArray(6) { index -> if (index == 0) macByte else 0 },
                    remoteMfiToken = token))
        }
        CarPlayBackgroundSession.configureRecovery(app, candidate())
        var revision = field<Long>("configurationRevision")
        CarPlayBackgroundSession.configureRecovery(app, candidate())
        assertEquals(revision, field<Long>("configurationRevision"))
        for (changed in listOf(candidate(iconByte = 2), candidate(macByte = 4), candidate(token = "replacement"))) {
            CarPlayBackgroundSession.configureRecovery(app, changed)
            assertEquals(++revision, field<Long>("configurationRevision"))
            CarPlayBackgroundSession.configureRecovery(app, changed)
            assertEquals(revision, field<Long>("configurationRevision"))
        }
    }

    @Test fun resubmittingTheSameMutableInstanceStillDetectsChangedStartupContents() {
        val icons = mutableListOf(com.shilapi.xcertplay.airplay.AirPlayIcon(1, 1, byteArrayOf(1)))
        val phones = mutableListOf(com.shilapi.xcertplay.transport.UsbDeviceId(0x05ac, 1))
        val base = configuration()
        val candidate = base.copy(airPlay = base.airPlay.copy(icons = icons),
            runtime = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = base.runtime.identification, transport = CarPlayTransport.WIRELESS,
                hostMac = byteArrayOf(2, 0, 0, 0, 0, 2), iphoneDevices = phones))
        CarPlayBackgroundSession.configureRecovery(app, candidate)
        var revision = field<Long>("configurationRevision")
        val mutations = listOf<() -> Unit>(
            { candidate.identity.privateKey[0] = 3 },
            { candidate.identity.publicKey[0] = 4 },
            { candidate.runtime.hostMac[5] = 5 },
            { icons[0].data[0] = 6 },
            { icons.add(com.shilapi.xcertplay.airplay.AirPlayIcon(2, 2, byteArrayOf(7))) },
            { phones.add(com.shilapi.xcertplay.transport.UsbDeviceId(0x05ac, 2)) },
        )
        for (mutate in mutations) {
            mutate()
            CarPlayBackgroundSession.configureRecovery(app, candidate)
            assertEquals(++revision, field<Long>("configurationRevision"))
            CarPlayBackgroundSession.configureRecovery(app, candidate)
            assertEquals(revision, field<Long>("configurationRevision"))
        }
    }

    @Test fun offOnAfterActivityDestructionWaitsPastFourSecondsAndStartsOneNewNativeController() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val old = install(activity.get())
        CarPlayBackgroundSession.handoffToBackground(activity.get())
        CarPlayBackgroundSession.stopIgnitionWatch(activity.get())
        activity.destroy()
        assertFalse(CarPlayBackgroundSession.isOwner(activity.get()))

        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            val completion = old.nextCompletion()
            sample(true)
            main.idleFor(Duration.ofSeconds(5))
            assertTrue(CarPlayBackgroundSession.hasSession())
            assertEquals("closing", CarPlayBackgroundSession.serviceMode())
            assertTrue(controllers.constructed().isEmpty())
            verify(old.controller).close()
            verify(old.sink).close()

            completion(CarPlayController.CloseResult(true))
            main.idle()
            val resumed = controllers.constructed().single()
            verify(resumed, times(1)).start()
            val snapshot = CarPlayBackgroundSession.snapshot()!!
            assertSame(resumed, snapshot.controller)
            assertEquals(1920, snapshot.width)
            assertEquals(720, snapshot.height)
            assertEquals(display, snapshot.display)
            assertNull(shadowOf(app).nextStartedActivity)
            sample(true)
            main.idle()
            assertEquals(1, controllers.constructed().size)
            verify(resumed, times(1)).start()
        }
    }

    @Test fun destroyingAHostWithAQueuedTransportRecoveryStartsOneReplacementInTheBackground() {
        val old = install(Any())
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
        run {
            val lifecycle = Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
            val host = lifecycle.get()
            var destroyed = false
            try {
                assertFalse(host.isFinishing)
                val budget = CarPlayBackgroundSession.startupRetryBudget()
                assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller,
                    "transport interrupted before Activity destruction"))
                main.idle()
                assertEquals(1, budget.retries)

                withNativeFactory { controllers, _ ->
                    lifecycle.destroy()
                    destroyed = true
                    assertFalse(CarPlayBackgroundSession.isOwner(host))
                    assertNull(CarPlayBackgroundSession.snapshot())
                    assertSame(budget, CarPlayBackgroundSession.startupRetryBudget())
                    closer.runAll()
                    verify(old.controller, times(1)).close()
                    main.idleFor(Duration.ofSeconds(1))
                    assertTrue(controllers.constructed().isEmpty())

                    old.nextCompletion()(CarPlayController.CloseResult(true))
                    main.idle()
                    val resumed = controllers.constructed().single()
                    verify(resumed, times(1)).start()
                    assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
                    assertNull(shadowOf(app).nextStartedActivity)
                    main.idleFor(Duration.ofSeconds(5))
                    assertEquals(1, controllers.constructed().size)
                    verify(resumed, times(1)).start()
                }
            } finally {
                if (!destroyed) lifecycle.destroy()
                ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
                ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
                ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
            }
        }
    }

    @Test fun aStoppedHostRecoveryCannotBeRearmedByBackgroundTransportCallbacks() {
        val owner = Any()
        val old = install(owner)
        withNativeFactory { controllers, _ ->
            CarPlayBackgroundSession.handoffToBackground(owner, pendingRecovery = true,
                recoveryStopped = true, startupBudget = WirelessStartupRetryBudget())
            val listener = mockingDetails(old.controller).invocations.last { it.method.name == "attachUi" }
                .arguments[0] as AirPlaySessionListener
            listener.onTransportError("Another callback from the stopped connection")
            main.idleFor(Duration.ofSeconds(10))
            closer.runAll()

            assertSame(old.controller, CarPlayBackgroundSession.snapshot()?.controller)
            verify(old.controller, never()).close()
            assertTrue(controllers.constructed().isEmpty())
        }
    }

    @Test fun backgroundHandoffKeepsAnExhaustedHostBudgetInsteadOfGrantingMoreRetries() {
        val owner = Any()
        val old = install(owner)
        val budget = WirelessStartupRetryBudget()
        repeat(WirelessStartupPolicy.MAX_STARTUP_RETRIES) { assertNotNull(budget.nextDelayMillis()) }
        withNativeFactory { controllers, _ ->
            CarPlayBackgroundSession.handoffToBackground(owner, pendingRecovery = true, startupBudget = budget)
            assertSame(budget, field<WirelessStartupRetryBudget>("retryBudget"))
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idleFor(Duration.ofSeconds(30))

            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
            assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, budget.retries)
            verify(old.controller, times(1)).close()
        }
    }

    @Test fun aNeverCompletingCloseKeepsOneObserverAcrossWatchdogRetries() {
        val old = installBackground()
        confirmOff()
        closer.runAll()
        val original = old.nextCompletion()
        repeat(3) {
            main.idleFor(Duration.ofSeconds(13))
            closer.runAll()
            main.idle()
        }
        verify(old.controller, times(1)).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        assertTrue(old.completions.isEmpty())
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.canStart())
        assertEquals("cleanup-blocked", CarPlayBackgroundSession.serviceMode())
        original(CarPlayController.CloseResult(true))
        main.idle()
        // The actual resource owner completed, so quarantine ends immediately.
        verify(old.controller, times(1)).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test fun realCloseCompletionDuringWatchdogRetryDoesNotWaitForAnotherDeadline() {
        val old = installBackground()
        confirmOff()
        closer.runAll()
        val original = old.nextCompletion()
        main.idleFor(Duration.ofSeconds(13))
        closer.runAll()
        main.idle()
        assertTrue(CarPlayBackgroundSession.hasSession())
        verify(old.controller, times(1)).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        original(CarPlayController.CloseResult(true))
        main.idle()
        // No advancement of the simulated clock: the real same-owner completion must
        // release the running retry immediately, rather than wait its 12-second watchdog.
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.canStart()) // Parking still blocks a new use.
    }

    @Test fun failedResourceCompletionRetriesTheSameOwnerBeforeCreatingAnyReplacement() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            val first = old.nextCompletion()
            sample(true)
            first(CarPlayController.CloseResult(false, listOf("Bonjour close failed")))
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            assertEquals("closing", CarPlayBackgroundSession.serviceMode())
            main.idleFor(Duration.ofSeconds(1))
            closer.runAll()
            verify(old.controller, times(2)).close()
            verify(old.sink, times(1)).close()
            assertTrue(controllers.constructed().isEmpty())
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun checkedMediaCloseFailureKeepsTheBarrierUntilTheSameSinkAndControllerAreReleased() {
        val old = installBackground()
        var mediaCloseAttempts = 0
        doAnswer {
            if (++mediaCloseAttempts == 1) throw IOException("audio device still closing")
            null
        }.`when`(old.sink).close()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            sample(true)
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            verify(old.sink, times(1)).close()
            verify(old.controller, times(1)).close()
            assertEquals("closing", CarPlayBackgroundSession.serviceMode())
            assertTrue(controllers.constructed().isEmpty())

            main.idleFor(Duration.ofSeconds(1))
            closer.runAll()
            verify(old.sink, times(2)).close()
            verify(old.controller, times(2)).close()
            assertEquals("closing", CarPlayBackgroundSession.serviceMode())
            assertTrue(controllers.constructed().isEmpty())
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
            assertEquals("connecting", CarPlayBackgroundSession.serviceMode())
        }
    }

    @Test fun rapidOffOnOffOnCannotCreateOverlappingOrDuplicateStacks() {
        val old = installBackground()
        withNativeFactory { controllers, sinks ->
            confirmOff()
            closer.runAll()
            val completion = old.nextCompletion()
            sample(true)
            confirmOff()
            sample(true)
            assertTrue(controllers.constructed().isEmpty())
            completion(CarPlayController.CloseResult(true))
            main.idle()
            val first = controllers.constructed().single()
            verify(first, times(1)).start()

            confirmOff()
            closer.runAll()
            main.idle()
            verify(first, times(1)).close()
            verify(sinks.constructed().single(), times(1)).close()
            assertNull(CarPlayBackgroundSession.snapshot())
            assertEquals(1, controllers.constructed().size)
            sample(true)
            main.idle()
            assertEquals(2, controllers.constructed().size)
            controllers.constructed().forEach { verify(it, times(1)).start() }
        }
    }

    @Test fun manualStopDoesNotResumeWithinTheSameIgnitionCycle() {
        val old = installBackground()
        var stopped = false
        withNativeFactory { controllers, _ ->
            CarPlayBackgroundSession.stop { stopped = true }
            closer.runAll()
            val completion = old.nextCompletion()
            sample(true)
            sample(null)
            main.idleFor(Duration.ofSeconds(5))
            assertFalse(stopped)
            assertTrue(controllers.constructed().isEmpty())
            completion(CarPlayController.CloseResult(true))
            main.idle()
            assertTrue(stopped)
            assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
            sample(true)
            main.idle()
            assertTrue(controllers.constructed().isEmpty())

            // A genuine next ignition cycle honors the product's enabled auto-connect setting.
            confirmOff()
            sample(true)
            main.idle()
            verify(controllers.constructed().single()).start()
        }
    }

    @Test fun terminalStopPreventsAQueuedCloseOrLaterIgnitionFromRestartingAnything() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            val completion = old.nextCompletion()
            sample(true)
            CarPlayBackgroundSession.terminate()
            completion(CarPlayController.CloseResult(true))
            main.idle()
            feedOffSamples()
            assertNull(field<Boolean?>("ignitionState"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            sample(true)
            main.idleFor(Duration.ofSeconds(10))
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
            assertEquals("stopped", CarPlayBackgroundSession.serviceMode())
        }
    }

    @Test fun unknownIgnitionStartsImmediatelyWithoutInventingAnOnReadingOrDuplicatingTheStack() {
        withNativeFactory { controllers, sinks ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            assertEquals(1, sinks.constructed().size)
            verify(first, times(1)).start()
            assertNull(CarPlayBackgroundSession.startBlockReason())
            assertSame(first, CarPlayBackgroundSession.snapshot()?.controller)
            assertNull(field<Boolean?>("ignitionState"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertNull(shadowOf(app).nextStartedActivity)

            repeat(4) {
                sample(null)
                CarPlayBackgroundSession.configureRecovery(app, configuration())
                main.idleFor(Duration.ofSeconds(3))
            }
            assertEquals(1, controllers.constructed().size)
            assertEquals(1, sinks.constructed().size)
            verify(first, times(1)).start()
            assertNull(field<Boolean?>("ignitionState"))
        }
    }

    @Test fun aLaterValidOnReadingDoesNotReplaceTheStackStartedBeforeIgnition() {
        withNativeFactory { controllers, _ ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            sample(true)
            main.idle()
            assertSame(first, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(true, field<Boolean?>("ignitionState"))
            sample(null)
            main.idleFor(Duration.ofSeconds(5))
            assertEquals(1, controllers.constructed().size)
            verify(first, times(1)).start()
            verify(first, never()).close()
            assertEquals(true, field<Boolean?>("ignitionState"))
        }
    }

    @Test fun initialOffAndUnknownReadingsCannotCreateAParkingEdgeOrBlockPreIgnitionConnection() {
        withNativeFactory { controllers, _ ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            sample(false)
            sample(null)
            feedOffSamples()
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            main.idleFor(Duration.ofSeconds(10))

            assertEquals(false, field<Boolean?>("ignitionState"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
            assertNull(CarPlayBackgroundSession.startBlockReason())
            assertSame(first, CarPlayBackgroundSession.snapshot()?.controller)
            verify(first, never()).close()
            assertEquals(1, controllers.constructed().size)
        }
    }

    @Test fun manualStopBeforeIgnitionCannotBeUndoneByUnknownSamplesOrTheFirstOnReading() {
        withNativeFactory { controllers, _ ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            var stopped = false
            CarPlayBackgroundSession.stop { stopped = true }
            closer.runAll()
            main.idle()
            assertTrue(stopped)
            sample(null)
            main.idleFor(Duration.ofSeconds(10))
            sample(true)
            main.idle()

            assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
            assertEquals("manual-stop", CarPlayBackgroundSession.startBlockReason())
            assertEquals(1, controllers.constructed().size)
            verify(first, times(1)).close()
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun terminateBeforeIgnitionClosesTheStackAndIgnoresLaterSamples() {
        withNativeFactory { controllers, _ ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            var stopped = false
            CarPlayBackgroundSession.terminate { stopped = true }
            closer.runAll()
            main.idle()
            assertTrue(stopped)
            main.idleFor(Duration.ofSeconds(10))
            sample(null)
            sample(true)
            main.idle()

            assertNull(field<Boolean?>("ignitionState"))
            assertFalse(CarPlayBackgroundSession.canStart())
            assertEquals(1, controllers.constructed().size)
            verify(first, times(1)).close()
            assertNull(CarPlayBackgroundSession.snapshot())
            assertEquals("stopped", CarPlayBackgroundSession.serviceMode())
        }
    }

    @Test fun interruptedOffEvidenceNeverStopsThePreIgnitionStackAfterIgnitionTurnsOn() {
        withNativeFactory { controllers, _ ->
            beginUnknownRequest()
            val first = controllers.constructed().single()
            sample(true)
            sample(false)
            ignitionClock += 1_000
            sample(false)
            sample(null)
            ignitionClock += 1_000
            sample(false)
            ignitionClock += 1_000
            sample(false)
            sample(true)
            main.idle()
            verify(first, never()).close()
            assertEquals(1, controllers.constructed().size)

            confirmOff()
            closer.runAll()
            main.idle()
            sample(null)
            main.idleFor(Duration.ofSeconds(5))
            assertTrue(CarPlayBackgroundSession.ignitionOff)
            assertFalse(CarPlayBackgroundSession.canStart())
            assertEquals(1, controllers.constructed().size)
            sample(true)
            main.idle()
            assertEquals(2, controllers.constructed().size)
        }
    }

    @Test fun completedParkingCleanupStaysStoppedUntilNewUseEvidenceEvenIfConfigurationIsRefreshed() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            repeat(3) {
                feedOffSamples()
                sample(null)
                CarPlayBackgroundSession.configureRecovery(app, configuration())
                assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
                main.idleFor(Duration.ofSeconds(10))
            }
            verify(old.controller, times(1)).close()
            verify(old.sink, times(1)).close()
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
            assertTrue(CarPlayBackgroundSession.ignitionOff)
            assertEquals("parked", CarPlayBackgroundSession.startBlockReason())
        }
    }

    @Test fun aVerifiedReturnBeforeIgnitionWaitsForOldResourcesAndContinuousOffCannotStopTheNewUse() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            val previousUse = field<Long>("useGeneration")
            CarPlayBackgroundSession.vehicleUseResumed("test-verified-return")
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertEquals(previousUse + 1, field<Long>("useGeneration"))
            assertEquals("closing", CarPlayBackgroundSession.startBlockReason())
            feedOffSamples()
            assertTrue(controllers.constructed().isEmpty())

            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            val resumed = controllers.constructed().single()
            repeat(3) { feedOffSamples() }
            CarPlayBackgroundSession.vehicleUseResumed("duplicate-return")
            main.idleFor(Duration.ofSeconds(10))
            assertEquals(previousUse + 1, field<Long>("useGeneration"))
            assertEquals(false, field<Boolean?>("ignitionState"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
            verify(resumed, times(1)).start()
            verify(resumed, never()).close()
            assertEquals(1, controllers.constructed().size)
        }
    }

    @Test fun repeatedOffSamplesCannotRepeatParkingOrConsumeAnotherUseGeneration() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            val use = field<Long>("useGeneration")
            confirmOff()
            repeat(3) { feedOffSamples() }
            closer.runAll()
            assertEquals(use, field<Long>("useGeneration"))
            assertEquals(1, field<MutableList<*>>("retiring").size)
            verify(old.controller, times(1)).close()
            verify(old.sink, times(1)).close()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idleFor(Duration.ofSeconds(10))
            assertTrue(controllers.constructed().isEmpty())
            assertTrue(CarPlayBackgroundSession.ignitionOff)
        }
    }

    @Test fun anExplicitNewUseCannotInheritAnUnfinishedOffCandidateFromThePreviousUse() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            sample(false)
            ignitionClock += 1_000L
            sample(false)
            CarPlayBackgroundSession.stop()
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            val previousUse = field<Long>("useGeneration")

            // A door cannot undo the explicit stop; an explicit connection starts a new use.
            CarPlayBackgroundSession.requestConnection(app)
            main.idle()
            val resumed = controllers.constructed().single()
            repeat(3) { feedOffSamples() }
            main.idle()
            assertEquals(previousUse + 1, field<Long>("useGeneration"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
            verify(resumed, never()).close()

            // Once this new use observes ON, its own genuine OFF still ends the use.
            sample(true)
            confirmOff()
            closer.runAll()
            main.idle()
            verify(resumed, times(1)).close()
            assertTrue(CarPlayBackgroundSession.ignitionOff)
        }
    }

    @Test fun aColdAutomaticRequestStartsTheParkedVehicleWatchAndResumesOnlyAfterItsOnCallback() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            val saved = SessionRecoveryRecord.load(app)!!
            assertTrue(saved.parked)
            AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
            AirPlayPersistence.saveWirelessEnabled(app, true)
            forgetProcessState()
            val watchesBeforeEntry = ignitionMonitors.constructed().size

            // This is the homepage's real request boundary after process replacement in the
            // same Android boot. Do not pre-start a service or configure/watch ignition here.
            assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
            main.idle()
            assertEquals("The parked automatic entry must start its own vehicle observation",
                watchesBeforeEntry + 1, ignitionMonitors.constructed().size)
            val watch = ignitionMonitors.constructed().last()
            verify(watch, times(1)).start()
            assertEquals(DiPlaySessionService::class.java.name,
                shadowOf(app).nextStartedService?.component?.className)
            assertEquals("parked", CarPlayBackgroundSession.startBlockReason())
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            assertTrue(controllers.constructed().isEmpty())

            // Deliver through the callback registered with this newly created monitor,
            // rather than manufacturing an onIgnitionSample call behind the entry point.
            val observe = ignitionSampleCallbacks.last()
            observe(null)
            observe(false)
            main.idle()
            assertTrue(CarPlayBackgroundSession.ignitionOff)
            assertTrue(controllers.constructed().isEmpty())
            observe(true)
            main.idle()
            val resumed = controllers.constructed().single()
            verify(resumed, times(1)).start()
            assertSame(resumed, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(saved.useGeneration + 1, field<Long>("useGeneration"))
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            observe(true)
            assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
            main.idle()
            assertEquals(1, controllers.constructed().size)
            assertEquals(watchesBeforeEntry + 1, ignitionMonitors.constructed().size)
        }
    }

    @Test fun processRestartInTheSameBootRestoresParkingInsteadOfAutomaticallyConnecting() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            val saved = SessionRecoveryRecord.load(app)!!
            assertTrue(saved.parked)
            forgetProcessState()

            assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            feedOffSamples()
            sample(null)
            main.idleFor(Duration.ofSeconds(10))
            assertTrue(CarPlayBackgroundSession.ignitionOff)
            assertEquals(saved.useGeneration, field<Long>("useGeneration"))
            assertEquals("parked", CarPlayBackgroundSession.startBlockReason())
            assertTrue(controllers.constructed().isEmpty())
        }
    }

    @Test fun aParkedHomeEntryStartsVehicleObservationEvenWhenParkingWasAlreadyRestored() {
        val saved = SessionRecoveryRecord(4, desired = false, manualDisconnect = false,
            parked = true, terminated = false, retries = 0, retryStopped = false)
        assertTrue(SessionRecoveryRecord.save(app, saved))
        forgetProcessState()
        // A prior service attachment may have restored control intent without resuming it.
        val service = Robolectric.buildService(DiPlaySessionService::class.java).create()
        try {
            CarPlayBackgroundSession.attachService(service.get())
            assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
            val watchesBeforeEntry = ignitionMonitors.constructed().size
            val activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
            activity.intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            withNativeFactory { controllers, _ ->
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "autoConnectOnOpen")
                main.idle()
                assertEquals(watchesBeforeEntry + 1, ignitionMonitors.constructed().size)
                verify(ignitionMonitors.constructed().last(), times(1)).start()
                assertEquals("parked", CarPlayBackgroundSession.startBlockReason())
                assertFalse(CarPlayBackgroundSession.automaticEntryPending())
                assertTrue(controllers.constructed().isEmpty())
            }
        } finally { service.destroy() }
    }

    @Test fun aColdAutomaticRequestCannotBypassManualStopTerminationOrDisabledAutoConnect() {
        val parked = SessionRecoveryRecord(8, desired = false, manualDisconnect = false,
            parked = true, terminated = false, retries = 0, retryStopped = false)
        val cases = listOf(
            parked.copy(manualDisconnect = true) to true,
            parked.copy(terminated = true) to true,
            parked to false,
        )
        withNativeFactory { controllers, _ ->
            for ((saved, autoConnect) in cases) {
                assertTrue(SessionRecoveryRecord.save(app, saved))
                DiPlayPreferences.saveAutoConnect(app, autoConnect)
                forgetProcessState()
                while (shadowOf(app).nextStartedService != null) { /* Drain earlier lifecycle work. */ }
                val watchesBeforeEntry = ignitionMonitors.constructed().size
                assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
                main.idle()
                assertEquals(watchesBeforeEntry, ignitionMonitors.constructed().size)
                assertNull(shadowOf(app).nextStartedService)
                assertTrue(CarPlayBackgroundSession.ignitionOff)
                assertFalse(CarPlayBackgroundSession.automaticEntryPending())
                assertTrue(controllers.constructed().isEmpty())
                assertEquals(saved.useGeneration, field<Long>("useGeneration"))
                assertEquals(saved.manualDisconnect, field<Boolean>("manualDisconnect"))
                assertEquals(saved.terminated, field<Boolean>("terminated"))
            }
        }
    }

    @Test fun launcherReopensATaskTerminatedUseAfterProcessRestartWithANewAutomaticBudget() {
        val ended = SessionRecoveryRecord(8, desired = false, manualDisconnect = true,
            parked = false, terminated = true, retries = 4, retryStopped = true,
            startupDeadlineMillis = 1L, automaticBootstrapAttempted = true)
        assertTrue(SessionRecoveryRecord.save(app, ended))
        forgetProcessState()
        assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))

        assertTrue(CarPlayBackgroundSession.reopenFromLauncher(app))
        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))

        assertTrue(CarPlayBackgroundSession.automaticConnectAllowed())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertFalse(field<Boolean>("explicitUse"))
        assertEquals(9L, field<Long>("useGeneration"))
        assertEquals(0, CarPlayBackgroundSession.startupRetryBudget().retries)
        assertNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        assertFalse(field<Boolean>("automaticBootstrapAttempted"))
        assertFalse(CarPlayBackgroundSession.reopenFromLauncher(app))
        assertEquals(9L, field<Long>("useGeneration"))
    }

    @Test fun launcherReopenPreservesManualDisconnectParkingAndDisabledAutomaticConnection() {
        val ended = SessionRecoveryRecord(8, desired = false, manualDisconnect = true,
            parked = false, terminated = true, retries = 2, retryStopped = false)
        for ((saved, automatic) in listOf(ended.copy(terminated = false) to true,
                ended.copy(parked = true) to true, ended to false)) {
            assertTrue(SessionRecoveryRecord.save(app, saved))
            DiPlayPreferences.saveAutoConnect(app, automatic)
            forgetProcessState()
            assertFalse(CarPlayBackgroundSession.reopenFromLauncher(app))
            assertEquals(saved.useGeneration, field<Long>("useGeneration"))
            assertEquals(saved.manualDisconnect, field<Boolean>("manualDisconnect"))
            assertEquals(saved.terminated, field<Boolean>("terminated"))
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        }
    }

    @Test fun processRestartInTheSameBootRestoresRetryBudgetAndManualDisconnect() {
        val old = installBackground()
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        repeat(2) { assertNotNull(budget.nextDelayMillis()) }
        assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller, "persist retry budget"))
        val saved = SessionRecoveryRecord.load(app)!!
        assertEquals(3, saved.retries)
        forgetProcessState()

        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
        assertEquals(saved.useGeneration, field<Long>("useGeneration"))
        assertEquals(3, CarPlayBackgroundSession.startupRetryBudget().retries)
        assertNull(CarPlayBackgroundSession.snapshot())
        main.idle()
        CarPlayBackgroundSession.stop()
        main.idle()
        val stopped = SessionRecoveryRecord.load(app)!!
        assertTrue(stopped.manualDisconnect)
        forgetProcessState()

        assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
        assertFalse(CarPlayBackgroundSession.automaticConnectAllowed())
        assertEquals("manual-stop", CarPlayBackgroundSession.startBlockReason())
        assertEquals(3, CarPlayBackgroundSession.startupRetryBudget().retries)
    }

    @Test fun aFreshFirstOnReadingAfterProcessRestartCanResumeASavedParkedUse() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            confirmOff()
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()
            val saved = SessionRecoveryRecord.load(app)!!
            forgetProcessState()
            assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            assertNull(field<Boolean?>("ignitionState"))

            sample(true)
            main.idle()
            assertFalse(CarPlayBackgroundSession.ignitionOff)
            assertEquals(saved.useGeneration + 1, field<Long>("useGeneration"))
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun ignitionOffDuringProcessDeathCannotRestoreAStackBeforeFreshVehicleConfirmation() {
        installBackground()
        withNativeFactory { controllers, _ ->
            val saved = SessionRecoveryRecord.load(app)!!
            assertTrue(saved.desired)
            assertTrue(saved.useSawIgnitionOn)
            forgetProcessState()
            assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            main.idle()
            assertEquals("checking-vehicle", CarPlayBackgroundSession.startBlockReason())
            assertTrue(controllers.constructed().isEmpty())

            repeat(3) {
                sample(null)
                main.idleFor(Duration.ofSeconds(1))
                assertTrue(controllers.constructed().isEmpty())
            }
            feedOffSamples()
            main.idle()
            assertTrue(CarPlayBackgroundSession.ignitionOff)
            assertEquals("parked", CarPlayBackgroundSession.startBlockReason())
            assertEquals(saved.useGeneration, field<Long>("useGeneration"))
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun restoringAnOnUseWaitsForAFreshOnSampleWithoutUsingAnOldOnAsCurrentState() {
        installBackground()
        withNativeFactory { controllers, _ ->
            forgetProcessState()
            assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            main.idle()
            assertNull(field<Boolean?>("ignitionState"))
            assertTrue(controllers.constructed().isEmpty())

            sample(true)
            main.idle()
            assertNull(CarPlayBackgroundSession.startBlockReason())
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun disablingAutoConnectPreventsSavedDesiredIntentFromStartingInTheBackground() {
        // This scenario is an automatic use, unlike the explicit request in the shared setup.
        reset()
        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
        sample(true)
        CarPlayBackgroundSession.configureRecovery(app, configuration())
        installBackground()
        withNativeFactory { controllers, _ ->
            assertTrue(SessionRecoveryRecord.load(app)!!.desired)
            assertFalse(SessionRecoveryRecord.load(app)!!.explicitUse)
            DiPlayPreferences.saveAutoConnect(app, false)
            forgetProcessState()
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            CarPlayBackgroundSession.resumeService(app)
            assertFalse(CarPlayBackgroundSession.requestAutomaticConnection(app))
            sample(true)
            sample(null)
            main.idleFor(Duration.ofSeconds(10))
            assertTrue(controllers.constructed().isEmpty())
            assertFalse(field<Boolean>("desired"))
            assertNull(CarPlayBackgroundSession.snapshot())

            CarPlayBackgroundSession.requestConnection(app)
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun anExplicitUseSurvivesProcessRestartWithAutoConnectDisabledAfterFreshIgnition() {
        installBackground()
        withNativeFactory { controllers, _ ->
            val saved = SessionRecoveryRecord.load(app)!!
            assertTrue(saved.desired)
            assertTrue(saved.explicitUse)
            DiPlayPreferences.saveAutoConnect(app, false)
            forgetProcessState()
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            CarPlayBackgroundSession.resumeService(app)
            main.idle()
            assertEquals("checking-vehicle", CarPlayBackgroundSession.startBlockReason())
            assertTrue(controllers.constructed().isEmpty())

            sample(true)
            main.idle()
            assertEquals(saved.useGeneration, field<Long>("useGeneration"))
            assertTrue(field<Boolean>("desired"))
            verify(controllers.constructed().single(), times(1)).start()
        }
    }

    @Test fun disablingAutoConnectInTheSameProcessCancelsAnIdleAutomaticRequest() {
        reset()
        val viewportOwner = Any()
        CarPlayBackgroundSession.setForegroundStartupReady(viewportOwner, false)
        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
        CarPlayBackgroundSession.configureRecovery(app, configuration())
        withNativeFactory { controllers, _ ->
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            assertTrue(field<Boolean>("desired"))

            DiPlayPreferences.saveAutoConnect(app, false)
            CarPlayBackgroundSession.resumeService(app)
            CarPlayBackgroundSession.releaseForegroundStartup(viewportOwner)
            main.idleFor(Duration.ofSeconds(10))

            assertFalse(field<Boolean>("desired"))
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun disablingAutoConnectDoesNotCancelRecoveryOfAnExplicitConnectionInTheSameProcess() {
        val old = installBackground()
        withNativeFactory { controllers, _ ->
            DiPlayPreferences.saveAutoConnect(app, false)
            assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller, "manual-use-interrupted"))
            CarPlayBackgroundSession.resumeService(app)
            assertTrue(field<Boolean>("desired"))
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idleFor(Duration.ofSeconds(10))

            verify(controllers.constructed().single(), times(1)).start()
            assertTrue(field<Boolean>("desired"))
            assertNotNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun aNewUseBuildsItsControllerFromCurrentPhoneAndSettingsAfterOldResourcesAreReleased() {
        val old = installBackground()
        val created = mutableListOf<Pair<CarPlayRuntimeConfig, AirPlayConfig>>()
        withNativeFactory(createdConfigurations = created) { controllers, _ ->
            confirmOff()
            closer.runAll()
            DiPlayPreferences.savePhone(app, "02:11:22:33:44:55", "Replacement phone")
            AirPlayPersistence.saveManualHotspotSsid(app, "Current-Hotspot")
            AirPlayPersistence.saveLocationReportingEnabled(app, true)
            sample(true)
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            assertTrue(created.isEmpty())

            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idle()

            verify(controllers.constructed().single(), times(1)).start()
            val (runtime, airPlay) = created.single()
            assertEquals("02:11:22:33:44:55", runtime.wirelessBluetoothDeviceAddress)
            assertEquals("Current-Hotspot", runtime.manualHotspotSsid)
            assertTrue(runtime.locationReportingEnabled)
            assertEquals(configuration().airPlay.main, airPlay.main)
            assertEquals(display, CarPlayBackgroundSession.snapshot()?.display)
        }
    }

    @Test fun automaticConnectionWaitsWithoutSpendingRetriesUntilTheSelectedPhoneArrives() {
        withNativeFactory { controllers, _ ->
            for (missing in listOf(SelectedPhoneState.DISCONNECTED, SelectedPhoneState.UNAVAILABLE)) {
                val previousControllers = controllers.constructed().size
                beginAutomaticRequest(missing)
                main.idleFor(Duration.ofSeconds(30))
                assertEquals(previousControllers, controllers.constructed().size)
                assertEquals(0, CarPlayBackgroundSession.startupRetryBudget().retries)
                assertFalse(field<Boolean>("retryPending"))
                assertNull(CarPlayBackgroundSession.snapshot())
                assertNull(shadowOf(app).nextStartedActivity)

                // Returning to the car desktop remains a user choice when the phone arrives.
                CarPlayBackgroundSession.cancelAutomaticEntry("car-desktop")
                phoneSample(SelectedPhoneState.CONNECTED)
                main.idle()
                assertEquals(previousControllers + 1, controllers.constructed().size)
                verify(controllers.constructed().last(), times(1)).start()
                assertFalse(CarPlayBackgroundSession.automaticEntryPending())
                assertNull(shadowOf(app).nextStartedActivity)
            }
        }
    }

    @Test fun selectedPairedIphoneGetsOneBootstrapWithoutFactoryProfilesAndCanArriveLater() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.DISCONNECTED)
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val device = mock(android.bluetooth.BluetoothDevice::class.java)
            `when`(device.address).thenReturn("02:00:00:00:00:49")
            `when`(device.uuids).thenReturn(arrayOf(android.os.ParcelUuid.fromString(
                com.shilapi.xcertplay.transport.IphoneBluetoothDevices.IAP2_SERVICE_UUID)))
            shadowOf(adapter).setEnabled(true)
            shadowOf(adapter).setBondedDevices(setOf(device))
            DiPlayPreferences.savePhone(app, device.address, "Selected iPhone")
            try {
                val fresh = requireNotNull(CarPlayConfigurationStore.load(app, MfiTarget.LOCAL))
                CarPlayBackgroundSession.configureRecovery(app, fresh)
                main.idle()
                val first = controllers.constructed().single()
                verify(first).start()
                assertNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
                assertTrue(CarPlayBackgroundSession.requestRecovery(first, "phone-not-in-range"))
                closer.runAll()
                main.idleFor(Duration.ofMinutes(3))
                assertEquals(1, controllers.constructed().size)
                assertFalse(CarPlayBackgroundSession.retryStopped())
                phoneSample(SelectedPhoneState.CONNECTED)
                main.idle()
                assertEquals(2, controllers.constructed().size)
                verify(controllers.constructed().last()).start()
            } finally {
                shadowOf(adapter).setBondedDevices(emptySet())
                DiPlayPreferences.savePhone(app, "", "")
            }
        }
    }

    @Test fun unknownBluetoothEvidenceKeepsTheCompatibleConnectionAttemptAvailable() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.UNKNOWN)
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
            assertEquals(0, CarPlayBackgroundSession.startupRetryBudget().retries)
        }
    }

    @Test fun anExplicitConnectionCanStartWhileAutomaticConnectionWaitsForBluetooth() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.UNAVAILABLE)
            main.idle()
            assertTrue(controllers.constructed().isEmpty())

            CarPlayBackgroundSession.requestConnection(app)
            main.idle()
            verify(controllers.constructed().single(), times(1)).start()
            assertTrue(field<Boolean>("explicitUse"))
        }
    }

    @Test fun aPhoneArrivalCannotReplenishAnUnfinishedExhaustedBudgetOrRearmTheUi() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.CONNECTED)
            val old = installBackground()
            val budget = CarPlayBackgroundSession.startupRetryBudget()
            repeat(WirelessStartupPolicy.MAX_STARTUP_RETRIES) { assertNotNull(budget.nextDelayMillis()) }
            assertFalse(CarPlayBackgroundSession.requestRecovery(old.controller, "exhausted phone attempt"))
            phoneSample(SelectedPhoneState.DISCONNECTED)
            CarPlayBackgroundSession.cancelAutomaticEntry("car-desktop")
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idleFor(Duration.ofSeconds(30))
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, budget.retries)
            assertTrue(controllers.constructed().isEmpty())

            phoneSample(SelectedPhoneState.CONNECTED)
            main.idle()
            assertTrue(controllers.constructed().isEmpty())
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, budget.retries)
            assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, SessionRecoveryRecord.load(app)!!.retries)
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        }
    }

    @Test fun phoneArrivalRetainsAQueuedControllerRetryAndRepeatedConnectedEventsKeepItsBudget() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.CONNECTED)
            val old = installBackground()
            `when`(old.controller.tryReconnectWireless(any())).thenReturn(true)
            assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller, "link-lost", reuseController = true))
            val generation = field<Int>("recoveryGeneration")
            phoneSample(SelectedPhoneState.DISCONNECTED)
            phoneSample(SelectedPhoneState.CONNECTED)
            assertTrue(field<Boolean>("retryPending"))
            assertSame(old.controller, field<CarPlayController?>("pendingReuse"))
            assertEquals(generation, field<Int>("recoveryGeneration"))
            main.idleFor(Duration.ofSeconds(1))
            verify(old.controller, times(1)).tryReconnectWireless(any())
            verify(old.controller, never()).close()
            assertTrue(controllers.constructed().isEmpty())

            val budget = CarPlayBackgroundSession.startupRetryBudget()
            assertNotNull(budget.nextDelayMillis())
            phoneSample(SelectedPhoneState.CONNECTED)
            phoneSample(SelectedPhoneState.CONNECTED)
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            main.idle()
            assertEquals(2, budget.retries)
            assertSame(old.controller, CarPlayBackgroundSession.snapshot()?.controller)
        }
    }

    @Test fun lossOfTheBluetoothLinkDoesNotCloseAHealthyWirelessCarPlaySession() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.CONNECTED)
            val old = installBackground()
            val session = mock(AirPlaySession::class.java)
            `when`(old.controller.currentActiveAirPlaySession()).thenReturn(session)
            `when`(old.controller.activeAirPlaySessionToken()).thenReturn(Any())
            backgroundListener(old).onSessionActive(session)
            main.idle()
            val budget = CarPlayBackgroundSession.startupRetryBudget()
            repeat(2) { assertNotNull(budget.nextDelayMillis()) }

            phoneSample(SelectedPhoneState.DISCONNECTED)
            phoneSample(SelectedPhoneState.UNAVAILABLE)
            phoneSample(SelectedPhoneState.CONNECTED)
            closer.runAll()
            main.idle()

            assertTrue(CarPlayBackgroundSession.active)
            assertSame(old.controller, CarPlayBackgroundSession.snapshot()?.controller)
            verify(old.controller, never()).close()
            verify(old.sink, never()).close()
            verify(old.controller, never()).tryReconnectWireless(any())
            assertTrue(controllers.constructed().isEmpty())
            assertEquals(2, budget.retries)
        }
    }

    @Test fun anEstablishedWirelessSessionKeepsBoundedNetworkRecoveryAfterBluetoothDisconnects() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.CONNECTED)
            val old = installBackground()
            val session = mock(AirPlaySession::class.java)
            `when`(old.controller.currentActiveAirPlaySession()).thenReturn(session)
            backgroundListener(old).onSessionActive(session)
            main.idle()
            assertTrue(CarPlayBackgroundSession.active)
            phoneSample(SelectedPhoneState.DISCONNECTED)
            `when`(old.controller.currentActiveAirPlaySession()).thenReturn(null)
            `when`(old.controller.tryReconnectWireless(any())).thenReturn(true)

            val listener = backgroundListener(old)
            listener.onSessionDisconnected(session)
            listener.onSessionEnded(session)
            main.idle()
            assertFalse(CarPlayBackgroundSession.active)
            assertTrue(field<Boolean>("retryPending"))
            assertSame(old.controller, field<CarPlayController?>("pendingReuse"))
            val budget = CarPlayBackgroundSession.startupRetryBudget()
            assertEquals(1, budget.retries)
            verify(old.controller, never()).close()

            // The retained AP/session proof can recover without Bluetooth. Its failures still
            // consume the ordinary bounded budget and cannot create endless wireless retries.
            repeat(WirelessStartupPolicy.MAX_STARTUP_RETRIES) { attempt ->
                main.idleFor(Duration.ofSeconds(10))
                verify(old.controller, times(attempt + 1)).tryReconnectWireless(any())
                if (attempt + 1 < WirelessStartupPolicy.MAX_STARTUP_RETRIES) {
                    assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller,
                        "network recovery failed", reuseController = true))
                } else {
                    assertFalse(CarPlayBackgroundSession.requestRecovery(old.controller,
                        "network recovery exhausted", reuseController = true))
                }
            }
            closer.runAll()
            old.nextCompletion()(CarPlayController.CloseResult(true))
            main.idleFor(Duration.ofSeconds(30))
            assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, budget.retries)
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
            assertNull(field<AirPlaySession?>("activeSession"))
        }
    }

    @Test fun phoneArrivalCannotUndoAManualDisconnect() {
        assertPhoneArrivalDoesNotBypass("manual-stop") { CarPlayBackgroundSession.stop() }
    }

    @Test fun phoneArrivalCannotUndoConfirmedParking() {
        assertPhoneArrivalDoesNotBypass("parked") { confirmOff() }
    }

    @Test fun aStalePhoneCallbackCannotUndoTermination() {
        assertPhoneArrivalDoesNotBypass("manual-stop") { CarPlayBackgroundSession.terminate() }
    }

    @Test fun phoneArrivalCannotBypassTheDisabledAutomaticConnectionSetting() {
        assertPhoneArrivalDoesNotBypass("idle") {
            DiPlayPreferences.saveAutoConnect(app, false)
            CarPlayBackgroundSession.resumeService(app)
        }
    }

    @Test fun phoneArrivalCannotClearHotspotUserActionEvenAfterProcessRestart() {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.CONNECTED)
            val old = installBackground()
            val budget = CarPlayBackgroundSession.startupRetryBudget()
            assertNotNull(budget.nextDelayMillis())
            assertFalse(CarPlayBackgroundSession.requestRecovery(old.controller, "hotspot setup",
                WirelessStartupFailure.HOTSPOT_CONFIGURATION))
            phoneSample(SelectedPhoneState.DISCONNECTED)
            phoneSample(SelectedPhoneState.CONNECTED)
            main.idle()
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertTrue(field<Boolean>("retryRequiresUserAction"))
            assertTrue(SessionRecoveryRecord.load(app)!!.retryRequiresUserAction)
            assertEquals(1, budget.retries)
            verify(old.controller, never()).tryReconnectWireless(any())
            assertTrue(controllers.constructed().isEmpty())

            forgetProcessState()
            assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
            sample(true)
            CarPlayBackgroundSession.configureRecovery(app, configuration())
            phoneSample(SelectedPhoneState.DISCONNECTED)
            phoneSample(SelectedPhoneState.CONNECTED)
            main.idle()
            assertTrue(CarPlayBackgroundSession.retryStopped())
            assertTrue(field<Boolean>("retryRequiresUserAction"))
            assertEquals(1, CarPlayBackgroundSession.startupRetryBudget().retries)
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    @Test fun aStableCurrentSessionRetainsItsRetryBudgetUntilRecentMediaProgressAndThenRechecks() {
        val old = installBackground()
        val listener = backgroundListener(old)
        val session = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(session)
        `when`(old.controller.activeAirPlaySessionToken()).thenReturn(Any())
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        repeat(2) { assertNotNull(budget.nextDelayMillis()) }
        listener.onSessionActive(session)
        listener.onVideoFrameRendered(session)
        main.idleFor(Duration.ofSeconds(60))
        assertEquals(2, budget.retries)

        `when`(session.hasRecentMediaProgress(10_000L)).thenReturn(true)
        main.idleFor(Duration.ofSeconds(10))
        assertEquals(0, budget.retries)
        assertEquals(0, SessionRecoveryRecord.load(app)!!.retries)
    }

    @Test fun aClosedSessionCannotClearTheRetryBudgetEvenWhenItsMediaProgressWasRecent() {
        val old = installBackground()
        val listener = backgroundListener(old)
        val session = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(session)
        `when`(old.controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(session.hasRecentMediaProgress(10_000L)).thenReturn(true)
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        assertNotNull(budget.nextDelayMillis())
        listener.onSessionActive(session)
        listener.onVideoFrameRendered(session)
        main.idle()
        `when`(session.isClosed()).thenReturn(true)
        main.idleFor(Duration.ofSeconds(70))

        assertEquals(1, budget.retries)
    }

    @Test fun aPreviousSessionsStableTimerCannotClearAReplacementSessionsRetryBudget() {
        val old = installBackground()
        val listener = backgroundListener(old)
        val previous = mock(AirPlaySession::class.java)
        val replacement = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(previous)
        `when`(old.controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(previous.hasRecentMediaProgress(10_000L)).thenReturn(true)
        `when`(replacement.hasRecentMediaProgress(10_000L)).thenReturn(true)
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        repeat(2) { assertNotNull(budget.nextDelayMillis()) }
        listener.onSessionActive(previous)
        listener.onVideoFrameRendered(previous)
        main.idleFor(Duration.ofSeconds(55))

        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(replacement)
        listener.onSessionActive(replacement)
        listener.onVideoFrameRendered(replacement)
        listener.onVideoFrameRendered(previous)
        main.idleFor(Duration.ofSeconds(5))
        assertEquals(2, budget.retries)
        main.idleFor(Duration.ofSeconds(55))
        assertEquals(0, budget.retries)
    }

    @Test fun offCandidateDefersRecoveryAndOnCancellationContinuesTheSameAttempt() {
        val old = installBackground()
        `when`(old.controller.tryReconnectWireless(any())).thenReturn(true)
        sample(false)
        assertEquals("checking-vehicle-stop", CarPlayBackgroundSession.startBlockReason())
        assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller, "link-lost", reuseController = true))
        main.idleFor(Duration.ofSeconds(2))
        verify(old.controller, never()).tryReconnectWireless(any())
        val retries = CarPlayBackgroundSession.startupRetryBudget().retries
        sample(true)
        main.idleFor(Duration.ofSeconds(1))
        verify(old.controller, times(1)).tryReconnectWireless(any())
        assertEquals(retries, CarPlayBackgroundSession.startupRetryBudget().retries)
        assertFalse(CarPlayBackgroundSession.ignitionOff)
    }

    @Test fun lostVehicleSamplingExpiresOnlyTheCandidateAndUnblocksPreparation() {
        set("configuration", null)
        sample(false)
        assertFalse(CarPlayBackgroundSession.canStart())
        ignitionClock += 1_501
        main.idleFor(Duration.ofMillis(500))
        assertTrue(CarPlayBackgroundSession.canStart())
        assertFalse(CarPlayBackgroundSession.ignitionOff)
    }

    @Test fun verifiedDoorReturnCannotUndoManualDisconnect() {
        set("configuration", null)
        CarPlayBackgroundSession.stop()
        main.idle()
        val generation = field<Long>("useGeneration")
        CarPlayBackgroundSession.vehicleUseResumed("verified-driver-door-return")
        main.idle()
        assertEquals("manual-stop", CarPlayBackgroundSession.startBlockReason())
        assertEquals(generation, field<Long>("useGeneration"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun channelFactsSeparateMainRuntimeNavigationAndCurrentClusterProgress() {
        val old = installBackground()
        val session = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(session)
        val listener = backgroundListener(old)
        listener.onSessionActive(session)
        listener.onVideoFrameRendered(session)
        main.idle()
        val mainOnly = CarPlayBackgroundSession.channelReadiness()
        assertTrue(mainOnly.airPlayConnected)
        assertTrue(mainOnly.mainFrameRendered)
        assertFalse(mainOnly.wirelessRuntimeAuthenticated)
        assertFalse(mainOnly.navigationDataReceived)
        assertNull(mainOnly.cluster)
        listener.onWirelessRuntimeReady(session)
        listener.onNavigationDataReceived(session)
        val progress = ClusterContentProgress(7, 2, true, true, false, false, 1)
        listener.onClusterContentProgress(session, progress)
        listener.onClusterContentProgress(session, progress.copy(revision = 1, frameRendered = true))
        main.idle()
        assertTrue(CarPlayBackgroundSession.channelReadiness().wirelessRuntimeAuthenticated)
        assertTrue(CarPlayBackgroundSession.channelReadiness().navigationDataReceived)
        assertEquals(progress, CarPlayBackgroundSession.channelReadiness().cluster)
        listener.onWirelessRuntimeUnavailable(session)
        main.idle()
        val degraded = CarPlayBackgroundSession.channelReadiness()
        assertTrue(degraded.mainFrameRendered)
        assertFalse(degraded.wirelessRuntimeAuthenticated)
        assertFalse(degraded.navigationDataReceived)
        assertEquals(progress, degraded.cluster)
    }

    @Test fun oldChannelCallbacksCannotMarkAReplacementSessionReady() {
        val old = installBackground()
        val listener = backgroundListener(old)
        val first = mock(AirPlaySession::class.java)
        val replacement = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(first)
        listener.onSessionActive(first)
        listener.onWirelessRuntimeReady(first)
        main.idle()
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(replacement)
        listener.onSessionActive(replacement)
        listener.onWirelessRuntimeReady(first)
        listener.onNavigationDataReceived(first)
        listener.onClusterContentProgress(first, ClusterContentProgress(7, 5, true, true, true, true, 1))
        main.idle()
        assertEquals(CarPlayBackgroundSession.ChannelReadiness(airPlayConnected = true),
            CarPlayBackgroundSession.channelReadiness())
        CarPlayBackgroundSession.clear(old.controller)
        listener.onWirelessRuntimeReady(replacement)
        main.idle()
        assertEquals(CarPlayBackgroundSession.ChannelReadiness(), CarPlayBackgroundSession.channelReadiness())
    }

    @Test fun outerRetryConsumesTheOriginalStartupDeadlineAndStopsAnUnfinishedConnection() {
        val old = installBackground()
        val due = CarPlayBackgroundSession.startupRetryBudget().deadlineMillis
        assertNotNull(due)
        `when`(old.controller.tryReconnectWireless(any())).thenReturn(true)
        assertTrue(CarPlayBackgroundSession.requestRecovery(old.controller, "silent-startup", reuseController = true))
        main.idleFor(Duration.ofSeconds(119))
        assertEquals(due, CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        assertEquals(due, SessionRecoveryRecord.load(app)!!.startupDeadlineMillis)
        assertFalse(CarPlayBackgroundSession.retryStopped())
        verify(old.controller, times(1)).tryReconnectWireless(any())
        main.idleFor(Duration.ofSeconds(1))
        assertTrue(CarPlayBackgroundSession.retryStopped())
        assertNull(CarPlayBackgroundSession.snapshot())
        closer.runAll()
        verify(old.controller, times(1)).close()
    }

    @Test fun onlyCurrentMainVideoCompletesTheStartupBudgetWithoutForegroundRendering() {
        val old = installBackground()
        val listener = backgroundListener(old)
        val previous = mock(AirPlaySession::class.java)
        val current = mock(AirPlaySession::class.java)
        `when`(old.controller.currentActiveAirPlaySession()).thenReturn(current)
        `when`(previous.hasReceivedMainScreenVideo()).thenReturn(true)
        listener.onSessionActive(current)
        listener.onMainScreenVideoReceived(previous)
        listener.onVideoFrameRendered(current, 111)
        main.idle()
        assertNotNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        `when`(current.hasReceivedMainScreenVideo()).thenReturn(true)
        listener.onMainScreenVideoReceived(current)
        main.idle()
        assertNull(CarPlayBackgroundSession.startupRetryBudget().deadlineMillis)
        assertNull(SessionRecoveryRecord.load(app)!!.startupDeadlineMillis)
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        main.idleFor(Duration.ofSeconds(121))
        assertFalse(CarPlayBackgroundSession.retryStopped())
        assertSame(old.controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(old.controller, never()).close()
    }

    private fun beginAutomaticRequest(phone: SelectedPhoneState) {
        reset()
        observedPhoneState = phone
        assertTrue(CarPlayBackgroundSession.requestAutomaticConnection(app))
        sample(true)
        CarPlayBackgroundSession.configureRecovery(app, configuration())
        assertFalse(field<Boolean>("explicitUse"))
    }

    private fun phoneSample(value: SelectedPhoneState) {
        observedPhoneState = value
        phoneSampleCallbacks.last()(value)
    }

    private fun assertPhoneArrivalDoesNotBypass(reason: String, stop: () -> Unit) {
        withNativeFactory { controllers, _ ->
            beginAutomaticRequest(SelectedPhoneState.DISCONNECTED)
            val budget = CarPlayBackgroundSession.startupRetryBudget()
            repeat(2) { assertNotNull(budget.nextDelayMillis()) }
            stop()
            main.idle()
            CarPlayBackgroundSession.cancelAutomaticEntry("car-desktop")
            phoneSample(SelectedPhoneState.CONNECTED)
            main.idleFor(Duration.ofSeconds(10))
            assertEquals(reason, CarPlayBackgroundSession.startBlockReason())
            assertEquals(2, budget.retries)
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            assertTrue(controllers.constructed().isEmpty())
            assertNull(CarPlayBackgroundSession.snapshot())
        }
    }

    private fun beginUnknownRequest() {
        // A real new request must reset the initially confirmed ON used by the other fixtures.
        reset()
        CarPlayBackgroundSession.requestConnection(app)
        CarPlayBackgroundSession.configureRecovery(app, configuration())
        sample(null)
        main.idle()
    }

    /** Real Host preparation and BackgroundSession notifications, with native work held in flight. */
    private fun withPreparingHost(block: (CarPlayHostActivity, QueuedSessionExecutor) -> Unit) {
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "loadPersistedSettings")
        ReflectionHelpers.setField(host, "airPlayIdentity", AirPlayPersistence.loadIdentity(app))
        ReflectionHelpers.setField(host, "mfiTarget", MfiTarget.USB_CH341)
        ReflectionHelpers.setField(host, "wirelessPermissionsReady", true)
        ReflectionHelpers.setField(host, "microphoneAvailable", true)
        ReflectionHelpers.setField(host, "microphonePermissionResolved", true)
        // This lightweight fixture does not run Activity lifecycle methods. Supply the
        // same visibility boundary that real onCreate/onResume establishes before preparing.
        ReflectionHelpers.setField(host, "isActivityResumed", true)
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        setHostSize(host, 1920, 720)
        CarPlayBackgroundSession.setForegroundStartupReady(host, false)
        CarPlayBackgroundSession.watchIgnition(host, {}, {}, { startHost(host) })
        val queuedIo = QueuedSessionExecutor()
        set("stateIo", queuedIo)
        try { block(host, queuedIo) } finally {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.setField(host, "isActivityResumed", false)
            CarPlayBackgroundSession.setProjectionUiVisible(host, false)
            CarPlayBackgroundSession.stopIgnitionWatch(host)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            queuedIo.shutdownNow()
            set("stateIo", DirectSessionExecutor())
        }
    }

    private fun startHost(host: CarPlayHostActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "maybeStartCarPlay")
    }
    private fun notifyPreparationReady() {
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayBackgroundSession, "notifyUi")
        main.idle()
    }
    private fun setHostSize(host: CarPlayHostActivity, width: Int, height: Int) {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        ReflectionHelpers.setField(host, "activeDisplaySize", size)
    }

    /** Mockito construction scopes are thread-local, so pump native preparation on this thread. */
    private class QueuedSessionExecutor : AbstractExecutorService() {
        private val owner = Thread.currentThread()
        private val queue = java.util.ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) = synchronized(queue) {
            if (stopped) throw RejectedExecutionException("Session test executor is shut down")
            queue.addLast(command)
            Unit
        }

        fun runNext() {
            check(Thread.currentThread() === owner)
            val command = synchronized(queue) { queue.pollFirst() } ?: error("No queued session operation")
            command.run()
        }
        fun runAll() {
            check(Thread.currentThread() === owner)
            while (true) {
                val command = synchronized(queue) { queue.pollFirst() } ?: return
                command.run()
            }
        }

        override fun shutdown() = synchronized(queue) { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = synchronized(queue) {
            stopped = true
            queue.toMutableList().also { queue.clear() }
        }
        override fun isShutdown() = synchronized(queue) { stopped }
        override fun isTerminated() = synchronized(queue) { stopped && queue.isEmpty() }
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated()
    }

    private class ClosingSession(val controller: CarPlayController, val sink: AndroidMediaSink) {
        val completions = java.util.ArrayDeque<(CarPlayController.CloseResult) -> Unit>()
        fun nextCompletion(): (CarPlayController.CloseResult) -> Unit = completions.poll()
            ?: error("Controller close did not subscribe to resource completion")
    }

    private fun install(owner: Any): ClosingSession {
        val old = ClosingSession(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        doAnswer { invocation ->
            old.completions.add(invocation.getArgument<(CarPlayController.CloseResult) -> Unit>(0))
            null
        }.`when`(old.controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        CarPlayBackgroundSession.store(old.controller, old.sink, 1920, 720, owner, display) { done ->
            CarPlayBackgroundSession.clear(old.controller)
            CarPlayBackgroundSession.retire(old.controller, old.sink, "test-stop", done)
        }
        return old
    }

    private fun installBackground(): ClosingSession {
        val owner = Any()
        return install(owner).also { CarPlayBackgroundSession.handoffToBackground(owner) }
    }

    private fun backgroundListener(session: ClosingSession): AirPlaySessionListener =
        mockingDetails(session.controller).invocations.last { it.method.name == "attachUi" }
            .arguments[0] as AirPlaySessionListener

    private fun withNativeFactory(
        createdConfigurations: MutableList<Pair<CarPlayRuntimeConfig, AirPlayConfig>>? = null,
        onConstructed: () -> Unit = {},
        block: (MockedConstruction<CarPlayController>, MockedConstruction<AndroidMediaSink>) -> Unit,
    ) {
        mockConstruction(AndroidMediaSink::class.java).use { sinks ->
            mockConstruction(CarPlayController::class.java) { controller, construction ->
                onConstructed()
                createdConfigurations?.add((construction.arguments()[1] as CarPlayRuntimeConfig) to
                    (construction.arguments()[2] as AirPlayConfig))
                doAnswer { invocation ->
                    invocation.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true))
                    null
                }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
            }.use { controllers -> block(controllers, sinks) }
        }
    }

    private fun sample(value: Boolean?) { sampleMethod.invoke(CarPlayBackgroundSession, value) }
    private fun confirmOff() {
        feedOffSamples()
        assertTrue(CarPlayBackgroundSession.ignitionOff)
    }
    private fun feedOffSamples() {
        sample(false)
        repeat(6) { ignitionClock += 500; sample(false) }
    }

    private fun configuration() = CarPlayResumeConfiguration(
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("Autunn Play", "test", "Autunn Play", "serial", "1", "1", 1)),
        AirPlayConfig("Autunn Play", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0", AirPlayDisplayConfig(1280, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "saved-pairing"), 1920, 720, display,
        false, false, 14, true,
    )

    private fun reset() {
        CarPlayBackgroundSession.terminate()
        closer.runAll()
        forgetProcessState()
        app.getSharedPreferences("faw_session_control", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Discard only process memory; unlike a user stop, process death does not rewrite intent. */
    private fun forgetProcessState() {
        field<HongqiIgnitionMonitor?>("ignitionMonitor")?.close()
        set("ignitionMonitor", null)
        field<SelectedPhoneMonitor?>("phoneMonitor")?.close()
        set("phoneMonitor", null)
        set("phoneState", SelectedPhoneState.UNKNOWN)
        set("automaticBootstrapAttempted", false)
        closer.runAll()
        handler().removeCallbacksAndMessages(null)
        field<MutableList<*>>("retiring").clear()
        field<MutableList<*>>("stopWaiters").clear()
        set("stopping", false)
        set("starting", false)
        set("preparedResume", null)
        set("startupDeadlineCheck", null)
        set("startupDeadlineAt", null)
        field<MutableList<*>>("retirementWaiters").clear()
        set("intentRevision", 0L)
        set("durableIntentRevision", 0L)
        set("configurationRevision", 0L)
        set("configurationLoading", false)
        set("preparationFailure", null)
        set("activeSession", null)
        set("startupOwner", null)
        set("projectionUiOwner", null)
        set("foregroundStartupReady", true)
        set("app", null)
        set("service", null)
        set("configuration", null)
        set("desired", true)
        set("manualDisconnect", false)
        set("terminated", false)
        set("explicitRequestPending", false)
        set("explicitUse", false)
        set("restored", false)
        set("useGeneration", 0L)
        set("pendingReuse", null)
        set("recoveryGeneration", 0)
        set("retryPending", false)
        set("retryStopped", false)
        set("retryRequiresUserAction", false)
        set("retryBudget", WirelessStartupRetryBudget())
        set("automaticEntryRequested", false)
        set("serviceResumeInitialized", false)
        set("backgroundSetupRequired", null)
        set("backgroundEntryLaunch", null)
        set("backgroundEntrySequence", 0L)
        set("ignitionOwner", null)
        set("ignitionListener", null)
        set("ignitionReady", null)
        set("ignitionState", null)
        set("useSawIgnitionOn", false)
        set("restoredIgnitionPending", false)
        set("ignitionOff", false)
        set("lastIgnitionSample", null)
        set("ignitionSampleLogged", false)
        set("lastDriverDoor", null)
        set("lastDriverDoorHealth", null)
        ignitionClock = 0L
        set("ignitionDebouncer", IgnitionStateDebouncer(clock = { ignitionClock }))
        CarPlayBackgroundSession.clear()
        field<SessionLogFile?>("powerLog")?.let { AsyncDiagnosticLog.close(it) }
        set("powerLog", null)
        handler().removeCallbacksAndMessages(null)
    }

    private fun handler(): Handler = field("main")
    private fun <T> field(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, value)
}
