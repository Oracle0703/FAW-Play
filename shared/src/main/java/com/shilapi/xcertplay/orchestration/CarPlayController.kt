package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayResourceModes
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlayPhoneState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoPlaybackDelivery
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.AutunnHudRouteState
import com.shilapi.xcertplay.hud.AutunnLaneGuidanceState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.HongqiBluetoothMediaHandoff
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferReceiver
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayBonjourEvent
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WifiScanPause
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessInterfaceDiagnostics
import com.shilapi.xcertplay.network.WirelessReceiveDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.network.WirelessAddressRecovery
import com.shilapi.xcertplay.network.existingWifiHostAddresses
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import com.shilapi.xcertplay.licensing.ProductLicense
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Wires the xcertplay wired or wireless CarPlay play stack: iPhone bring-up, iAP2 control,
 * transport setup, and the AirPlay media/input sessions.
 *
 * MFi stays on the DiPlay path (`MfiTarget.LOCAL` plus `LocalMfiAuthenticationClient` reading
 * `offline-mfi/identity.pk8` and `certificate.p7b`). This class does not switch that provisioning
 * to xcertplay's `local-auth` documents.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
    private val addressRecovery: WirelessAddressRecovery = WirelessAddressRecovery(),
    private val onAddressRecoveryChanged: () -> Unit = {},
    private val connectionCycle: Long = 0L,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        WifiScanPause.restoreIfNeeded(context.applicationContext)
        AutunnNavigationOutputs.start(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val bluetoothMediaHandoff = HongqiBluetoothMediaHandoff(appContext)
    @Volatile private var wirelessBluetoothDevice: BluetoothDevice? = null
    private val vehicleStopping = AtomicBoolean(false)
    private val vehicleStopPauseSession = AtomicReference<AirPlaySession?>()
    @Volatile private var vehicleStopPauseWorker: Thread? = null
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = CarPlayInputExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    private val navigationFrameLock = Any()
    // The first iAP2 link may prefetch guidance before AirPlay becomes active. After a
    // disconnect, only a new active session may authorize vehicle navigation frames again.
    private var vehicleNavigationFramesAllowed = true
    private var vehicleNavigationLink: Any? = null
    private var vehicleNavigationLinkSession: AirPlaySession? = null
    private var reportedNavigationData: Pair<Any, AirPlaySession>? = null
    private val pendingNavigationFrames = java.util.ArrayDeque<Pair<Iap2Frame, Int>>()
    private var pendingNavigationBytes = 0
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    private var started = false
    @Volatile private var connectionStartedNs = 0L
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var activeWirelessSessionGeneration = -1
    @Volatile private var wirelessEndpointInvalidated = false
    @Volatile private var nativeAudioBorrowed = false
    @Volatile private var siriButtonSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    @Volatile private var requestedClusterUiShown = AutunnNavigationOutputs.clusterStreamRequestedShown()
    private val clusterUiRequestRevision = AtomicInteger()
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()
    private val mediaIntentRevision = AtomicInteger()
    private val lastUserMode = CarPlayLastUserMode(appContext.getSharedPreferences("carplay_last_user_mode", Context.MODE_PRIVATE))

    init { AutunnNavigationOutputs.setClusterStreamControl(::queueClusterUi) }

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the xcertplay file-transfer worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null
    private val fileTransferReceivers =
        java.util.IdentityHashMap<Iap2Session, Iap2FileTransferReceiver>()

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var wifiScanPause: WifiScanPause? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val bluetoothReadyLock = Object()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    @Volatile private var coldSessionProgress: WirelessSessionProgress<AirPlaySession>? = null
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "xcertplay-first-tcp-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var rfcommConnectWorker: Thread? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessTunnelSession: AirPlaySession? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAddressToken = -1L
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val vpnBindLock = Any()
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()
    private data class WirelessRecoveryTarget(
        val generation: Int,
        val controllerId: String,
        val bluetoothAddress: String,
        val hotspotInfo: WirelessHotspotInfo,
        val listener: AirPlayListenerIdentity,
    )
    @Volatile private var wirelessRecoveryTarget: WirelessRecoveryTarget? = null
    private val fastReconnect = WirelessFastReconnect<AirPlaySession>()
    // Policy/status commits are short. Platform readiness and Bonjour IO never hold this lock.
    private val fastReconnectCommitLock = Any()
    private class FastReconnectPublication(
        val attempt: WirelessFastReconnect.Attempt,
        val discovery: CarPlayBonjour,
    ) {
        var requestId: Int? = null
        var publishing = true
        var withdrawn = false
        var cleanupClaimed = false
    }
    private class FastReconnectPublicationCancelled : java.util.concurrent.CancellationException()
    private var fastReconnectPublication: FastReconnectPublication? = null
    @Volatile private var fastReconnectBonjourRequest: Int? = null

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    data class CloseResult(val resourcesReleased: Boolean, val failures: List<String> = emptyList())

    @Volatile private var teardownCompletion = CompletableFuture<CloseResult>()
    private val teardownFailures = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val pendingResourceClose = PendingResourceClose()
    @Volatile private var teardownThread: Thread? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            vpnLatch = CountDownLatch(1)
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun authorizeSession(session: AirPlaySession): Boolean {
            if (closed || wirelessEndpointInvalidated && activeSession !== session) return false
            // A re-SETUP inherits intent without sending a command to the superseded stream.
            session.retainClusterUiShown(requestedClusterUiShown)
            return !closed
        }

        override fun onSessionActive(session: AirPlaySession) {
            var replayedNavigationFrames = 0
            var replayedNavigationLink: Any? = null
            val replacement = synchronized(navigationFrameLock) {
                if (closed || session.isClosed()) return
                val replacement = activeSession !== session
                activeSession = session
                vehicleNavigationFramesAllowed = true
                if (replacement) {
                    AutunnNavigationOutputs.endNow()
                    AutunnNavigationOutputs.start(appContext)
                }
                // Wired links can precede RECORD. A wireless runtime already belongs to its
                // authenticated AirPlay candidate and must never be rebound to another phone.
                if (vehicleNavigationLink != null && vehicleNavigationLinkSession == null) {
                    vehicleNavigationLinkSession = session
                }
                // A replacement iAP2 link can send its one-shot maneuver/lane details before
                // AirPlay RECORD. Publish only after the corresponding session is active.
                while (pendingNavigationFrames.isNotEmpty()) {
                    val frame = pendingNavigationFrames.removeFirst().first
                    if (vehicleNavigationLinkSession === session) {
                        AutunnNavigationOutputs.onFrame(frame)
                        replayedNavigationFrames++
                    }
                }
                pendingNavigationBytes = 0
                if (replayedNavigationFrames > 0) vehicleNavigationLink?.let {
                    reportedNavigationData = it to session
                    replayedNavigationLink = it
                }
                replacement
            }
            if (closed || activeSession !== session) return
            if (replayedNavigationFrames > 0) {
                debugLog("Navigation: replayed $replayedNavigationFrames prefetched frames for the active session")
            }
            if (replacement) {
                nativeAudioBorrowed = false
                nativeScreenBorrowed = false
                siriButtonSession = null
                lastUserMode.begin(session, session.controllerId)
                // Older audio=false records were inferred from Android focus loss. They
                // cannot prove a user selected the vehicle source, so do not replay TAKE.
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            media.onSessionActive(session)
            publishAudioContext(session)
            if (config.transport == CarPlayTransport.WIRELESS) {
                wirelessBluetoothDevice?.let { device ->
                    bluetoothMediaHandoff.start(device) { !closed && activeSession === session }
                }
            }
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
            // Wireless adoption also has to pass its current-generation checks below.
            if (config.transport != CarPlayTransport.WIRELESS) onMainScreenReady(session)
            replayedNavigationLink?.let { notifyCurrentNavigationData(session, expectedLink = it) }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            lastUserMode.end(session)
            if (siriButtonSession === session) siriButtonSession = null
            SiriPlaybackRecovery.onSessionEnded(session)
            AirPlayPhoneState.forgetCalls(session)
            val wasActive = synchronized(navigationFrameLock) {
                if (closed || activeSession !== session) false else {
                    activeSession = null
                    vehicleNavigationFramesAllowed = false
                    if (vehicleNavigationLinkSession === session) clearVehicleNavigationLink()
                    AutunnNavigationOutputs.endNow()
                    true
                }
            }
            if (wasActive) {
                bluetoothMediaHandoff.stop()
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            if (wasActive) uiListener?.onSessionEnded(session)
        }

        override fun onSessionDisconnected(session: AirPlaySession) {
            synchronized(navigationFrameLock) {
                if (activeSession !== session || closed) return
                vehicleNavigationFramesAllowed = false
                if (vehicleNavigationLinkSession === session) clearVehicleNavigationLink()
                AutunnNavigationOutputs.endNow()
            }
            connectionDiagnostic("session disconnected id=${session.diagnosticId} atNs=${System.nanoTime()}")
            uiListener?.onSessionDisconnected(session)
        }

        override fun onMainScreenReady(session: AirPlaySession) {
            if (!mainScreenReady(session)) return
            uiListener?.onMainScreenReady(session)
        }

        override fun onVideoFrameRendered(session: AirPlaySession) = onVideoFrameRendered(session, 110)

        override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
            if (closed || activeSession !== session || session.isClosed()) return
            uiListener?.onVideoFrameRendered(session, screenType)
        }

        override fun onClusterContentProgress(session: AirPlaySession,
            progress: com.shilapi.xcertplay.airplay.ClusterContentProgress) {
            if (!closed && activeSession === session && !session.isClosed())
                uiListener?.onClusterContentProgress(session, progress)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            if (closed) return
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            if (closed) return
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The accessory's icon is the vehicle Home action.  Changing Android tasks is enough
        // to reveal the vehicle launcher; the AirPlay control session and its ownership state
        // must remain available so the next launcher click can bring this Host back immediately.
        override fun onHostUiRequested(session: AirPlaySession) {
            if (closed || activeSession !== session || session.isClosed()) return
            debugLog("CarPlay requested the car UI; opening the vehicle launcher while retaining the session")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                )
            }.onFailure { debugLog("Vehicle launcher could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (!closed && activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (!closed && activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            if (closed) return
            if (activeSession === session) {
                if (type == "modesChanged") {
                    media.onResourceModes(session, session.resourceModes.state)
                    publishAudioContext(session)
                    debugLog("Resources: screen=${session.resourceModes.state.screen} audio=${session.resourceModes.state.mainAudio}")
                }
                CallPlaybackRecovery.onPhoneState(this@CarPlayController, session, type)
                SiriPlaybackRecovery.onModesChanged(this@CarPlayController, session, type)
            }
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Replays only the current live session to the listener which has actually adopted it. */
    fun replayActiveSession(listener: AirPlaySessionListener) {
        mainHandler.post {
            if (uiListener !== listener) return@post
            val session = currentActiveAirPlaySession() ?: return@post
            listener.onSessionActive(session)
            if (uiListener !== listener || currentActiveAirPlaySession() !== session) return@post
            if (mainScreenReady(session)) listener.onMainScreenReady(session)
            if (uiListener !== listener || currentActiveAirPlaySession() !== session) return@post
            notifyWirelessRuntimeReady(wirelessGeneration.get(), session, listener = listener)
            notifyCurrentNavigationData(session, listener = listener)
            if (uiListener === listener && currentActiveAirPlaySession() === session && session.hasRenderedMainScreen()) {
                listener.onVideoFrameRendered(session, 110)
            }
        }
    }

    /** Current live session for a foreground host adopting this background controller. */
    fun currentActiveAirPlaySession(): AirPlaySession? {
        val session = activeSession ?: return null
        return session.takeIf { !closed && activeSession === session && !session.isClosed() }
    }

    private fun mainScreenReady(session: AirPlaySession): Boolean =
        !closed && activeSession === session && !session.isClosed() && session.mainScreenSessionToken() != null &&
            (config.transport != CarPlayTransport.WIRELESS || !isStaleWirelessRun(activeWirelessSessionGeneration))

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (closed || started) return
            started = true
        }
        ProductLicense.refreshAsync(appContext)
        connectionStartedNs = System.nanoTime()
        connectionDiagnostic("start transport=${config.transport} atNs=$connectionStartedNs")
        videoListener?.let { listener ->
            listener.onVideoMonitoringStarted()
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        // Binding needs no hotspot or MFi credentials; overlap it with their preparation.
        if (config.transport == CarPlayTransport.WIRELESS) bindVpn()
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        if (!authorizeNewConnection()) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (!authorizeNewConnection()) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    /** After the old session ends, recover its verified live network before rebuilding Bluetooth. */
    fun requiresFullWirelessRebuild(expectedConfig: CarPlayRuntimeConfig? = null): Boolean =
        closed || vehicleStopping.get() || !started || config.transport != CarPlayTransport.WIRELESS ||
            mfiSession == null || expectedConfig != null && !matchesRuntimeConfig(expectedConfig)

    fun tryReconnectWireless(expectedConfig: CarPlayRuntimeConfig? = null): Boolean = synchronized(lifecycleLock) reconnect@{
        if (closed || vehicleStopping.get() || !started || config.transport != CarPlayTransport.WIRELESS ||
            mfiSession == null || activeSession != null) return@reconnect false
        if (expectedConfig != null && !matchesRuntimeConfig(expectedConfig)) return@reconnect false
        if (!authorizeNewConnection()) return@reconnect false
        if (fastReconnect.attempt() != null) return@reconnect true
        connectionStartedNs = System.nanoTime()
        val target = wirelessRecoveryTarget
        if (target != null && target.generation == wirelessGeneration.get() && !wirelessFailureReported.get() &&
            bonjour != null && vpnService?.isAttached() == true && hotspot is WifiP2pGroupManager) {
            val attempt = synchronized(fastReconnectCommitLock) recovery@{
                if (isStaleWirelessRun(target.generation) || vehicleStopping.get()) return@recovery null
                // RECORD can adopt while the eligibility checks run. Keep that live controller
                // instead of beginning an unowned attempt or telling its caller to retire it.
                if (activeSession != null) return@reconnect true
                fastReconnect.begin(target.controllerId)
            } ?: return@reconnect false
            connectionDiagnostic("reconnect path=network-first generation=${target.generation} attempt=${attempt.id} atNs=${System.nanoTime()}")
            // Admission starts at begin(), even while the single preparation worker is busy.
            scheduleFastReconnectDeadline(target.generation, attempt)
            try {
                executor.execute { startFastWirelessReconnect(target, attempt) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                fallbackFastWirelessReconnect(target.generation, attempt, "worker_unavailable")
            }
            return@reconnect true
        }
        connectionDiagnostic("reconnect reuse=MFi-and-service atNs=${System.nanoTime()}")
        reconnectIphone()
        true
    }

    private fun startFastWirelessReconnect(target: WirelessRecoveryTarget,
        attempt: WirelessFastReconnect.Attempt) {
        var publication: FastReconnectPublication? = null
        try {
            if (isStaleWirelessRun(target.generation) || !fastReconnect.isCurrent(attempt)) return
            if (fastReconnect.remainingMillis(attempt) == 0L) {
                fallbackFastWirelessReconnect(target.generation, attempt, "deadline_before_preparation")
                return
            }
            val groupOwner = hotspot as? WifiP2pGroupManager
            val service = vpnService
            val discovery = bonjour
            val identification = wirelessRuntimeIdentification
            val endpoint = wirelessAirPlayEndpoint
            // Neither group readiness nor discovery IO may delay policy success or timeout.
            val group = groupOwner?.reuseHealthyGroup()
            val networkCurrent = group != null && group.interfaceName == target.hotspotInfo.interfaceName &&
                group.hostAddresses.toSet() == target.hotspotInfo.hostAddresses.toSet() &&
                group.bssid == target.hotspotInfo.bssid && service != null && service.isAttached() &&
                service.boundAddresses().all { it in group.hostAddresses } && discovery != null &&
                identification != null && endpoint != null
            val reserved = synchronized(fastReconnectCommitLock) {
                if (isStaleWirelessRun(target.generation) || !fastReconnect.isCurrent(attempt)) return
                if (fastReconnect.remainingMillis(attempt) == 0L) return@synchronized null
                if (!networkCurrent || hotspot !== groupOwner || vpnService !== service || bonjour !== discovery ||
                    wirelessRuntimeIdentification !== identification || wirelessAirPlayEndpoint !== endpoint) {
                    return@synchronized null
                }
                firstTcpWatchdog?.terminate()
                coldSessionProgress?.close()
                firstTcpWatchdog = newFirstTcpWatchdog(target.generation, target.listener)
                // The retained listener may already have authenticated this attempt's new
                // runtime while preparation was queued. Session/link transitions revoke their
                // own previous facts; delayed preparation must not erase the new ones.
                onStatus(CarPlayStatus.ConnectingControl, target.generation)
                FastReconnectPublication(attempt, discovery!!).also { fastReconnectPublication = it }
            }
            if (reserved == null) {
                fallbackFastWirelessReconnect(target.generation, attempt, "network_not_current_or_expired")
                return
            }
            publication = reserved
            reserved.discovery.requestReconnect(target.bluetoothAddress) { requestId ->
                synchronized(fastReconnectCommitLock) {
                    reserved.requestId = requestId
                    // This callback runs before Bonjour clears queues/closes sockets/updates JmDNS.
                    // A success after reservation can still revoke publication before that IO.
                    if (reserved.withdrawn || fastReconnectPublication !== reserved ||
                        isStaleWirelessRun(target.generation) || !fastReconnect.isCurrent(attempt) ||
                        fastReconnect.remainingMillis(attempt) == 0L) {
                        reserved.withdrawn = true
                        throw FastReconnectPublicationCancelled()
                    }
                    fastReconnectBonjourRequest = requestId
                }
            }
            connectionDiagnostic("reconnect stage=control-discovery generation=${target.generation} attempt=${attempt.id} atNs=${System.nanoTime()}")
        } catch (_: FastReconnectPublicationCancelled) {
            if (fastReconnect.remainingMillis(attempt) == 0L) {
                fallbackFastWirelessReconnect(target.generation, attempt, "deadline_before_publication")
            }
        } catch (error: Exception) {
            connectionDiagnostic("reconnect preparation failureClass=${error.javaClass.simpleName}")
            fallbackFastWirelessReconnect(target.generation, attempt, "prepare_failed")
        } finally {
            val cleanup = synchronized(fastReconnectCommitLock) {
                publication?.let {
                    it.publishing = false
                    if (it.withdrawn || !fastReconnect.isCurrent(it.attempt)) {
                        it.withdrawn = true
                        claimFastReconnectCleanupLocked(it)
                    } else null
                }
            }
            cleanup?.let(::finishFastReconnectPublication)
        }
    }

    private fun scheduleFastReconnectDeadline(generation: Int, attempt: WirelessFastReconnect.Attempt) {
        val remaining = fastReconnect.remainingMillis(attempt) ?: return
        try {
            startupTimer.schedule({
                if (isStaleWirelessRun(generation) || !fastReconnect.isCurrent(attempt)) return@schedule
                if (fastReconnect.remainingMillis(attempt) == 0L) {
                    fallbackFastWirelessReconnect(generation, attempt, "deadline_${fastReconnect.stage()}")
                } else scheduleFastReconnectDeadline(generation, attempt)
            }, remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (!closed) fallbackFastWirelessReconnect(generation, attempt, "timer_unavailable")
        }
    }

    private fun fallbackFastWirelessReconnect(generation: Int, attempt: WirelessFastReconnect.Attempt, reason: String) {
        val (nextGeneration, cleanup) = synchronized(fastReconnectCommitLock) {
            if (isStaleWirelessRun(generation) || !fastReconnect.fail(attempt)) return
            val cleanup = withdrawFastReconnectPublicationLocked(attempt)
            // Revoke the old listener before releasing this commit; restart IO runs outside it.
            wirelessGeneration.incrementAndGet() to cleanup
        }
        finishFastReconnectPublicationAsync(cleanup)
        connectionDiagnostic("reconnect path=bluetooth-fallback generation=$generation attempt=${attempt.id} reason=$reason totalMs=${elapsedMillis(connectionStartedNs)}")
        startWirelessRestart(nextGeneration)
    }

    private fun cancelFastWirelessReconnect() {
        val cleanup = synchronized(fastReconnectCommitLock) {
            fastReconnect.cancel()
            withdrawFastReconnectPublicationLocked()
        }
        finishFastReconnectPublicationAsync(cleanup)
    }

    private fun withdrawFastReconnectPublicationLocked(attempt: WirelessFastReconnect.Attempt? = null): FastReconnectPublication? {
        val publication = fastReconnectPublication ?: return null
        if (attempt != null && publication.attempt !== attempt) return null
        publication.withdrawn = true
        fastReconnectBonjourRequest = null
        // The publisher owns deferred cleanup while inside Bonjour IO. Media callbacks never
        // wait for its lifecycle lock, and success can cancel the deadline immediately.
        return if (publication.publishing) null else claimFastReconnectCleanupLocked(publication)
    }

    private fun claimFastReconnectCleanupLocked(publication: FastReconnectPublication): FastReconnectPublication? {
        if (fastReconnectPublication === publication) {
            fastReconnectPublication = null
            fastReconnectBonjourRequest = null
        }
        if (publication.requestId == null || publication.cleanupClaimed) return null
        publication.cleanupClaimed = true
        return publication
    }

    private fun finishFastReconnectPublication(publication: FastReconnectPublication) {
        try {
            publication.requestId?.let { publication.discovery.finishReconnect(it) }
        } catch (error: Exception) {
            connectionDiagnostic("reconnect discovery cleanup failureClass=${error.javaClass.simpleName}")
        }
    }

    private fun finishFastReconnectPublicationAsync(publication: FastReconnectPublication?) {
        if (publication == null) return
        Thread({ finishFastReconnectPublication(publication) }, "xcertplay-reconnect-discovery-finish")
            .apply { isDaemon = true; start() }
    }

    private fun onWirelessBonjourEvent(generation: Int, event: CarPlayBonjourEvent) {
        if (isStaleWirelessRun(generation)) return
        connectionDiagnostic("wireless generation=$generation ${event.diagnosticSummary()} totalMs=${elapsedMillis(connectionStartedNs)} atNs=${System.nanoTime()}")
        val attempt = fastReconnect.attempt() ?: return
        if (event is CarPlayBonjourEvent.EndpointsExhausted && event.requestId == fastReconnectBonjourRequest) {
            fallbackFastWirelessReconnect(generation, attempt, "control_endpoints_exhausted")
            return
        }
        if (event is CarPlayBonjourEvent.Probed && event.requestId == fastReconnectBonjourRequest) {
            val status = event.statusLine?.let {
                Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            val success = event.error == null && status != null && status in 200..299
            if (success) fastReconnect.progress(attempt, WirelessFastReconnect.Stage.CONTROL_ACCEPTED)
            connectionDiagnostic("reconnect stage=control-response attempt=${attempt.id} success=$success atNs=${System.nanoTime()}")
        }
    }

    private fun completeFastWirelessReconnect(generation: Int, session: AirPlaySession) {
        val completion = synchronized(fastReconnectCommitLock) {
            val attempt = fastReconnect.attempt() ?: return
            if (isStaleWirelessRun(generation) || activeSession !== session) return
            val authenticatedCompletion = wirelessConnectionProof.isAuthenticated(generation, session) &&
                fastReconnect.authenticated(attempt, session)
            val renderedCompletion = wirelessConnectionProof.hasRenderedFrame(generation, session) &&
                fastReconnect.rendered(attempt, session)
            if (!authenticatedCompletion && !renderedCompletion) return
            // No Bluetooth handoff is needed on this retained network. Its old handoff flag
            // must not leave a successfully recovered session displaying ConnectingControl.
            onStatus(CarPlayStatus.WirelessActive, generation)
            attempt to withdrawFastReconnectPublicationLocked(attempt)
        }
        finishFastReconnectPublicationAsync(completion.second)
        connectionDiagnostic("reconnect completed proof=authenticated-runtime-and-main-frame attempt=${completion.first.id} totalMs=${elapsedMillis(connectionStartedNs)} atNs=${System.nanoTime()}")
    }

    private fun matchesRuntimeConfig(other: CarPlayRuntimeConfig): Boolean =
        config.iphoneDevices == other.iphoneDevices && config.mfiTarget == other.mfiTarget &&
            config.ch341Devices == other.ch341Devices && config.ch341MfiResetGpio == other.ch341MfiResetGpio &&
            config.linuxI2cPath == other.linuxI2cPath && config.remoteMfiServer == other.remoteMfiServer &&
            config.remoteMfiToken == other.remoteMfiToken && config.hostMac.contentEquals(other.hostMac) &&
            config.linkLocal == other.linkLocal && config.identification == other.identification &&
            config.availableCurrentMilliAmps == other.availableCurrentMilliAmps && config.label == other.label &&
            config.hostName == other.hostName && config.transport == other.transport &&
            config.wirelessHotspotMode == other.wirelessHotspotMode &&
            config.manualHotspotSsid == other.manualHotspotSsid &&
            config.manualHotspotPassphrase == other.manualHotspotPassphrase &&
            config.manualHotspotBand == other.manualHotspotBand && config.manualHotspotChannel == other.manualHotspotChannel &&
            config.manualHotspotSecurity == other.manualHotspotSecurity &&
            config.wirelessBluetoothDeviceAddress == other.wirelessBluetoothDeviceAddress &&
            config.locationReportingEnabled == other.locationReportingEnabled &&
            config.wifiP2pPreferredChannel == other.wifiP2pPreferredChannel &&
            config.existingWifiSsid == other.existingWifiSsid && config.existingWifiPassphrase == other.existingWifiPassphrase

    private fun queueInputState(channel: String, token: Any, mask: Int,
        replaceMoves: Boolean = true, isCurrent: () -> Boolean, makeAction: (Int) -> Runnable): Boolean {
        val input = touchExecutor as? CarPlayInputExecutor
        if (input != null) return input.state(channel, token, mask, replaceMoves, isCurrent, makeAction)
        if (!isCurrent()) return false
        touchExecutor.execute(makeAction(mask))
        return true
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        if (!session.resourceModes.state.screenAllowed && contacts.any { it.down }) return false
        return try {
            val token = session.mainScreenSessionToken() ?: return false
            val queuedAt = System.nanoTime()
            val mask = contacts.filter { it.down && it.id in 0..1 }.fold(0) { value, contact -> value or (1 shl contact.id) }
            queueInputState("touch", token, mask, isCurrent = { !closed && activeSession === session && session.mainScreenSessionToken() === token }) { accepted -> Runnable {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    com.shilapi.xcertplay.media.TouchLatencyProbe.onQueueWait(System.nanoTime() - queuedAt)
                    session.sendTouch(contacts.map { it.copy(down = session.resourceModes.state.screenAllowed &&
                        it.down && it.id in 0..1 && accepted and (1 shl it.id) != 0) })
                }
            } }
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val release = !momentary && !state.select && !state.home && !state.back &&
            state.x == 0 && state.y == 0 && state.wheel == 0
        if (!session.resourceModes.state.screenAllowed && !release) return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            val send: (AirPlayKnobState) -> Unit = { value ->
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token &&
                    (session.resourceModes.state.screenAllowed || release)) session.sendKnob(value, momentary)
            }
            if (momentary && !state.select && !state.home && !state.back && state.x == 0 && state.y == 0 && state.wheel != 0) {
                (touchExecutor as? CarPlayInputExecutor)?.scroll(token, state.wheel, isCurrent = { !closed && activeSession === session && session.mainScreenSessionToken() === token }) { delta -> send(state.copy(wheel = delta)) }
                    ?: run { touchExecutor.execute { send(state) }; true }
            } else if (!momentary) {
                val mask = (if (state.select) 1 else 0) or (if (state.home) 2 else 0) or (if (state.back) 4 else 0)
                queueInputState("knob", token, mask, replaceMoves = state.wheel == 0, isCurrent = { !closed && activeSession === session && session.mainScreenSessionToken() === token }) { accepted -> Runnable {
                    send(state.copy(select = accepted and 1 != 0, home = accepted and 2 != 0, back = accepted and 4 != 0))
                } }
            } else {
                touchExecutor.execute { send(state) }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shilapi.xcertplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** A visible physical map and its session/stream generation; null for a paused/virtual/turn-card route. */
    fun dashboardMapRoute(): Any? {
        val session = activeSession ?: return null
        val content = session.clusterContentRoute() ?: return null
        val stream = content.first
        val route = session to stream
        val visibility = clusterUiVisibility
        val shown = visibility?.takeIf { it.first == route }?.second ?: true
        if (!DashboardMapEligibility.permits(content.second,
                dashboardMapOutputVisible, stream, shown, closed)) return null
        return Triple(session, stream, dashboardMapEpoch.get() to content.third)
    }

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun mainScreenAllowed(): Boolean = currentActiveAirPlaySession()?.resourceModes?.state?.screenAllowed != false

    fun mainAudioAllowed(): Boolean = currentActiveAirPlaySession()?.resourceModes?.state?.mainAudioAllowed != false

    @Volatile private var nativeScreenBorrowed = false
    fun nativeScreenInterrupted(): Boolean = nativeScreenBorrowed

    /** Screen selection never selects an audio source. */
    fun selectProjectionScreen(carPlay: Boolean): Boolean {
        val session = currentActiveAirPlaySession() ?: return false
        if (!carPlay) session.expectMainScreenEnd()
        return queueResourceChange {
            if (it !== session) return@queueResourceChange
            nativeScreenBorrowed = false
            lastUserMode.screen(session, carPlay)
            session.resourceModes.request(AirPlayResourceModes.Resource.SCREEN,
                if (carPlay) AirPlayResourceModes.Transfer.RELEASE else AirPlayResourceModes.Transfer.TAKE,
                if (carPlay) "user-carplay-screen" else "user-vehicle-screen")
        }
    }

    /** Verified SystemServiceConst focus event, tied to the phone present at notification time. */
    fun nativeScreenFocusChanged(change: Int, expectedToken: Any): Boolean {
        if (activeAirPlaySessionToken() !== expectedToken) return false
        return queueResourceChange { session ->
            if (activeAirPlaySessionToken() !== expectedToken) return@queueResourceChange
            val transfer = when (change) {
                -2 -> {
                    if (nativeScreenBorrowed) return@queueResourceChange
                    nativeScreenBorrowed = true
                    AirPlayResourceModes.Transfer.BORROW
                }
                1 -> {
                    if (!nativeScreenBorrowed) return@queueResourceChange
                    nativeScreenBorrowed = false
                    AirPlayResourceModes.Transfer.RETURN
                }
                -1 -> {
                    nativeScreenBorrowed = false
                    lastUserMode.screen(session, false)
                    AirPlayResourceModes.Transfer.TAKE
                }
                else -> return@queueResourceChange
            }
            session.resourceModes.request(AirPlayResourceModes.Resource.SCREEN, transfer,
                "vehicle-screen-focus", exclusive = change == -2)
        }
    }

    fun nativeAudioFocusChanged(change: Int) {
        // A local permanent focus loss is not evidence of a user source selection.
        if (change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && change != AudioManager.AUDIOFOCUS_GAIN) return
        queueResourceChange { session ->
            val transfer = when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Recheck at execution time: call state may arrive after the media
                    // listener queued this interruption and before the phone track exists.
                    if (phoneAudioState(session) == true ||
                        com.shilapi.xcertplay.airplay.AirPlaySpeechState.active(session) == true) {
                        debugLog("Audio: media interruption during phone or Siri; keeping resource ownership")
                        return@queueResourceChange
                    }
                    nativeAudioBorrowed = true
                    AirPlayResourceModes.Transfer.BORROW
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (!nativeAudioBorrowed) return@queueResourceChange
                    nativeAudioBorrowed = false
                    AirPlayResourceModes.Transfer.RETURN
                }
                else -> return@queueResourceChange
            }
            session.resourceModes.request(AirPlayResourceModes.Resource.MAIN_AUDIO, transfer, "vehicle-audio-focus")
        }
    }

    private fun queueResourceChange(action: (AirPlaySession) -> Unit): Boolean {
        val session = currentActiveAirPlaySession() ?: return false
        return try {
            touchExecutor.execute { if (currentActiveAirPlaySession() === session) action(session) }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }

    /** Preserve a physical press's session: releasing it must not operate the next phone. */
    fun sendSiriButton(down: Boolean): Boolean {
        val token = activeAirPlaySessionToken() ?: return false
        return sendSiriButton(down, token)
    }

    fun sendSiriButton(down: Boolean, expectedToken: Any): Boolean {
        if (activeAirPlaySessionToken() !== expectedToken) return false
        return queueResourceChange { session ->
            if (activeAirPlaySessionToken() !== expectedToken) return@queueResourceChange
            if (down) {
                if (siriButtonSession != null) return@queueResourceChange
                media.onSiriRequested(session)
                SiriPlaybackRecovery.onRequested(session, synchronized(playbackStatus) { playbackStatus.nowPlaying })
                if (session.sendSiriButton(true)) siriButtonSession = session
            } else {
                val pressed = siriButtonSession
                siriButtonSession = null
                if (pressed === session) session.sendSiriButton(false)
            }
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = activeAirPlaySessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && activeAirPlaySessionToken() === token) {
                    val playback = synchronized(playbackStatus) { playbackStatus.nowPlaying }
                    media.onSiriRequested(session)
                    SiriPlaybackRecovery.onRequested(session, playback)
                    session.invokeSiri()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean = queueMediaButton(index, userInitiated = true)

    fun resumeMediaAutomatically(): Boolean = queueMediaButton(
        com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY, userInitiated = false)

    private fun queueMediaButton(index: Int, userInitiated: Boolean): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val revision = if (userInitiated) mediaIntentRevision.incrementAndGet() else mediaIntentRevision.get()
        return try {
            if (userInitiated) CallPlaybackRecovery.cancel(session)
            SiriPlaybackRecovery.onMediaButton(session, index)
            touchExecutor.execute {
                if (!closed && activeSession === session && !session.isClosed()) {
                    if (!userInitiated && (!session.resourceModes.state.mainAudioAllowed ||
                            revision != mediaIntentRevision.get())) return@execute
                    val play = index == com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY ||
                        index == com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE &&
                            !synchronized(playbackStatus) { playbackStatus.playing }
                    if (userInitiated && play) {
                        nativeAudioBorrowed = false
                        lastUserMode.audio(session, true)
                        session.resourceModes.request(AirPlayResourceModes.Resource.MAIN_AUDIO,
                            AirPlayResourceModes.Transfer.RELEASE, "user-play")
                    }
                    if (userInitiated && (play || index == com.shilapi.xcertplay.airplay.CarPlayMediaButton.PAUSE ||
                            index == com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE))
                        lastUserMode.playback(session, play)
                    session.sendMedia(index)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Marks a real vehicle stop before [close]. True means a session was captured for the
     * teardown worker's explicit PAUSE; it does not claim that the phone acknowledged it.
     */
    fun pauseMediaForVehicleStop(): Boolean {
        vehicleStopping.set(true)
        // Revoke admission immediately; the teardown worker releases network resources later.
        cancelFastWirelessReconnect()
        bluetoothMediaHandoff.stop(restoreFactoryMedia = false)
        val session = activeSession
        if (session != null) lastUserMode.end(session) // Freeze before the teardown PAUSE.
        vehicleStopPauseSession.compareAndSet(null, session)
        return session != null
    }

    /** Momentary telephone HID key; the iPhone handles the current call context. */
    fun sendTelephonyButton(index: Int): Boolean {
        if (closed || (index != 1 && index != 3)) return false
        val token = activeAirPlaySessionToken() ?: return false
        val session = activeSession ?: return false
        if (activeAirPlaySessionToken() !== token) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && activeAirPlaySessionToken() === token) {
                    session.sendTelephony(index)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        val completion = synchronized(this) {
            if (closed) {
                if (!teardownCompletion.isDone || teardownCompletion.getNow(null)?.resourcesReleased == true) return
                // A failed attempt retains its resource handles. Retrying may confirm that an
                // asynchronous platform cleanup finished, or release the resource again safely.
                teardownCompletion = CompletableFuture()
            }
            closed = true
            cancelFastWirelessReconnect()
            dashboardMapOutputVisible = false
            teardownFailures.clear()
            teardownCompletion
        }
        // Revoke navigation admission and clear the visible/cached guidance before returning.
        // This also waits out any frame already publishing under navigationFrameLock. Network,
        // native media and worker release stay on the teardown thread below.
        synchronized(navigationFrameLock) {
            activeSession = null
            vehicleNavigationFramesAllowed = false
            clearVehicleNavigationLink()
            closeBestEffort("navigation outputs") { AutunnNavigationOutputs.endNow() }
        }
        synchronized(bluetoothReadyLock) { bluetoothReadyLock.notifyAll() }
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        val service = vpnService
        Thread(
            {
                try {
                    sendVehicleStopPause()
                    closeBestEffort("startup watchdogs") {
                        firstTcpWatchdog?.terminate()
                        coldSessionProgress?.close()
                    }
                    closeBestEffort("Bluetooth media handoff") {
                        bluetoothMediaHandoff.stop(restoreFactoryMedia = !vehicleStopping.get())
                    }
                    listOf(executor, touchExecutor, tunnelExecutor, startupTimer).forEach { it.shutdownNow() }
                    closeBestEffort("video monitoring") { videoGate?.close() }
                    closeBestEffort("video listener") { videoListener?.onVideoMonitoringStopped() }
                    closeBestEffort("connected state") { com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false) }
                    closeBestEffort("cluster stream control") { AutunnNavigationOutputs.clearClusterStreamControl(::queueClusterUi) }
                    closeReceivers()
                    closeTransportWithDeadline(service)
                } catch (error: Throwable) {
                    teardownFailures.add("controller: ${error.javaClass.simpleName}")
                    debugLog("Controller teardown failed", error)
                } finally {
                    // A timeout is a progress observation, never evidence of released resources.
                    awaitWorkerTermination("startup worker", executor)
                    awaitWorkerTermination("touch worker", touchExecutor)
                    awaitWorkerTermination("tunnel worker", tunnelExecutor)
                    awaitWorkerTermination("startup timer", startupTimer)
                    // A worker may have acquired a handle while close was being requested.
                    // Sweep again after workers exit, before declaring the controller released.
                    closeTransportWithDeadline(service)
                    val pauseWorker = vehicleStopPauseWorker
                    if (pauseWorker != null && closeBestEffort("vehicle stop media pause worker") {
                            pauseWorker.join(EXECUTOR_CLOSE_TIMEOUT_MILLIS)
                            if (pauseWorker.isAlive) throw IOException("Vehicle stop media pause worker is still running")
                        }) vehicleStopPauseWorker = null
                    // Keep the binder available for retry when its detach failed.
                    if (teardownFailures.isEmpty()) unbindVpn()
                    val failures = synchronized(teardownFailures) { teardownFailures.toList() }
                    val result = CloseResult(failures.isEmpty(), failures)
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "resourcesReleased=${result.resourcesReleased} failures=${failures.joinToString(",")}",
                    )
                    completion.complete(result)
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            teardownThread = this
            start()
        }
    }

    /** Cancels waits without surrendering resource ownership or allowing another stack. */
    fun cancelCloseWaits() {
        pendingResourceClose.interruptPending()
        teardownThread?.interrupt()
    }

    private fun closeTransportWithDeadline(service: CarPlayVpnService?) {
        val result = pendingResourceClose.run("transport", EXECUTOR_CLOSE_TIMEOUT_MILLIS) {
            if (!closeTransportResources(service)) throw IOException("Transport resources remain owned")
        }
        if (!result.released) teardownFailures.add(result.failure ?: "transport cancellation pending")
    }

    private fun closeTransportResources(service: CarPlayVpnService?): Boolean {
        var released = true
        fun release(name: String, action: () -> Unit): Boolean = closeBestEffort(name, action).also {
            if (!it) released = false
        }
        stopFileTransferReceivers()
        if (config.transport == CarPlayTransport.WIRELESS) {
            if (!closeWirelessStack(service, mode = WirelessCloseMode.RELEASE).resourcesReleased) released = false
            if (release("Wi-Fi scan pause") { wifiScanPause?.close() }) wifiScanPause = null
        } else {
            release("VPN/NCM") { service?.detach() }
            if (release("CSM") { csm?.let(::releasePlaySession) }) csm = null
        }
        if (release("USBMUX") { mux?.close() }) mux = null
        if (release("MFi") { mfiSession?.close() }) mfiSession = null
        wirelessRuntimeIdentification = null
        wirelessAirPlayEndpoint = null
        release("location provider") { locationProvider?.close() }
        return released
    }

    private fun sendVehicleStopPause() {
        val session = vehicleStopPauseSession.getAndSet(null) ?: return
        val finished = CountDownLatch(1)
        val worker = Thread({
            try {
                val sent = runCatching {
                    session.sendMedia(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PAUSE)
                }.isSuccess
                connectionDiagnostic("vehicle stop mediaPauseAttemptCompleted=$sent")
            } finally { finished.countDown() }
        }, "carplay-vehicle-stop-pause").apply { isDaemon = true }
        vehicleStopPauseWorker = worker
        worker.start()
        // A stalled phone must not hold audio/network shutdown hostage. Closing its session
        // below unblocks the write, and final completion still confirms this worker's exit.
        if (!finished.await(250, TimeUnit.MILLISECONDS)) connectionDiagnostic("vehicle stop mediaPause=pending; continuing shutdown")
    }

    fun dashboardContentUrl(): String? = activeSession?.clusterUrl()

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    val shown = requestedClusterUiShown
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = requestedClusterUiShown
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (!shown) session.setClusterUiShown(false)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        clusterUiStream = session to stream
                        clusterUiShown = shown
                        clusterUiVisibility = (session to stream) to shown
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    /** Callback admission is nonblocking and bounded; stale owners and stream generations never write. */
    private fun queueClusterUi(shown: Boolean) {
        if (closed) return
        requestedClusterUiShown = shown
        val revision = clusterUiRequestRevision.incrementAndGet()
        val session = activeSession ?: return
        val stream = session.clusterStream
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream ||
                        clusterUiRequestRevision.get() != revision ||
                        !AutunnNavigationOutputs.ownsClusterStreamControl(::queueClusterUi)) return@synchronized
                    applyClusterUi(shown, session, stream)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            debugLog("Cluster visibility request not admitted; retained for next setup or explicit request")
        }
    }

    // A genuine reopen/new-route request redraws the saved page even when a prior hide was superseded.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        applyClusterUi(shown, session, session.clusterStream)
    }

    /** Caller holds clusterUiLock; use the admitted generation rather than adopting another one. */
    private fun applyClusterUi(shown: Boolean, session: AirPlaySession, stream: Int) {
        if (closed || activeSession !== session || session.clusterStream != stream) return
        requestedClusterUiShown = shown
        if (stream <= 0) {
            session.setClusterUiShown(shown, stream)
            return
        }
        if (!shown && clusterUiStream == session to stream && !clusterUiShown) return
        val sent = session.setClusterUiShown(shown, stream)
        if (closed || activeSession !== session || session.clusterStream != stream) return
        clusterUiStream = session to stream
        clusterUiShown = shown
        clusterUiVisibility = (session to stream) to shown
        dashboardMapEpoch.incrementAndGet()
        debugLog("Cluster map requested=${if (shown) "shown" else "hidden"} stream=$stream written=$sent effect=unmeasured")
    }

    /**
     * Reports this close attempt on its completing thread (or immediately if already complete).
     * Call [close] before subscribing again after a failed attempt.
     */
    fun whenClosed(onClosed: (CloseResult) -> Unit) {
        teardownCompletion.thenAccept { result ->
            try { onClosed(result) }
            catch (error: Throwable) { debugLog("Controller close callback failed", error) }
        }
    }

    /** True only after all resources and workers were released; intended for a non-main thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownCompletion.get(timeoutMillis, TimeUnit.MILLISECONDS).resourcesReleased
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: TimeoutException) {
            false
        }
    }

    private fun awaitWorkerTermination(name: String, worker: ExecutorService) {
        worker.shutdownNow()
        try {
            if (!worker.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                teardownFailures.add("$name: cancellation pending")
        } catch (error: InterruptedException) {
            teardownFailures.add("$name: interrupted before termination")
            Thread.currentThread().interrupt()
        }
    }

    private fun beginVehicleNavigationLink(link: Any): Boolean = synchronized(navigationFrameLock) {
        if (closed) return@synchronized false
        clearVehicleNavigationLink()
        vehicleNavigationLink = link
        vehicleNavigationLinkSession = activeSession?.takeIf {
            vehicleNavigationFramesAllowed && !it.isClosed()
        }
        true
    }

    private fun endVehicleNavigationLink(link: Any) = synchronized(navigationFrameLock) {
        if (vehicleNavigationLink === link) clearVehicleNavigationLink()
    }

    /** Caller holds navigationFrameLock. Link identity also rejects late old-link frames. */
    private fun clearVehicleNavigationLink() {
        vehicleNavigationLink = null
        vehicleNavigationLinkSession = null
        reportedNavigationData = null
        pendingNavigationFrames.clear()
        pendingNavigationBytes = 0
    }

    private fun bufferNavigationFrame(frame: Iap2Frame) {
        if (frame.messageId != AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != AutunnLaneGuidanceState.LANE_GUIDANCE_INFORMATION) return
        val bytes = frame.payload.size
        if (bytes > MAX_PENDING_NAVIGATION_BYTES) return
        while (pendingNavigationFrames.size >= MAX_PENDING_NAVIGATION_FRAMES ||
            pendingNavigationBytes + bytes > MAX_PENDING_NAVIGATION_BYTES) {
            pendingNavigationBytes -= pendingNavigationFrames.removeFirst().second
        }
        // Iap2Frame owns a defensive copy of its payload.
        pendingNavigationFrames.addLast(frame to bytes)
        pendingNavigationBytes += bytes
    }

    /** Notify only the fact still owned by this link, ordered with runtime invalidation. */
    private fun notifyCurrentNavigationData(session: AirPlaySession, expectedLink: Any? = null,
        listener: AirPlaySessionListener? = uiListener) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) return
        fun notifyCurrent() = synchronized(navigationFrameLock) {
            val link = vehicleNavigationLink ?: return@synchronized
            val reported = reportedNavigationData
            if (listener == null || uiListener !== listener || closed || activeSession !== session ||
                session.isClosed() || !vehicleNavigationFramesAllowed || vehicleNavigationLinkSession !== session ||
                expectedLink != null && link !== expectedLink || reported == null ||
                reported.first !== link || reported.second !== session)
                return@synchronized
            if (config.transport == CarPlayTransport.WIRELESS) {
                val generation = wirelessGeneration.get()
                if (isStaleWirelessRun(generation) || wirelessTunnelChannel !== link ||
                    !hasAuthenticatedWirelessRuntime(generation, session)) return@synchronized
            }
            listener.onNavigationDataReceived(session)
        }
        // Never enter the resource lock from navigationFrameLock. Wired links have no
        // wireless runtime and retain their own link/session validation boundary.
        if (config.transport == CarPlayTransport.WIRELESS) synchronized(wirelessResourceLock) { notifyCurrent() }
        else notifyCurrent()
    }

    private fun onRouteFrame(frame: Iap2Frame, link: Any) {
        if (closed) return
        activeSession?.let { AirPlayPhoneState.observeCallState(it, frame) }
        var navigationDataSession: AirPlaySession? = null
        synchronized(navigationFrameLock) {
            if (!closed && vehicleNavigationLink === link) {
                val current = activeSession
                if (vehicleNavigationFramesAllowed && current != null && !current.isClosed() &&
                    vehicleNavigationLinkSession === current) {
                    AutunnNavigationOutputs.onFrame(frame)
                    if (com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED &&
                        (frame.messageId == AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE ||
                            frame.messageId == AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE ||
                            frame.messageId == AutunnLaneGuidanceState.LANE_GUIDANCE_INFORMATION) &&
                        reportedNavigationData != (link to current)) {
                        reportedNavigationData = link to current
                        navigationDataSession = current
                    }
                } else if (vehicleNavigationLinkSession?.isClosed() != true) {
                    bufferNavigationFrame(frame)
                }
            }
        }
        navigationDataSession?.let { notifyCurrentNavigationData(it, expectedLink = link) }
        com.shilapi.xcertplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previous = playbackStatus.nowPlaying
            playbackStatus.acceptUpdate(frame)?.let {
                Triple(it, it.playing != previous.playing, it.sourceApp != previous.sourceApp)
            }
        }?.let { (update, playingChanged, sourceChanged) ->
            if (playingChanged || sourceChanged) {
                debugLog("Audio: iPhone playback playing=${update.playing} " +
                    "sourceChanged=$sourceChanged sourceKnown=${update.sourceApp != null}")
            }
            CallPlaybackRecovery.onPlayback(activeSession, update)
            SiriPlaybackRecovery.onPlayback(activeSession, update)
            activeSession?.let { session ->
                if (!vehicleStopping.get() && !nativeAudioBorrowed &&
                    AirPlayPhoneState.active(session) != true &&
                    com.shilapi.xcertplay.airplay.AirPlaySpeechState.active(session) != true) {
                    lastUserMode.playback(session, update.playing)
                    if (update.playing && session.resourceModes.state.mainAudio == AirPlayResourceModes.Entity.IPHONE)
                        lastUserMode.audio(session, true)
                }
            }
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
        activeSession?.let(::publishAudioContext)
    }

    private fun publishAudioContext(session: AirPlaySession) {
        if (closed || activeSession !== session) return
        val playback = synchronized(playbackStatus) { playbackStatus.nowPlaying }
        media.onPlaybackContext(session, playback, phoneAudioState(session), com.shilapi.xcertplay.airplay.AirPlaySpeechState.active(session))
    }

    private fun phoneAudioState(session: AirPlaySession): Boolean? =
        when (AirPlayPhoneState.callKeyState(session)) {
            AirPlayPhoneState.CallKeyState.RINGING, AirPlayPhoneState.CallKeyState.IN_CALL -> true
            else -> AirPlayPhoneState.active(session)
        }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    /** Attaches the xcertplay session-12 receiver. DiPlay MFi is unchanged. */
    private fun startFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            if (fileTransferReceivers.containsKey(session)) return
            Iap2FileTransferReceiver(
                session = session,
                isArtworkCached = { false },
                onArtwork = { artwork ->
                    onArtworkTransfer(
                        com.shilapi.xcertplay.transport.Iap2ArtworkTransfer(
                            artwork.fileTransferId,
                            artwork.bytes,
                        ),
                    )
                },
                onLog = ::debugLog,
            ).also { fileTransferReceivers[session] = it }
        }
        receiver.start()
    }

    private fun stopFileTransferReceiver(session: Iap2Session?) {
        if (session == null) return
        val receiver = synchronized(fileTransferReceivers) { fileTransferReceivers.remove(session) }
        receiver?.close()
    }

    private fun stopFileTransferReceivers() {
        val receivers = synchronized(fileTransferReceivers) {
            fileTransferReceivers.values.toList().also { fileTransferReceivers.clear() }
        }
        receivers.forEach { it.close() }
    }

    private fun releasePlaySession(session: Iap2Session) {
        stopFileTransferReceiver(session)
        session.close()
    }

    private fun startMfi() {
        if (!authorizeNewConnection()) return
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val offlineDirectory = java.io.File(appContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        // DiPlay MFi: directory load of offline-mfi/identity.pk8 and certificate.p7b.
        // Do not replace this with xcertplay local-auth streams or generated test identities.
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled diplayMfi=kept")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        if (!authorizeNewConnection()) return
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            val next = wirelessGeneration.incrementAndGet()
            next
        }
        synchronized(bluetoothReadyLock) { bluetoothReadyLock.notifyAll() }
        executor.execute { runWireless(generation) }
    }

    private fun restartWireless() {
        if (!authorizeNewConnection()) return
        val (generation, cleanup) = synchronized(fastReconnectCommitLock) {
            fastReconnect.cancel()
            wirelessGeneration.incrementAndGet() to withdrawFastReconnectPublicationLocked()
        }
        finishFastReconnectPublicationAsync(cleanup)
        startWirelessRestart(generation)
    }

    private fun startWirelessRestart(generation: Int) {
        // Revocation is immediate; slow Bonjour/group release must never make the caller
        // (including the service's main thread) wait for the wireless resource lock.
        synchronized(bluetoothReadyLock) { bluetoothReadyLock.notifyAll() }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    if (closed || generation != wirelessGeneration.get()) return@Thread
                    firstTcpWatchdog?.terminate()
                    coldSessionProgress?.close()
                    wirelessFailureReported.set(false)
                }
                val released = closeWirelessStack(generation = generation)
                if (released.resourcesReleased) startWireless(generation)
                else if (!isStaleWirelessRun(generation)) fail(IOException("Wireless resources are not fully released"), generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        var bootstrapTransport: BluetoothRfcommDuplexStream? = null
        var bootstrapSession: Iap2Session? = null
        try {
            val bringupStarted = System.nanoTime()
            debugLog("wireless bring-up generation=$generation atNs=${System.nanoTime()} starting")
            val released = closeWirelessStack(generation = generation)
            if (!released.resourcesReleased) throw IOException("Wireless resources are not fully released")
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val addressToken = addressRecovery.token()
            wirelessAddressToken = addressToken

            // A disabled car radio is a prerequisite to wait for, not a failed connection.
            // Resolve it before creating an AP, so boot-time Bluetooth startup cannot cause
            // repeated hotspot teardown and controller retry backoff.
            val adapter = awaitWirelessBluetooth(generation) ?: return
            onStatus(CarPlayStatus.WaitingForPairedIphone, generation)
            val selectionStarted = System.nanoTime()
            val device = selectWirelessBluetoothDevice(adapter)
            connectionDiagnostic("prepare generation=$generation stage=bluetooth-select elapsedMs=${elapsedMillis(selectionStarted)} totalMs=${elapsedMillis(bringupStarted)}")
            if (isStaleWirelessRun(generation)) return
            wirelessBluetoothDevice = device
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = newFirstTcpWatchdog(generation, listenerIdentity)
            firstTcpWatchdog = watchdog
            coldSessionProgress?.close()
            coldSessionProgress = WirelessSessionProgress(
                schedule = { delay, action ->
                    val future = startupTimer.schedule(action, delay, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { future.cancel(false); Unit }
                    cancel
                },
                onTimeout = { stage ->
                    if (!isStaleWirelessRun(generation)) {
                        connectionDiagnostic("wireless startup stalled stage=post-tcp-${stage.name.lowercase()} generation=$generation")
                        fail(IOException("Wireless CarPlay startup stalled after TCP: $stage"), generation)
                        Thread({ closeWirelessStack(generation = generation) }, "xcertplay-session-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
            )
            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            onStatus(CarPlayStatus.StartingHotspot, generation)
            val hotspotStarted = System.nanoTime()
            val hotspotInfo = startWirelessHotspot(generation)
            connectionDiagnostic("prepare generation=$generation stage=hotspot elapsedMs=${elapsedMillis(hotspotStarted)} totalMs=${elapsedMillis(bringupStarted)}")
            if (isStaleWirelessRun(generation)) {
                return
            }
            pauseWifiScans(hotspotInfo.backend)
            val startedHotspot = hotspot
            media.setWirelessDiagnosticSource {
                if (isStaleWirelessRun(generation)) "generation=$generation wireless=stale"
                else "generation=$generation ${startedHotspot?.connectionDiagnosticSnapshot() ?: "wireless=unavailable"}"
            }
            val candidates = if (hotspotInfo.interfaceName != null) sampleHotspotAddresses(hotspotInfo)
                else hotspotInfo.hostAddresses
            val hostAddress = addressRecovery.select(hotspotInfo.hostAddress, candidates)
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) {
                    startedHotspot?.onCarPlayConfirmed()
                    bonjour?.captureControlDiscoveryStop()?.let { stop ->
                        runCatching { startupTimer.execute { stop() } }
                    }
                    if (addressRecovery.confirmed(addressToken, hostAddress)) onAddressRecoveryChanged()
                    activeSession?.controllerId?.takeIf { it.isNotBlank() }?.let { controllerId ->
                        wirelessRecoveryTarget = WirelessRecoveryTarget(generation, controllerId,
                            device.address, hotspotInfo, listenerIdentity)
                    }
                }
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
                fastPeerDetection = true,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val serviceStarted = System.nanoTime()
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            connectionDiagnostic("prepare generation=$generation stage=service-wait elapsedMs=${elapsedMillis(serviceStarted)} totalMs=${elapsedMillis(bringupStarted)}")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        additionalBindAddresses = candidates.filter { it != hostAddress },
                    )
                ) {
                    CarPlayVpnService.AttachResult.Started -> Unit
                    CarPlayVpnService.AttachResult.AlreadyStarted ->
                        throw IOException("Wireless AirPlay transport is already attached")
                    is CarPlayVpnService.AttachResult.Failed ->
                        throw IOException(result.message)
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val bonjourStarted = System.nanoTime()
            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                diagnostic = ::connectionDiagnostic,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event -> onWirelessBonjourEvent(generation, event) },
                additionalAddresses = service.boundAddresses().filter { it != hostAddress },
                targetBluetoothId = device.address,
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
                bonjourClient.start()
            }
            startedBonjour = bonjourClient
            connectionDiagnostic("prepare generation=$generation stage=bonjour elapsedMs=${elapsedMillis(bonjourStarted)} totalMs=${elapsedMillis(bringupStarted)}")
            diagnostics.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            val socket = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
            }
            logBluetoothConnectionSnapshot(device, "before-connect")
            val bluetoothStarted = System.nanoTime()
            try {
                connectBluetoothSocket(socket, device.address, generation)
                connectionDiagnostic("Bluetooth connect completed elapsedMs=${elapsedMillis(bluetoothStarted)}")
            } catch (error: Throwable) {
                connectionDiagnostic(
                    "Bluetooth connect failed elapsedMs=${elapsedMillis(bluetoothStarted)} " +
                        "failureClass=${diagnosticFailureClass(error)}",
                )
                logBluetoothConnectionSnapshot(device, "after-failure")
                throw error
            }
            debugLog("wireless RFCOMM connected address=${device.address}")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val stream = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
            }
            bootstrapTransport = stream
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                traceEnabled = { appContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getBoolean("debug_logs_enabled", false) },
                onTrace = ::debugLog,
            )
            startFileTransferReceiver(channel)
            bootstrapSession = channel
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) {
                    releasePlaySession(channel)
                    return
                }
                csm = channel
            }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessPublishedAddress = hostAddress
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler { session, stream ->
                startWirelessTunnelControl(generation, session, stream)
            }

            onStatus(CarPlayStatus.RunningWireless)
            debugLog(
                "wireless Bluetooth iAP2 bootstrap starting " +
                    "location=false vehicleStatus=false",
            )
            startedHotspot?.validateReady()
            var controlStageStarted = System.nanoTime()
            val firstStartSession = AtomicBoolean(false)
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = bootstrapIdentification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                beforeStartSession = { startedHotspot?.validateReady() },
                onStartSessionSent = {
                    watchdog.startSessionSent(it.sentAtNanos)
                    if (firstStartSession.compareAndSet(false, true))
                        connectionDiagnostic("wireless stage=start-session generation=$generation totalMs=${elapsedMillis(bringupStarted)} atNs=${it.sentAtNanos}")
                },
                onIncoming = { frame ->
                    if (!isStaleWirelessRun(generation)) onRouteFrame(frame, channel)
                },
                onProgress = { message ->
                    diagnostics.controlProgress(message)
                    debugLog("wireless generation=$generation atNs=${System.nanoTime()} $message")
                    val completedStage = when (message) {
                        "iap2 identification accepted" -> "iap2-identification"
                        "iap2 authentication accepted" -> "iap2-authentication"
                        else -> null
                    }
                    if (completedStage != null) {
                        connectionDiagnostic("prepare generation=$generation stage=$completedStage elapsedMs=${elapsedMillis(controlStageStarted)} totalMs=${elapsedMillis(bringupStarted)}")
                        controlStageStarted = System.nanoTime()
                    }
                },
            )
            if (isStaleWirelessRun(generation)) {
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    finishWirelessBootstrap(generation, result.terminal)
                }
                Iap2WirelessControlTerminal.TIMED_OUT -> {
                    finishWirelessBootstrap(generation, result.terminal)
                }
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            val expectedTransportFailure = bootstrapTransport?.causedByTransport(error) == true ||
                error is com.shilapi.xcertplay.transport.Iap2ChannelClosedException && bootstrapSession?.isClosed == true
            if (expectedTransportFailure && preserveWifiAfterBootstrap(generation, "transport_io")) {
                debugLog("wireless RFCOMM transport ended; current Wi-Fi session retained")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                fail(error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun startWirelessTunnelControl(generation: Int, session: AirPlaySession,
                                          stream: BlockingDuplexByteStream): Boolean {
        if (isStaleWirelessRun(generation) || !isWirelessSessionCandidate(session) ||
            config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                traceEnabled = { appContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getBoolean("debug_logs_enabled", false) },
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        startFileTransferReceiver(channel)
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation) || !isWirelessSessionCandidate(session)) {
                releasePlaySession(channel)
                return false
            }
            val previous = wirelessTunnelChannel
            if (previous != null) {
                // An unactivated candidate cannot replace another candidate's live tunnel.
                // Once RECORD selects a session, only that session may replace the old link.
                if ((wirelessTunnelSession !== session && activeSession !== session &&
                        wirelessTunnelSession?.isClosed() == false && !previous.isClosed) ||
                    !closeBestEffort("replaced tunneled iAP2 link") { releasePlaySession(previous) }) {
                    releasePlaySession(channel)
                    return false
                }
            }
            wirelessTunnelChannel = channel
            if (wirelessTunnelReady.get()) wirelessTunnelSession?.let { previousSession ->
                wirelessConnectionProof.authenticationEnded(generation, previousSession)
                if (!closed && activeSession === previousSession) uiListener?.onWirelessRuntimeUnavailable(previousSession)
            }
            wirelessTunnelSession = session
            wirelessTunnelReady.set(false)
        }
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        requireStartSession = false,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation, session, channel)
                        },
                        onIncoming = { frame ->
                            if (isCurrentWirelessTunnel(generation, session, channel)) {
                                onRouteFrame(frame, channel)
                            }
                        },
                        onProgress = { message -> debugLog("iAP tunnel generation=$generation atNs=${System.nanoTime()} $message") },
                    )
                    synchronized(wirelessResourceLock) {
                        if (!isCurrentWirelessTunnel(generation, session, channel)) return@execute
                        connectionDiagnostic("runtime end generation=$generation atNs=${System.nanoTime()} terminal=${result.terminal}")
                        when (result.terminal) {
                            Iap2WirelessControlTerminal.TIMED_OUT ->
                                fail(IOException("Wireless runtime iAP2 timed out"), generation)
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                                fail(IOException("Wireless runtime iAP2 tunnel closed"), generation)
                        }
                    }
                } catch (error: Throwable) {
                    synchronized(wirelessResourceLock) {
                        if (isCurrentWirelessTunnel(generation, session, channel)) {
                            connectionDiagnostic("runtime first error generation=$generation class=${diagnosticFailureClass(error)} atNs=${System.nanoTime()}")
                            debugLog("tunneled iAP2 control failed", error)
                            fail(error, generation)
                        }
                    }
                } finally {
                    clearWirelessTunnel(channel)
                    endVehicleNavigationLink(channel)
                }
            }
            true
        } catch (error: Throwable) {
            clearWirelessTunnel(channel)
            endVehicleNavigationLink(channel)
            closeBestEffort("tunneled iAP2 link") { releasePlaySession(channel) }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun newFirstTcpWatchdog(generation: Int, listener: AirPlayListenerIdentity): FirstTcpWatchdog {
        lateinit var watchdog: FirstTcpWatchdog
        watchdog = FirstTcpWatchdog(
        listener = listener,
        schedule = { delay, action ->
            val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { future.cancel(false); Unit }
            cancel
        },
        onTimeout = {
            if (!isStaleWirelessRun(generation)) {
                synchronized(wirelessResourceLock) {
                    if (!isStaleWirelessRun(generation) && fastReconnect.attempt() == null) {
                        prepareAlternateWirelessAddress(generation, watchdog)
                    }
                }
                fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                    "No AirPlay TCP after CarPlay StartSession"), generation)
                Thread({ closeWirelessStack(generation = generation) }, "xcertplay-startup-cleanup")
                    .apply { isDaemon = true; start() }
            }
        },
        log = { debugLog("wireless startup generation=$generation listener=${listener.id} $it") },
        )
        return watchdog
    }

    private fun sampleHotspotAddresses(info: WirelessHotspotInfo): List<InetAddress> = runCatching {
        val network = info.interfaceName?.let(NetworkInterface::getByName) ?: return@runCatching emptyList()
        if (!network.isUp || network.isLoopback) return@runCatching emptyList()
        existingWifiHostAddresses(Collections.list(network.inetAddresses), network.index)
    }.getOrDefault(emptyList())

    /** Reserve a family only after confirming the same healthy, owned GO is still present. */
    private fun prepareAlternateWirelessAddress(generation: Int, watchdog: FirstTcpWatchdog) {
        val manager = hotspot as? WifiP2pGroupManager ?: return
        val published = wirelessPublishedAddress ?: return
        val token = wirelessAddressToken
        val info = runCatching { manager.reuseHealthyGroup() }.getOrNull() ?: return
        val candidates = sampleHotspotAddresses(info)
        // Querying Android can outlive cancellation. Revalidate the run after the I/O;
        // a late timeout must not change the next run or a newly accepted session.
        if (isStaleWirelessRun(generation) || firstTcpWatchdog !== watchdog ||
            activeSession != null || hotspot !== manager) return
        val switched = addressRecovery.reserveAlternate(token, published, candidates)
        connectionDiagnostic("wireless address alternate=$switched preferred=${addressRecovery.snapshot().preferredFamily ?: "none"} " +
            "availableFamilies=${candidates.map { if (it is Inet6Address) "IPv6" else "IPv4" }.distinct()}")
        if (switched) onAddressRecoveryChanged()
    }

    private fun onHotspotRuntimeFailure(expected: WirelessHotspotManager, failure: IOException) {
        // The manager callback never waits for this Controller's resource lock.
        // A healthy GO can survive a Controller run; bind the event to its current run.
        val generation = wirelessGeneration.get()
        runCatching { startupTimer.execute {
            val current = synchronized(wirelessResourceLock) {
                if (hotspot !== expected || isStaleWirelessRun(generation)) false
                else {
                    val session = currentActiveAirPlaySession()
                    if (failure is WirelessStartupException && failure.reason == WirelessStartupFailure.HOTSPOT_NOT_READY &&
                        session?.controllerId != null) {
                        // A link broadcast invalidates discovery/new admission, not an authenticated TCP socket.
                        // Its actual EOF/write failure/kernel keepalive drives the normal bounded recovery path.
                        wirelessEndpointInvalidated = true
                        val discovery = bonjour
                        if (closeBestEffort("invalidated discovery") { discovery?.close() } && bonjour === discovery)
                            bonjour = null
                        connectionDiagnostic("wireless link invalidated; retaining authenticated TCP session=${session.diagnosticId}")
                        false
                    } else { fail(failure, generation); true }
                }
            }
            if (current) Thread({ closeWirelessStack(generation = generation) }, "xcertplay-p2p-recovery-cleanup")
                .apply { isDaemon = true; start() }
        } }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null
            // Fast reconnect preserves this listener but gives its new session a fresh watchdog.
            private fun currentWatchdog(): FirstTcpWatchdog = firstTcpWatchdog
                ?.takeIf { it.listener === watchdog.listener } ?: watchdog

            override fun authorizeSession(session: AirPlaySession): Boolean =
                !isStaleWirelessRun(generation) && fastReconnect.allows(session.controllerId) &&
                    sessionListener.authorizeSession(session)

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                val accepted = synchronized(fastReconnectCommitLock) {
                    !isStaleWirelessRun(generation) && currentWatchdog().accepted(event)
                }
                if (accepted) {
                    connectionDiagnostic("wireless stage=airplay-tcp generation=$generation totalMs=${elapsedMillis(connectionStartedNs)} atNs=${event.acceptedAtNanos}")
                    wirelessDiagnostics?.connectionAccepted()
                    coldSessionProgress?.accepted(event.acceptedAtNanos)
                    fastReconnect.attempt()?.let { fastReconnect.progress(it, WirelessFastReconnect.Stage.TCP_ACCEPTED) }
                }
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                currentWatchdog().terminate()
                fastReconnect.attempt()?.let {
                    fallbackFastWirelessReconnect(generation, it, "listener_transport_error")
                    return
                }
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                synchronized(fastReconnectCommitLock) {
                    if (isStaleWirelessRun(generation) || !currentWatchdog().sessionEstablished()) return
                    fastReconnect.attempt()?.let {
                        if (!fastReconnect.activate(it, session, session.controllerId)) return
                    }
                }
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
                synchronized(fastReconnectCommitLock) {
                    if (isStaleWirelessRun(generation) || activeSession !== session || session.isClosed()) return
                    // An attempt may begin after RECORD admission but before base adoption.
                    // Its exact current identity must own this session before proof is used.
                    fastReconnect.attempt()?.let {
                        if (!fastReconnect.activate(it, session, session.controllerId)) return
                    }
                    activeWirelessSessionGeneration = generation
                }
                // A background controller has no Surface; accepted SETUP can precede RECORD.
                onMainScreenReady(session)
                // SETUP video may render before RECORD. The decoder reports first output only
                // once, so reconcile the current stream and notify the adopting UI after active.
                if (session.hasRenderedMainScreen()) onVideoFrameRendered(session, 110)
                connectionDiagnostic("wireless stage=airplay-active generation=$generation totalMs=${elapsedMillis(connectionStartedNs)} atNs=${System.nanoTime()}")
                notifyWirelessRuntimeReady(generation, session)
                completeFastWirelessReconnect(generation, session)
                maybeCompleteWirelessHandoff()
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                // The UI may start the next recovery while handling this old session's end.
                // Do not mistake that newly-created attempt for the session which just ended.
                val failedAttempt = fastReconnect.attempt()?.takeIf { activeSession === session }
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
                failedAttempt?.let { fallbackFastWirelessReconnect(generation, it, "session_ended_before_proof") }
            }

            override fun onSessionDisconnected(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionDisconnected(session)
            }

            override fun onMainScreenReady(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                updateColdSessionProgress(session)
                sessionListener.onMainScreenReady(session)
            }

            override fun onMainScreenVideoReceived(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session || session.isClosed()) return
                updateColdSessionProgress(session)
                uiListener?.onMainScreenVideoReceived(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) = onVideoFrameRendered(session, 110)

            override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
                if (isStaleWirelessRun(generation) || activeSession !== session || session.isClosed()) return
                if (screenType != 110) {
                    uiListener?.onVideoFrameRendered(session, screenType)
                    return
                }
                val accepted = synchronized(fastReconnectCommitLock) {
                    !isStaleWirelessRun(generation) && activeSession === session && !session.isClosed() &&
                        currentWatchdog().sessionEstablished()
                }
                if (accepted) {
                    updateColdSessionProgress(session)
                    // Cold-session confirmation may persist the group. Keep that work outside
                    // the short recovery/status boundary; the proof retains its own ownership.
                    wirelessConnectionProof.rendered(generation, session)
                    completeFastWirelessReconnect(generation, session)
                    val firstFrame = synchronized(this) {
                        if (reportedFrameSession === session) false else {
                            reportedFrameSession = session
                            true
                        }
                    }
                    if (firstFrame) {
                        connectionDiagnostic("first frame generation=$generation session=${session.diagnosticId} totalMs=${elapsedMillis(connectionStartedNs)} atNs=${System.nanoTime()}")
                    }
                }
                // Surface replacement has its own first output. The adopting UI still needs
                // that event even after this session already supplied its connection proof.
                uiListener?.onVideoFrameRendered(session, screenType)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(if (message.contains(CONNECTION_DIAGNOSTIC_PREFIX)) "generation=$generation $message" else message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun updateColdSessionProgress(session: AirPlaySession) {
        if (activeSession !== session || session.isClosed()) return
        coldSessionProgress?.active(session, session.mainScreenSessionToken() != null,
            session.hasReceivedMainScreenVideo() || session.hasRenderedMainScreen())
    }

    private fun clearWirelessTunnel(channel: Iap2Session) = synchronized(wirelessResourceLock) {
        if (wirelessTunnelChannel === channel) {
            wirelessTunnelSession?.let { session ->
                wirelessConnectionProof.authenticationEnded(wirelessGeneration.get(), session)
                if (wirelessTunnelReady.get() && !closed && activeSession === session) {
                    uiListener?.onWirelessRuntimeUnavailable(session)
                }
            }
            wirelessTunnelChannel = null
            wirelessTunnelSession = null
            wirelessTunnelReady.set(false)
        }
    }

    private fun isWirelessSessionCandidate(session: AirPlaySession): Boolean {
        val active = activeSession
        return !session.isClosed() && (active == null || active === session || active.isClosed())
    }

    private fun isCurrentWirelessTunnel(generation: Int, session: AirPlaySession, channel: Iap2Session): Boolean =
        !isStaleWirelessRun(generation) && isWirelessSessionCandidate(session) &&
            wirelessTunnelSession === session && wirelessTunnelChannel === channel

    private fun onWirelessTunnelReady(generation: Int, session: AirPlaySession, channel: Iap2Session) {
        synchronized(wirelessResourceLock) {
            if (!isCurrentWirelessTunnel(generation, session, channel) || channel.isClosed) return
            wirelessTunnelReady.set(true)
            wirelessConnectionProof.authenticated(generation, session)
            // The candidate must complete identification, MFi and subscriptions before it can
            // replace the navigation source. Keep its own session even before AirPlay RECORD.
            synchronized(navigationFrameLock) {
                clearVehicleNavigationLink()
                vehicleNavigationLink = channel
                vehicleNavigationLinkSession = session
            }
        }
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        if (notifyWirelessRuntimeReady(generation, session, expectedChannel = channel)) {
            connectionDiagnostic("wireless stage=runtime-authenticated generation=$generation totalMs=${elapsedMillis(connectionStartedNs)} atNs=${System.nanoTime()}")
            completeFastWirelessReconnect(generation, session)
        }
        maybeCompleteWirelessHandoff()
    }

    /**
     * Share the clear/replacement ordering boundary: an old ready callback must never follow
     * that tunnel's unavailable callback. Built-in recipients only post to their UI owner;
     * they do not acquire another controller/resource lock while receiving this notification.
     */
    private fun notifyWirelessRuntimeReady(generation: Int, session: AirPlaySession,
        expectedChannel: Iap2Session? = null, listener: AirPlaySessionListener? = uiListener): Boolean =
        synchronized(wirelessResourceLock) {
            if (listener == null || uiListener !== listener || isStaleWirelessRun(generation) ||
                expectedChannel != null && wirelessTunnelChannel !== expectedChannel ||
                !hasAuthenticatedWirelessRuntime(generation, session)) return@synchronized false
            listener.onWirelessRuntimeReady(session)
            true
        }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                synchronized(wirelessResourceLock) {
                    if (generation != wirelessGeneration.get() || closed) return@Thread
                    if (!hasAuthenticatedWirelessRuntime(generation, wirelessTunnelSession)) {
                        wirelessActiveReported.set(false)
                        return@Thread
                    }
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    if (!closeBluetoothBootstrapTransport()) {
                        fail(IOException("Bluetooth bootstrap resources are not fully released"), generation)
                        return@Thread
                    }
                    onStatus(CarPlayStatus.WirelessActive, generation)
                }
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get()
                        ) {
                            return@Thread
                        }
                        if (preserveWifiAfterBootstrap(generation, "handoff_watchdog")) return@Thread
                        closeWirelessStack(generation = generation)
                        if (generation == wirelessGeneration.get()) {
                            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
                        }
                    },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun finishWirelessBootstrap(generation: Int, terminal: Iap2WirelessControlTerminal) {
        val reason = if (terminal == Iap2WirelessControlTerminal.CHANNEL_CLOSED) "eof" else "timeout"
        if (!preserveWifiAfterBootstrap(generation, reason)) {
            throw IOException("Wireless bootstrap ended ($reason) without a current authenticated runtime or main frame")
        }
    }

    private fun hasAuthenticatedWirelessRuntime(generation: Int, session: AirPlaySession?): Boolean =
        session != null && isWirelessSessionCandidate(session) && wirelessTunnelSession === session &&
            wirelessTunnelChannel?.isClosed == false && wirelessTunnelReady.get() &&
            wirelessConnectionProof.isAuthenticated(generation, session)

    private fun preserveWifiAfterBootstrap(generation: Int, reason: String): Boolean =
        synchronized(wirelessResourceLock) {
            val session = activeSession
            if (isStaleWirelessRun(generation)) return@synchronized false
            val authenticated = hasAuthenticatedWirelessRuntime(generation, wirelessTunnelSession)
            val rendered = session?.isClosed() == false && wirelessConnectionProof.hasRenderedFrame(generation, session)
            if (!authenticated && !rendered) return@synchronized false
            connectionDiagnostic("bootstrap ended=$reason generation=$generation atNs=${System.nanoTime()} " +
                "proof=${if (authenticated) "current_authenticated_runtime" else "current_session_first_frame"} " +
                "mainRendered=$rendered control=${if (authenticated) "runtime_authenticated" else "unavailable"}")
            if (!closeBluetoothBootstrapTransport()) return@synchronized false
            // Tunnel authentication remains a separate fact; a later type-130 can complete it.
            onStatus(CarPlayStatus.WirelessActive, generation)
            true
        }

    private fun closeBluetoothBootstrapTransport(): Boolean {
        var released = true
        val activeCsm = csm
        if (activeCsm != null) {
            if (closeBestEffort("wireless CSM") { releasePlaySession(activeCsm) }) csm = null else released = false
        }

        val activeStream = bluetoothStream
        if (activeStream != null) {
            if (closeBestEffort("wireless RFCOMM stream") { activeStream.close() }) bluetoothStream = null else released = false
        }

        val activeSocket = bluetoothSocket
        if (activeSocket != null) {
            if (closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }) bluetoothSocket = null else released = false
        }
        val connecting = rfcommConnectWorker
        if (connecting != null) {
            if (closeBestEffort("RFCOMM connect worker") {
                    if (connecting === Thread.currentThread()) throw IOException("RFCOMM worker cannot join itself")
                    connecting.join(EXECUTOR_CLOSE_TIMEOUT_MILLIS)
                    if (connecting.isAlive) throw IOException("RFCOMM connect worker is still running")
                }) rfcommConnectWorker = null else released = false
        }
        return released
    }

    private fun startIphone() {
        if (!authorizeNewConnection()) return
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        if (!authorizeNewConnection()) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                "action=${when {
                                    configuration != null -> "reuse-descriptors"
                                    reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                    else -> "reject-missing-configuration"
                                }}",
                        )
                        if (configuration != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        if (!authorizeNewConnection()) return
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        if (!authorizeNewConnection()) return
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        if (!authorizeNewConnection()) {
            usbSession.close()
            ncm.close()
            return
        }
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            // Temporary lab capture, limited to accessory/authentication messages and two minutes.
            try {
                val relay = carKitClient.openService(pairRecord, config.label, "com.apple.syslog_relay")
                Thread({
                    try {
                        relay.use {
                            val deadline = System.nanoTime() + 120_000_000_000L
                            val pending = StringBuilder()
                            val relevant = Regex(" (accessoryd|ACCCarPlayService|iap2d|CarPlay)([\\[(])", RegexOption.IGNORE_CASE)
                            while (!closed && System.nanoTime() < deadline) {
                                val bytes = relay.recv(8192, 1000) ?: continue
                                if (bytes.isEmpty()) break
                                pending.append(bytes.toString(Charsets.UTF_8).replace('\u0000', '\n'))
                                while (true) {
                                    val end = pending.indexOf("\n")
                                    if (end < 0) break
                                    val line = pending.substring(0, end)
                                    pending.delete(0, end + 1)
                                    if (relevant.containsMatchIn(line)) debugLog("PHONE ${line.take(2000)}")
                                }
                                if (pending.length > 65536) pending.clear()
                            }
                        }
                        debugLog("phone authentication diagnostic capture ended")
                    } catch (error: Exception) {
                        debugLog("phone authentication diagnostic capture ended: ${error.javaClass.simpleName}")
                    }
                }, "carplay-lab-phone-diagnostics").apply { isDaemon = true; start() }
                debugLog("phone authentication diagnostic capture started")
            } catch (error: Exception) {
                debugLog("phone authentication diagnostics unavailable: ${error.message}")
            }
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        debugLog("wired link TX begin ${wireSummary(data)}")
                        // Bound each TLS write while diagnosing the stalled certificate transfer.
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                traceEnabled = { appContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getBoolean("debug_logs_enabled", false) },
                onTrace = ::debugLog,
            )
            startFileTransferReceiver(csm)
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = vpnService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            if (!beginVehicleNavigationLink(csm)) return
            val result = try {
                Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                    identification = config.identification,
                    endpoint = endpoint,
                    availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                    timeoutMillis = controlLoopTimeoutMillis(),
                    locationProvider = locationProvider,
                    vehicleStatusProvider = vehicleStatusProvider,
                    onIncoming = { frame -> onRouteFrame(frame, csm) },
                    onProgress = { message -> debugLog("wired $message") },
                )
            } finally {
                endVehicleNavigationLink(csm)
            }
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val readyDeadline = System.nanoTime() + WirelessStartupPolicy.HOTSPOT_READY_MILLIS * 1_000_000
        val hotspotMode = config.wirelessHotspotMode
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) throw java.io.InterruptedIOException("Hotspot startup cancelled")
            hotspot?.let { previous ->
                if (hotspotMode == WirelessHotspotMode.WIFI_P2P && previous is WifiP2pGroupManager) {
                    previous.reuseHealthyGroup()?.let {
                        connectionDiagnostic("prepare generation=$generation stage=hotspot reuse=healthy-owned-group")
                        return it
                    }
                }
                if (!closeBestEffort("wireless hotspot") { previous.close() }) {
                    throw IOException("Previous wireless hotspot is not fully released")
                }
                if (hotspot === previous) hotspot = null
            }
        }
        if (com.shilapi.xcertplay.network.CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            val result = com.shilapi.xcertplay.network.CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !com.shilapi.xcertplay.network.CarHotspotSettings.enabled(appContext) },
                timeoutMillis = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting hotspot interface") },
            )
            val manualFallback = result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.UNSUPPORTED &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == null
            if (result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                        result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (hotspotMode == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> {
                lateinit var p2p: WifiP2pGroupManager
                p2p = WifiP2pGroupManager(appContext, ::debugLog,
                    preferredChannel = config.wifiP2pPreferredChannel,
                    onRuntimeFailure = { onHotspotRuntimeFailure(p2p, it) })
                p2p
            }
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext, ::debugLog)
            WirelessHotspotMode.EXISTING_WIFI -> {
                lateinit var existing: ExistingWifiManager
                existing = ExistingWifiManager(
                    appContext, config.existingWifiSsid, config.existingWifiPassphrase, ::debugLog,
                    onNetworkChanged = { onHotspotRuntimeFailure(existing,
                        WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY, "Existing Wi-Fi link changed")) })
                existing
            }
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = { debugLog("generation=$generation $it") },
                isCancelled = { isStaleWirelessRun(generation) },
            )
        }
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            if (hotspot != null) {
                manager.close()
                throw IOException("Previous wireless hotspot is still present")
            }
            hotspot = manager
        }
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (closeBestEffort(hotspotMode.name) { manager.close() } && hotspot === manager) hotspot = null
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || vehicleStopping.get() || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(this) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    /** Runs only on the startup worker. Cancellation wakes the wait without touching the radio. */
    private fun awaitWirelessBluetooth(generation: Int): BluetoothAdapter? {
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth adapter is unavailable")
        val started = System.nanoTime()
        var reportedWaiting = false
        while (!isStaleWirelessRun(generation)) {
            if (adapter.isEnabled) {
                if (isStaleWirelessRun(generation)) return null
                connectionDiagnostic("prepare generation=$generation stage=bluetooth-ready elapsedMs=${elapsedMillis(started)} waited=$reportedWaiting")
                return adapter
            }
            if (!reportedWaiting) {
                reportedWaiting = true
                onStatus(CarPlayStatus.WaitingForBluetooth, generation)
                connectionDiagnostic("prepare generation=$generation stage=bluetooth-wait reason=car_radio_disabled action=waiting_for_system_bluetooth")
            }
            synchronized(bluetoothReadyLock) {
                if (!isStaleWirelessRun(generation)) {
                    try {
                        bluetoothReadyLock.wait(BLUETOOTH_READY_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice =
        PairedIphoneSelector(appContext).select(adapter, config.wirelessBluetoothDeviceAddress)

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String, generation: Int) {
        cancelBluetoothDiscoveryBeforeConnect()
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        val connecting = Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
        }
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation) || bluetoothSocket !== socket || rfcommConnectWorker?.isAlive == true)
                throw IOException("RFCOMM startup is cancelled or still releasing")
            rfcommConnectWorker = connecting
            connecting.start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        synchronized(wirelessResourceLock) {
            if (!connecting.isAlive && rfcommConnectWorker === connecting) rfcommConnectWorker = null
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Discovery competes with RFCOMM. Missing SCAN permission must not break a paired link. */
    private fun cancelBluetoothDiscoveryBeforeConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            connectionDiagnostic("Bluetooth discovery cancellation skipped=scan-permission")
            return
        }
        val adapter = bluetoothAdapter ?: return
        try {
            val discovering = adapter.isDiscovering
            // The framework recommends cancellation even if discovery was initiated elsewhere.
            val accepted = adapter.cancelDiscovery()
            connectionDiagnostic("Bluetooth discovery cancellation discovering=$discovering accepted=$accepted")
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth discovery cancellation unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val uuids = device.uuids
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=${device.bondState} cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private enum class WirelessCloseMode { RETRY, RELEASE }
    private data class WirelessCloseResult(val resourcesReleased: Boolean, val hotspotRetained: Boolean = false)

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService, generation: Int? = null,
        mode: WirelessCloseMode = WirelessCloseMode.RETRY): WirelessCloseResult =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized WirelessCloseResult(false)
            var released = true
            fun release(name: String, block: () -> Unit): Boolean = closeBestEffort(name, block).also {
                if (!it) released = false
            }
            connectionDiagnostic("close begin generation=${wirelessGeneration.get()} atNs=${System.nanoTime()}")
            cancelFastWirelessReconnect()
            wirelessEndpointInvalidated = false
            wirelessRecoveryTarget = null
            synchronized(navigationFrameLock) { clearVehicleNavigationLink() }
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            coldSessionProgress?.close()
            val diagnostics = wirelessDiagnostics
            if (release("wireless diagnostics") { diagnostics?.close() }) wirelessDiagnostics = null
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            media.setWirelessDiagnosticSource { "wireless=closed" }
            // Music/video must stop before Bonjour or platform group removal can block.
            if (service != null && owner != null) release("AirPlay service") { service.detachWireless(owner) }
            val activeTunnel = wirelessTunnelChannel
            if (activeTunnel != null && release("tunneled iAP2 link") { releasePlaySession(activeTunnel) }) wirelessTunnelChannel = null
            if (wirelessTunnelChannel == null) wirelessTunnelSession = null

            if (!closeBluetoothBootstrapTransport()) released = false
            release("Bluetooth media handoff") { bluetoothMediaHandoff.stop(restoreFactoryMedia = !vehicleStopping.get()) }
            wirelessBluetoothDevice = null

            val activeBonjour = bonjour
            if (activeBonjour != null && release("Bonjour") { activeBonjour.close() }) bonjour = null

            val activeHotspot = hotspot
            // close() revokes the generation immediately. If it races a retry, upgrade this
            // cleanup to full release rather than retaining a radio for a closed controller.
            val retainHotspot = mode == WirelessCloseMode.RETRY && !closed && !vehicleStopping.get() &&
                config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P &&
                activeHotspot is WifiP2pGroupManager && activeHotspot.reuseHealthyGroup() != null
            if (activeHotspot != null && !retainHotspot && release("wireless hotspot") { activeHotspot.close() }) hotspot = null
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessPublishedAddress = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)

            connectionDiagnostic("close end generation=${wirelessGeneration.get()} resourcesReleased=$released hotspotRetained=$retainHotspot atNs=${System.nanoTime()}")
            WirelessCloseResult(released, retainHotspot)
        }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit): Boolean {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            if (closed) teardownFailures.add("$name: ${error.javaClass.simpleName}")
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
        return completed
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() = synchronized(vpnBindLock) {
        if (closed || vpnBound) return@synchronized
        vpnBound = true
        vpnLatch = CountDownLatch(1)
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() = synchronized(vpnBindLock) {
        if (!vpnBound) return@synchronized
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            fastReconnect.attempt()?.let {
                fallbackFastWirelessReconnect(wirelessGeneration.get(), it, "runtime_${error.javaClass.simpleName}")
                return
            }
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) {
                    connectionDiagnostic("failure duplicate generation=${wirelessGeneration.get()} class=${diagnosticFailureClass(error)} atNs=${System.nanoTime()}")
                    return
                }
                connectionDiagnostic("failure first generation=${wirelessGeneration.get()} class=${diagnosticFailureClass(error)} atNs=${System.nanoTime()}")
                firstTcpWatchdog?.terminate()
                coldSessionProgress?.close()
            }
            val causes = generateSequence(error) { it.cause }.toList()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                causes.any { it is com.shilapi.xcertplay.network.P2pResetRequiredException },
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason),
                if (wireless) wirelessGeneration.get() else null)
        }
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX cycle=$connectionCycle elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()} " +
                "attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun authorizeNewConnection(): Boolean = !closed

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        is CarPlayStatus.AuthorizationRequired -> "STEP license/required: device authorization required"
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForBluetooth ->
            "STEP bt/wait: waiting for car Bluetooth to be turned on; no hotspot or connection retry started"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP wireless/media-ready: Wi-Fi session retained; iAP2 control readiness is reported separately"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = com.shilapi.xcertplay.transport.IphoneBluetoothDevices.IAP2_SERVICE_UUID
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val BLUETOOTH_READY_POLL_MILLIS = 250L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val MAX_PENDING_NAVIGATION_FRAMES = 256
        private const val MAX_PENDING_NAVIGATION_BYTES = 256 * 1024
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
