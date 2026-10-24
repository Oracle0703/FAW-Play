package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayResourceModes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.hud.HongqiNavigation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [VideoSurfaceView], forwards touch to
 * the active AirPlay session. [CarPlayBackgroundSession] owns connection startup and recovery.
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private var connectionPanel: View? = null
    private var preparationRevealPanel: View? = null
    private var preparationRevealSurface: Surface? = null
    private var preparationRevealSession: AirPlaySession? = null
    private var preparationRevealGeneration = 0
    private var deferClusterForEntry = false
    private var clusterEntryPending = false
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private val startupRetryBudget = CarPlayBackgroundSession.startupRetryBudget()
    private var startupRetryStopped = false
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private fun createRuntimeConfig(): CarPlayRuntimeConfig =
        CarPlayRuntimeSettings.load(this, airPlayIdentity)

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus(getString(R.string.vpn_consent_was_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                AirPlayPersistence.saveLocationReportingEnabled(
                    this@CarPlayHostActivity,
                    false,
                )
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            requestStartupPrerequisites()
        }

    private var videoView: VideoSurfaceView? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var stageStatusView: TextView? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var navigationOverlaysAttached = false
    private var clusterTurnGuidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
    private var clusterLaneGuidance: com.shilapi.xcertplay.hud.ClusterLaneGuidance? = null
    private val clusterLaneOverlayListener: (com.shilapi.xcertplay.hud.ClusterLaneGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            if (!navigationOverlaysAttached || isDestroyed) return@runOnUiThread
            clusterLaneGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    private val clusterTurnOverlayListener: (com.shilapi.xcertplay.hud.ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            if (!navigationOverlaysAttached || isDestroyed) return@runOnUiThread
            clusterTurnGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    // Copies of stream 111 outside the dashboard (centre card, launcher maps) each get their own decoder.
    private val mirrorSink: (String, Surface?) -> Unit = { key, surface -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, surface) }
    private val mirrorsChanged: () -> Unit = {
        updateClusterMapShown()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    // With Usage Access the card shows only over a home screen; null = not known (no monitor).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private var isActivityStarted = false
    private var rejectAutomaticResume = false
    private var stoppedSinceResume = false
    private var isActivityResumed = false
    private var connectionLossReturn: Runnable? = null
    private var screenEntrySession: AirPlaySession? = null
    /** The current foreground entry is waiting for the phone to acknowledge screen ownership. */
    private var screenSelectionPendingSession: AirPlaySession? = null
    /** Last acknowledged screen owner observed while this Host was active. */
    private var observedScreenOwner: AirPlayResourceModes.Entity? = null
    private val hideIdleCenterMap = Runnable {
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) CenterMapOverlay.hide()
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = NATIVE_RESOLUTION_PERCENT
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = DEFAULT_NAVIGATION_STREAM_TYPE
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = true
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
        ) { night ->
            darkMode = night
            applyClusterTurnOverlay()
            appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
            logThemeState(nightModeDiagnosticSource, resources.configuration)
            syncAirPlayDarkMode(nightModeDiagnosticSource)
        }
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var appearanceWatch: AutoCloseable? = null
    private val vehicleNightMode by lazy { HongqiNightModeSource(this, nightModeController::vehicleChanged) }
    // Qisi IGN is the key state, rather than engine-running state. Automatic start-stop can stop
    // the engine while the ignition remains on, so it must not tear down a live CarPlay session.
    private var vehicleIgnitionOff = false
    private var vehicleIgnitionState: Boolean? = null
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private var reconnectRequestId = 0L
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishClusterEntry = Runnable { finishClusterEntryDeferral() }
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recoverClusterPresentation = Runnable {
        if (navigationOverlaysAttached && !isDestroyed && !shuttingDown.get()) ensureClusterPresentation()
    }
    private val refreshTurnOverlay = object : Runnable {
        override fun run() {
            com.shilapi.xcertplay.hud.AutunnNavigationOutputs.refreshTurnOverlay()
            mainHandler.postDelayed(this, TURN_OVERLAY_REFRESH_INTERVAL_MILLIS)
        }
    }
    // Some head units update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CarPlayBackgroundSession.acceptBackgroundEntry(intent, this)) {
            finish()
            return
        }
        val launcherEntry = LauncherEntryRouting.isLauncherIntent(intent)
        // The installed icon is an invisible router. A launcher click arrives here already
        // stamped with [LAUNCHER_ENTRY_MARKER]; anything else is an internal or background
        // launch. Resolve the disconnected branch before claiming a surface or inflating the
        // Host UI, so a connected return never passes through the software task.
        if (launcherEntry && !CarPlayBackgroundSession.launcherEntryBelongsToHost()) {
            redirectLauncherToSoftware()
            return
        }
        deferClusterForEntry = true
        // Also bound the deferral if Android accepts this Host without ever resuming it.
        mainHandler.postDelayed(finishClusterEntry, 2_000)
        CarPlayBackgroundSession.setProjectionUiVisible(this, true)
        NavigationWidgetUpdater.attach(applicationContext)
        CenterMapOverlay.requestShow = ::showCenterMap
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlaySettingsActivity::class.java)
                .putExtra("page", "home")
                .putExtra(EXPLICIT_SOFTWARE_PAGE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.registerDisplayListener(clusterDisplayListener, mainHandler)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        try {
            airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        } catch (error: IllegalStateException) {
            appendLog("CarPlay identity unavailable; original trust data retained")
            android.app.AlertDialog.Builder(this)
                .setTitle("CarPlay身份资料暂不可用")
                .setMessage(error.message ?: "身份资料异常，原数据已保留，请恢复备份后重试。")
                .setCancelable(false)
                .setPositiveButton("重试") { _, _ -> recreate() }
                .setNegativeButton("返回主页") { _, _ -> showDiPlayHome() }
                .show()
            return
        }
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host Android Back callback picturePanel=${picturePanel != null}")
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        CarPlayBackgroundSession.setForegroundStartupReady(this, false)
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        // Ignition ownership follows the process-local session, including Activity replacement and
        // background playback. Reuse the last confirmed state before attempting a new connection.
        CarPlayBackgroundSession.watchIgnition(this, ::onVehicleIgnitionState, ::appendLog, ::maybeStartCarPlay)
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onVehicleIgnitionState(state: Boolean?) {
        if (state == vehicleIgnitionState) return
        vehicleIgnitionState = state
        appendLog("vehicle ignition=${state?.let { if (it) "on" else "off" } ?: "unknown"}")
        when (state) {
            false -> if (CarPlayBackgroundSession.ignitionOff) disconnectForVehicleIgnitionOff()
            true -> {
                vehicleIgnitionOff = false
                startupRetryStopped = CarPlayBackgroundSession.retryStopped()
                startupFailureGeneration = -1
                startupRetryButton?.visibility = if (startupRetryStopped) View.VISIBLE else View.GONE
                appendLog("vehicle ignition on; startup waits for previous resources to close")
                if (!isDestroyed) maybeStartCarPlay()
            }
            null -> Unit
        }
    }

    private fun loadPersistedSettings() {
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
        )
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlaySettingsActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!CarPlayBackgroundSession.acceptBackgroundEntry(intent, this)) {
            // Android may resume an existing singleTask/singleTop even after rejecting its
            // stale intent. Restore its prior background position at the resume boundary.
            rejectAutomaticResume = stoppedSinceResume
            return
        }
        val launcherEntry = LauncherEntryRouting.isLauncherIntent(intent)
        if (launcherEntry && !CarPlayBackgroundSession.launcherEntryBelongsToHost()) {
            redirectLauncherToSoftware()
            return
        }
        updateDebugOverlays()
        rejectAutomaticResume = false
        setIntent(intent)
        completeAutomaticEntryIfVisible()
    }

    override fun onStart() {
        super.onStart()
        if (!::airPlayIdentity.isInitialized) return
        isActivityStarted = true
        updateDebugOverlays()
        appearanceWatch = AutunnUiTheme.watch(this) {
            if (!isFinishing && !isDestroyed) updateDebugOverlays()
        }
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || rejectAutomaticResume) {
            rejectAutomaticResume = false
            if (!isFinishing) moveTaskToBack(true)
            return
        }
        stoppedSinceResume = false
        if (!::airPlayIdentity.isInitialized) return
        isActivityResumed = true
        CarPlayBackgroundSession.setProjectionUiVisible(this, true)
        CarPlayBackgroundSession.refreshForegroundService()
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host lifecycle=resume focused=${hasWindowFocus()}")
        claimForegroundScreen()
        completeAutomaticEntryIfVisible()
        // Release factory view ownership in the background so safety views keep priority.
        HongqiWheelControls.onResume(this)
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold || savedDelay != ambientDelaySeconds) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds)
        }
        if (com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_AUTOMATIC_APPEARANCE_ENABLED) nightModeController.resume(systemNight)
        if (carPlayNightMode == CarPlayNightMode.AMBIENT) vehicleNightMode.start()
        else {
            vehicleNightMode.stop()
            nightModeController.vehicleChanged(null)
        }
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // The regular settings page brings this Activity back without recreating it.
        loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (DiLink51ClusterLayout.automatic(this) && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!DiLink51ClusterLayout.automatic(this)) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        // The instrument display usually exists before the display-added listener is registered.
        ensureClusterPresentation()
        AirPlayPersistence.overlaySettingsListener = { runOnUiThread { applyClusterTurnOverlay() } }
        navigationOverlaysAttached = true
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setTurnOverlayListener(clusterTurnOverlayListener)
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setLaneOverlayListener(clusterLaneOverlayListener)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.post(refreshTurnOverlay)
        val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
        val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        val systemBarsChanged = hideTopBar != savedHideTopBar || hideBottomBar != savedHideBottomBar
        hideTopBar = savedHideTopBar
        hideBottomBar = savedHideBottomBar
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        if (state != detectedCluster) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible}")
        detectedCluster = state
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        ensureClusterPresentation()
    }

    private fun finishClusterEntryDeferral() {
        mainHandler.removeCallbacks(finishClusterEntry)
        deferClusterForEntry = false
        if (!clusterEntryPending) return
        clusterEntryPending = false
        // Let the main Surface/cover transaction complete before creating another window.
        mainHandler.post { if (!isDestroyed && !shuttingDown.get()) ensureClusterPresentation() }
    }

    private fun ensureClusterPresentation() {
        if (isDestroyed || shuttingDown.get()) return
        if (deferClusterForEntry) {
            clusterEntryPending = true
            return
        }
        mainHandler.removeCallbacks(recoverClusterPresentation)
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        if (AdbClusterRouter.enabled(this)) {
            ClusterActivityOutput.bind(this, taskId) { onClusterSurface(it) }
            ClusterActivityOutput.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            runCatching { ClusterActivityOutput.ensure(this) }.onFailure {
                appendLog("Cluster activity: launch failed ${it.javaClass.simpleName}: ${it.message}")
            }
            return
        }
        ClusterActivityOutput.stop(this)
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        clusterPresentation?.let { current ->
            if (current.isShowing && current.display.isValid) {
                Hs5UltraPresentationHost.attach(current)
                return
            }
            dismissClusterPresentation()
        }
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            scheduleClusterPresentationRecovery()
            return
        }
        lateinit var presentation: ClusterMapPresentation
        presentation = ClusterMapPresentation(this, display, theme) { surface ->
            runOnUiThread {
                if (clusterPresentation === presentation) onClusterSurface(surface)
            }
        }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            Hs5UltraPresentationHost.detach(presentation)
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                onClusterSurface(null)
                com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setClusterMapShown(false)
                updateClusterMapShown()
                if (navigationOverlaysAttached) scheduleClusterPresentationRecovery()
            }
        }
        try {
            // Own the surface callbacks emitted synchronously by Presentation.show().
            clusterPresentation = presentation
            presentation.show()
            Hs5UltraPresentationHost.attach(presentation)
            updateClusterMapShown()
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            appendLog("Cluster map: presentation shown display=${display.displayId} name=${display.name}")
        } catch (error: RuntimeException) {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                onClusterSurface(null)
            }
            runCatching { presentation.dismiss() }
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
            scheduleClusterPresentationRecovery()
        }
    }

    private fun scheduleClusterPresentationRecovery() {
        if (!HongqiNavigation.supported()) return
        mainHandler.removeCallbacks(recoverClusterPresentation)
        mainHandler.postDelayed(recoverClusterPresentation, CLUSTER_PRESENTATION_RETRY_MILLIS)
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        val target = clusterLayers[fullMap] ?: createRetainedClusterPresentation(theme, fullMap) ?: return
        clusterPresentation = target
        target.outputSurface?.let(::onClusterSurface)
        target.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
        target.setMapVisible(visible)
        updateClusterMapShown()
        applyClusterTurnOverlay()
        val size = ClusterMapPresentation.sizeOf(target.display)
        val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme)
        if (controller != null && plan != null && clusterStreamViewport != null &&
            clusterStreamViewport != (plan.width to plan.height)) {
            reconnectAfterLoss("Cluster viewport changed; requesting its full stream")
        }
    }

    private fun createRetainedClusterPresentation(
        theme: DiLink51ClusterLayout.Theme,
        fullMap: Boolean,
    ): ClusterMapPresentation? {
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: return null
        lateinit var presentation: ClusterMapPresentation
        presentation = ClusterMapPresentation(this, display, theme) { surface ->
            runOnUiThread {
                if (clusterPresentation === presentation) onClusterSurface(surface)
            }
        }
        presentation.setOnDismissListener {
            if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
            if (clusterPresentation === presentation) clusterPresentation = null
            updateClusterMapShown()
        }
        return try {
            presentation.setMapVisible(false)
            clusterPresentation = presentation
            clusterLayers[fullMap] = presentation
            presentation.show()
            appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
            presentation
        } catch (error: RuntimeException) {
            clusterLayers.remove(fullMap)
            clusterPresentation = null
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun clusterPresentations(): List<ClusterMapPresentation> =
        (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()

    private fun applyClusterTurnOverlay() {
        val overlay = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
        ClusterActivityOutput.setTurnCard(if (overlay) clusterTurnGuidance else null,
            AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
            AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
            AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
            AirPlayPersistence.loadClusterTurnCardOpacityPercent(this), darkMode)
        for (presentation in clusterPresentations()) {
            presentation.setTurnCardOverlay(
                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
            )
            presentation.setTurnCardOpacity(AirPlayPersistence.loadClusterTurnCardOpacityPercent(this))
            presentation.setTurnCardNightMode(darkMode)
            presentation.setTurnCardGuidance(if (overlay) clusterTurnGuidance else null)
            presentation.setLaneGuidance(if (HongqiNavigation.supported()) clusterLaneGuidance else null)
        }
    }

    private fun dismissClusterPresentation() {
        mainHandler.removeCallbacks(recoverClusterPresentation)
        ClusterActivityOutput.stop(this)
        val presentations = clusterPresentations()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        presentations.forEach {
            Hs5UltraPresentationHost.detach(it)
            runCatching { it.dismiss() }
        }
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setClusterMapShown(false)
        controller?.setDashboardMapOutputVisible(false)
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if ((!DiLink51ClusterLayout.supported() && !AdbClusterRouter.enabled(this)) || surface == null) {
            clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) {
            if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute() &&
                !adbClusterConfigured && controller != null) {
                ClusterActivityOutput.setStreamActive(false)
                reconnectAfterLoss("DiLink 4 cluster confirmed; requesting its native stream")
            } else sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
        updateClusterMapShown()
    }

    private var adbClusterConfigured = false
    // Whether the running session's cluster stream was sized for a physical cluster display.
    private var clusterStreamOnDisplay = false
    private var clusterStreamViewport: Pair<Int, Int>? = null

    // DiLink 3 creates its cluster display only after DiPlay first projects to the cluster, which can
    // finish after the session has already asked for the virtual fallback stream.
    private val clusterDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (isDestroyed || !AirPlayPersistence.loadClusterMapEnabled(this@CarPlayHostActivity)) return
            val display = ClusterMapPresentation.findDisplay(this@CarPlayHostActivity, effectiveClusterTheme())
            if (display?.displayId != displayId) return
            appendLog("Cluster map: display ${display.name} appeared")
            ensureClusterPresentation()
            if (controller != null && !clusterStreamOnDisplay) {
                reconnectAfterLoss("Cluster display appeared; requesting its stream")
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (HongqiNavigation.supported() && clusterPresentation?.display?.displayId == displayId) {
                dismissClusterPresentation()
                scheduleClusterPresentationRecovery()
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            if (HongqiNavigation.supported()) ensureClusterPresentation()
        }
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        adbClusterConfigured = false
        clusterStreamOnDisplay = false
        clusterStreamViewport = null
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute()) {
            adbClusterConfigured = true
            clusterStreamOnDisplay = true
            return diLink4ClusterDisplayConfig().also {
                MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                appendLog("Cluster activity: requesting stream 111 at ${it.widthPixels}x${it.heightPixels}; safeArea=${it.safeArea} drawOutside=${it.safeAreaDrawOutside}; ADB task routing")
            }
        }
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: return virtualClusterDisplayConfig()
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x <= 0 || size.y <= 0) return virtualClusterDisplayConfig()

        clusterStreamOnDisplay = true
        if (DiLink51ClusterLayout.supported()) {
            val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
            return DiLink51ClusterLayout.streamConfig(theme, AirPlayPersistence.loadClusterContent(this)).also {
                clusterStreamViewport = plan.width to plan.height
                MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                appendLog("Cluster map: requesting viewport ${it.widthPixels}x${it.heightPixels}; layout=$theme viewport=$plan")
            }
        }
        if (DiLink4ClusterDisplay.matches(display.name, size.x, size.y)) {
            return diLink4ClusterDisplayConfig().also {
                MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
            }
        }
        return CarPlayClusterDisplay.config(
            size.x,
            size.y,
            AirPlayPersistence.loadClusterMapScalePercent(this),
            AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
            AirPlayPersistence.loadClusterMarkerVerticalStep(this),
            AirPlayPersistence.loadClusterContent(this),
            baseSafeArea = if (com.shilapi.xcertplay.hud.HongqiNavigation.supported()) {
                CarPlayClusterDisplay.HONGQI_SAFE_AREA_PERCENT
            } else CarPlayClusterDisplay.SAFE_AREA_PERCENT,
        ).also {
            MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
            appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} safeArea=${it.safeArea} url=${it.initialUrl}")
        }
    }

    private fun diLink4ClusterDisplayConfig(): AirPlayDisplayConfig =
        DiLink4ClusterDisplay.streamConfig(
            AirPlayPersistence.loadClusterContent(this),
            AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
            AirPlayPersistence.loadClusterMarkerVerticalStep(this),
            AirPlayPersistence.loadClusterSafeAreaRect(this),
        )

    private fun virtualClusterDisplayConfig(): AirPlayDisplayConfig {
        // Launcher and centre-card mirrors still need a stream when no physical cluster exists.
        MapMirrors.streamAspect = MapMirrors.VIRTUAL_STREAM_ASPECT
        return CarPlayClusterDisplay.config(
            widthPixels = VIRTUAL_CLUSTER_WIDTH_PIXELS,
            heightPixels = VIRTUAL_CLUSTER_HEIGHT_PIXELS,
            scalePercent = NATIVE_RESOLUTION_PERCENT,
            content = AirPlayPersistence.loadClusterContent(this),
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        ).also {
            appendLog("Cluster map: requesting virtual ${it.widthPixels}x${it.heightPixels} (16:9) stream for launcher/center card")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host Back action=${event.action} source=${event.source} knob=${AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)}")
        }
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_WHEEL_CONTROL_ENABLED) return super.dispatchKeyEvent(event)
        if (AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = controller?.requestSiri() == true
            appendLog("Siri: voice key ${event.keyCode} sent=$sent")
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelConnectionLossReturn()
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host focus=$hasFocus resumed=$isActivityResumed")
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
            completeAutomaticEntryIfVisible()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        cancelConnectionLossReturn()
        isActivityResumed = false
        updateDebugOverlays()
        finishClusterEntryDeferral()
        CarPlayBackgroundSession.setProjectionUiVisible(this, false)
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host lifecycle=pause")
        screenEntrySession = null
        screenSelectionPendingSession = null
        observedScreenOwner = null
        HongqiWheelControls.onPause(this)
        nightModeController.pause()
        vehicleNightMode.stop()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        cancelConnectionLossReturn()
        CarPlayBackgroundSession.cancelAutomaticEntry("user-left-host")
        // Leaving this task is the Android Home transition (including the CarPlay icon).
        // Keep the controller/session intact; the foreground entry path explicitly requests
        // the CarPlay screen again if the phone withdrew its main stream while showing Home.
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host left for vehicle Home; retaining CarPlay session")
        super.onUserLeaveHint()
    }

    override fun onStop() {
        stoppedSinceResume = true
        finishClusterEntryDeferral()
        // A newly created Host can be stopped before it ever resumes. Such a path has
        // no onPause callback, so it must also release its provisional page ownership.
        CarPlayBackgroundSession.setProjectionUiVisible(this, false)
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host lifecycle=stop")
        appearanceWatch?.close()
        appearanceWatch = null
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    /** Shows the dashboard map as a card on the centre screen while DiPlay is in the background. */
    private fun showCenterMap() {
        if (isDestroyed || shuttingDown.get() || sink == null || isActivityStarted) return
        if (!AirPlayPersistence.loadCenterMapOverlay(this) || !AirPlayPersistence.loadClusterMapEnabled(this)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(this)) return
        if (MapMirrors.launcherShowsMap) return // the launcher has the map on its own screen
        // Without the stream the card would stay black; it follows once the stream starts.
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) return
        if (!CenterMapOverlay.permitted(this)) {
            appendLog("Centre map: no permission to draw over other apps")
            return
        }
        // Without Usage Access or when auto-hide is disabled, the card shows over any app, as before.
        if (AirPlayPersistence.loadCenterMapAutoHide(this) && HomeScreenMonitor.hasAccess(this)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(this, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start() // its first answer shows the card
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(applicationContext, MapMirrors.streamAspect, ::onCenterMapSurface) {
            startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        appendLog("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${SCREEN_TYPE_ALT in activeScreenStreamTypes}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        appendLog("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!AirPlayPersistence.loadCenterMapAutoHide(this)) {
            homeMonitor?.stop()
            if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
        } else if (!visible) CenterMapOverlay.hide()
        else if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
    }

    private fun onCenterMapSurface(surface: Surface?) {
        MapMirrors.set(MapMirrors.CARD, surface)
        appendLog(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
    }

    // The dashboard map pause must not stop the stream while a copy of the map is on screen.
    private fun updateClusterMapShown() {
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.setClusterMapShown((clusterPresentation != null ||
            (ClusterActivityOutput.hasConfirmedRoute() && clusterSurface != null)) && !MapMirrors.any)
        // Wheel zoom needs a physical map; centre-screen copies must not suppress that eligibility.
        val surface = clusterSurface?.takeIf { it.isValid }
        val presentation = clusterPresentation
        val presented = surface != null && presentation?.isShowing == true && presentation.mapVisible &&
            presentation.outputSurface === surface
        val direct = surface != null && adbClusterConfigured && ClusterActivityOutput.hasConfirmedRoute()
        controller?.setDashboardMapOutputVisible(presented || direct)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        updateDebugOverlays()
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        refreshDisplaySizeAfterLayout()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    override fun onDestroy() {
        cancelPreparationReveal()
        mainHandler.removeCallbacks(finishClusterEntry)
        deferClusterForEntry = false
        clusterEntryPending = false
        cancelConnectionLossReturn()
        CarPlayBackgroundSession.setProjectionUiVisible(this, false)
        navigationOverlaysAttached = false
        CarPlayBackgroundSession.stopIgnitionWatch(this)
        CarPlayBackgroundSession.handoffToBackground(this)
        ++reconnectRequestId
        reconnectScheduled = false
        appearanceWatch?.close()
        appearanceWatch = null
        nightModeController.pause()
        videoView?.close()
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.removeCallbacks(recoverClusterPresentation)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.clearTurnOverlayListener(clusterTurnOverlayListener)
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.clearLaneOverlayListener(clusterLaneOverlayListener)
        clusterMonitor?.stop()
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(clusterDisplayListener)
        mainHandler.removeCallbacks(hideIdleCenterMap)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow == (::showCenterMap)) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
        dismissClusterPresentation()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(pollConfiguration)
        releaseMainSurface()
        AsyncDiagnosticLog.close(sessionLog, "Activity destroyed")
        sessionLog = null
        super.onDestroy()
    }

    private fun releaseMainSurface() {
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        }
        currentSurface = null
        updateDebugOverlays()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoSurfaceView(this, onSurface = { surface ->
            if (surface == null) {
                releaseMainSurface()
                appendLog("Video surface destroyed")
            } else {
                currentSurface = surface
                attachSurface(surface)
                updateDebugOverlays()
                appendLog("Video surface created")
            }
        }, onSizeChanged = { width, height ->
            scheduleDisplaySize(width, height)
        })
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        val stage = TextView(this).apply { text = getString(R.string.getting_carplay_ready) }
        val recovery = Button(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }
        val retry = Button(this).apply {
            text = getString(R.string.retry_carplay_connection)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                if (!CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) ||
                    shuttingDown.get() || handshakeResetInProgress) return@setOnClickListener
                startupRetryStopped = false
                reconnectAttempts = 0
                visibility = View.GONE
                CarPlayBackgroundSession.retryManually()
                maybeStartCarPlay()
            }
            startupRetryButton = this
        }
        val viewport = AutunnSettingsPage.createPreparation(this, stage, retry, recovery)
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        gestureOverlay = gestureLayer
        stageStatusView = stage
        connectionPanel = viewport
        updateDebugOverlays()
        return root
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        ProjectionDecoderCapabilities(hevcEnabled, hevcSoftwareDecoderEnabled).evaluate(display)

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val alignedWidth = (size.width / VIDEO_DIMENSION_ALIGNMENT * VIDEO_DIMENSION_ALIGNMENT)
            .coerceAtLeast(VIDEO_DIMENSION_ALIGNMENT)
        val alignedHeight = (size.height / VIDEO_DIMENSION_ALIGNMENT * VIDEO_DIMENSION_ALIGNMENT)
            .coerceAtLeast(VIDEO_DIMENSION_ALIGNMENT)
        val alignedSize = DisplaySize(alignedWidth, alignedHeight)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) PRIMARY_INPUT_KNOB else PRIMARY_INPUT_TOUCH,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedUiScalePercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > NATIVE_RESOLUTION_PERCENT) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > NATIVE_RESOLUTION_PERCENT) {
            resolutionFallback = true
            displayScalePercent = NATIVE_RESOLUTION_PERCENT
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, CarPlayDisplayScale.DEFAULT_TENTHS)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedUiScalePercent)} percent=$requestedUiScalePercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        val cluster = clusterDisplayConfig()
        return AirPlayConfig(
            deviceName = "Autunn Play",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = display,
            cluster = cluster,
            receiverManagedClusterUi = !(cluster != null &&
                com.shilapi.xcertplay.hud.HongqiNavigation.supported()),
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = com.shilapi.xcertplay.hud.AutunnOutputSettings.videoWhileParkedActive(this),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon = AirPlayIconRepository(this).load()

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog
            private var disconnectedSession: AirPlaySession? = null

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || session.isClosed() || shuttingDown.get() ||
                        controller?.currentActiveAirPlaySession() !== session || handshakeResetInProgress ||
                        !CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity)) {
                        return@runOnUiThread
                    }
                    if (vehicleIgnitionOff) {
                        appendLog("Ignoring CarPlay session while vehicle ignition is off")
                        disconnectForVehicleIgnitionOff()
                        return@runOnUiThread
                    }
                    releaseTouch()
                    activeAirPlaySession = session
                    screenSelectionPendingSession = null
                    observedScreenOwner = session.resourceModes.state.screen
                    if (CarPlayBackgroundSession.connectedProjectionReady()) cancelConnectionLossReturn()
                    disconnectedSession = null
                    ++reconnectRequestId
                    reconnectScheduled = false
                    claimForegroundScreen()
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    appendLog("AirPlay session active")
                    ensureClusterPresentation()
                    completeAutomaticEntryIfVisible()
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    completeAutomaticEntryIfVisible()
                }
            }

            override fun onMainScreenReady(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration == restartGeneration && !shuttingDown.get() &&
                        controller?.currentActiveAirPlaySession() === session && !session.isClosed() &&
                        CarPlayBackgroundSession.connectedProjectionReady()) cancelConnectionLossReturn()
                }
            }

            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                if (type != "modesChanged") return
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || session.isClosed()) return@runOnUiThread
                    val owner = session.resourceModes.state.screen
                    val previous = observedScreenOwner
                    observedScreenOwner = owner
                    if (owner == AirPlayResourceModes.Entity.IPHONE || owner == null) {
                        if (screenSelectionPendingSession === session) {
                            screenSelectionPendingSession = null
                            appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} screen owner acknowledged for foreground Host")
                        }
                        return@runOnUiThread
                    }
                    // VEHICLE is an acknowledged resource owner, not a navigation command. A
                    // stale VEHICLE notification is expected immediately after a launcher return:
                    // the phone has not yet acknowledged the new TAKE request. Only a transition
                    // from an already acknowledged iPhone owner may hide a visible Host.
                    if (screenSelectionPendingSession === session || previous != AirPlayResourceModes.Entity.IPHONE) return@runOnUiThread
                    if (isActivityResumed) {
                        releaseTouch()
                        if (controller?.nativeScreenInterrupted() != true) moveTaskToBack(true)
                    }
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration == restartGeneration && activeAirPlaySession === session) {
                        cancelConnectionLossReturn()
                        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
                    }
                }
            }

            override fun onSessionDisconnected(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || shuttingDown.get() ||
                        !CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) || handshakeResetInProgress) return@runOnUiThread
                    val active = activeAirPlaySession
                    if (disconnectedSession === session || active != null && active !== session ||
                        active == null && !CarPlayBackgroundSession.active) return@runOnUiThread
                    disconnectedSession = session
                    releaseTouch()
                    activeAirPlaySession = null
                    screenEntrySession = null
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    returnToConnectionSettingsAfterLoss(session.diagnosticId)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                // Early notification normally handles the page. Keep fallback for listener adoption.
                onSessionDisconnected(session)
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || shuttingDown.get() ||
                        disconnectedSession !== session || activeAirPlaySession != null) return@runOnUiThread
                    disconnectedSession = null
                    appendLog("AirPlay session cleanup complete; reconnecting")
                    reconnectAfterLoss("AirPlay session ended", reuseController = true)
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    if (shuttingDown.get() || handshakeResetInProgress ||
                        !CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity)) return@runOnUiThread
                    val hadProjection = activeAirPlaySession != null || CarPlayBackgroundSession.active
                    releaseTouch()
                    activeAirPlaySession = null
                    screenEntrySession = null
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    if (hadProjection) returnToConnectionSettingsAfterLoss(null)
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    private fun returnToConnectionSettingsAfterLoss(sessionId: Long?) {
        val disconnectedAtNs = System.nanoTime()
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} UI disconnected session=$sessionId atNs=$disconnectedAtNs foreground=$isActivityResumed")
        if (!isActivityResumed || !hasWindowFocus() || isFinishing || isDestroyed || shuttingDown.get() ||
            !CarPlayBackgroundSession.automaticConnectAllowed()) return
        // Recover brief interruptions without changing pages. The accepted primary stream,
        // rather than an assumed iPhone Wi-Fi setting, determines whether recovery succeeded.
        CarPlayBackgroundSession.requestAutomaticEntry()
        if (!CarPlayBackgroundSession.automaticEntryPending() || connectionLossReturn != null) return
        val check = object : Runnable {
            override fun run() {
                // A cancelled callback may already have been dispatched. It cannot affect
                // the next foreground period or replace the next loss's grace deadline.
                if (connectionLossReturn !== this) return
                connectionLossReturn = null
                if (!isActivityResumed || !hasWindowFocus() || isFinishing || isDestroyed || shuttingDown.get() ||
                    !CarPlayBackgroundSession.automaticEntryPending() ||
                    !CarPlayBackgroundSession.automaticConnectAllowed()) return
                if (CarPlayBackgroundSession.connectedProjectionReady()) {
                    appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} primary stream recovered during connection-loss grace; keeping CarPlay")
                    return
                }
                appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} no primary stream after connection-loss grace; opening connection page")
                showDiPlayHome("connection", disconnectedAtNs, preserveAutomaticEntry = true)
            }
        }
        connectionLossReturn = check
        mainHandler.postDelayed(check, CONNECTION_LOSS_GRACE_MILLIS)
    }

    private fun cancelConnectionLossReturn() {
        connectionLossReturn?.let(mainHandler::removeCallbacks)
        connectionLossReturn = null
    }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        val description = status.describe()
        setConnectionStage(description)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                reconnectAfterLoss(description, status.startupFailure)
            }
            else -> Unit
        }
    }

    private fun claimForegroundScreen() {
        if (!isActivityResumed || shuttingDown.get()) return
        val current = controller ?: return
        val session = current.currentActiveAirPlaySession() ?: return
        if (screenEntrySession === session) return
        // A withdrawn primary stream cannot be a prerequisite for asking the phone to restore it.
        // RECORD/SETUP may repeat for supplementary streams; select once per session and entry.
        if (CarPlayMediaKeys.onCarPlayEntered(current) && current.currentActiveAirPlaySession() === session) {
            screenEntrySession = session
            observedScreenOwner = session.resourceModes.state.screen
            screenSelectionPendingSession = session.takeIf { !it.resourceModes.state.screenAllowed }
        }
    }

    private fun primaryScreenRendered(): Boolean {
        val session = activeAirPlaySession ?: return false
        val surface = currentSurface ?: return false
        val view = videoView ?: return false
        return SCREEN_TYPE_MAIN in activeScreenStreamTypes && surface.isValid && view.isShown &&
            controller?.currentActiveAirPlaySession() === session && session.hasRenderedMainScreen() &&
            sink?.hasRenderedVideoFrame(SCREEN_TYPE_MAIN, surface) == true
    }

    private fun completeAutomaticEntryIfVisible() {
        updateDebugOverlays()
        if (isActivityResumed && hasWindowFocus() && !shuttingDown.get() &&
            !isFinishing && !isDestroyed && CarPlayBackgroundSession.isOwner(this)) {
            CarPlayBackgroundSession.confirmBackgroundEntryForeground(this)
            if (primaryScreenRendered()) {
                CarPlayBackgroundSession.takeAutomaticEntryIfReady("host-primary-visible")
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed() ||
            !CarPlayBackgroundSession.claimForeground(this, snapshot.controller, sessionLog)) return false
        vehicleIgnitionOff = CarPlayBackgroundSession.ignitionOff
        startupRetryStopped = CarPlayBackgroundSession.retryStopped()
        startupRetryButton?.visibility = if (startupRetryStopped) View.VISIBLE else View.GONE
        if (controller !== snapshot.controller) {
            if (controller != null) ++restartGeneration
            activeAirPlaySession = null
            activeScreenStreamTypes.clear()
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        clusterStreamViewport = snapshot.controller.configuredClusterSize()
        adbClusterConfigured = AdbClusterRouter.enabled(this) && snapshot.controller.configuredClusterSize() ==
            (DiLink4ClusterDisplay.STREAM_WIDTH to DiLink4ClusterDisplay.STREAM_HEIGHT)
        controller = snapshot.controller
        updateClusterMapShown()
        sink = snapshot.sink
        val adoptedDiagnosticLog = sessionLog
        snapshot.sink.setAudioDiagnosticListener { AsyncDiagnosticLog.append(adoptedDiagnosticLog, it) }
        HongqiWheelControls.attachDiagnosticLog(snapshot.controller, sessionLog)
        sessionDisplay = snapshot.display
        MapMirrors.reapply()
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        CarPlayBackgroundSession.attachForegroundUi(snapshot.controller,
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }


    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get()) return
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else size
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
        )
        val resume = CarPlayResumeConfiguration(config, airPlayConfig, airPlayIdentity, size.width, size.height,
            display, hevcSoftwareDecoderEnabled, advancedAudioChannelMapping, navigationStreamType,
            microphoneAvailable, audioCaptureDirectory(),
            ultraProvisioning = if (AirPlayPersistence.loadHs5UltraEnabled(this)) Hs5UltraProfile.factory(this) else null)
        if (wirelessEnabled && !hasRequiredWirelessPermissions()) {
            wirelessPermissionsReady = false
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
            return
        }
        CarPlayBackgroundSession.configureRecovery(this, resume)
        CarPlayBackgroundSession.setForegroundStartupReady(this, foregroundStartupReady())
        try {
            // The existing service also owns the lightweight ignition wait; the UI may disappear.
            startForegroundService(Intent(this, DiPlaySessionService::class.java))
            if (!adoptBackgroundSession()) {
                setConnectionStage(CarPlayBackgroundSession.startBlockReason()?.let(::startBlockStage)
                    ?: getString(R.string.connecting_to_your_iphone))
            }
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown("foreground service or controller could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_AUTOMATIC_APPEARANCE_ENABLED) return
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(MAX_FAILURE_CLASS_LENGTH)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        val marker = File(filesDir, AUDIO_CAPTURE_MARKER)
        if (!marker.isFile) return null
        val age = System.currentTimeMillis() - marker.lastModified()
        if (age !in 0..300_000L) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        CarPlayBackgroundSession.setForegroundStartupReady(this, false)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0) {
            val baseAspect = display.width.toDouble() / display.height
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > DISPLAY_ASPECT_CHANGE_THRESHOLD && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }

    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout =
        CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun foregroundStartupReady(): Boolean = activeDisplaySize != null && pendingDisplaySize == null &&
        (if (wirelessEnabled) wirelessPermissionsReady else vpnReady) &&
        (!locationReportingEnabled || locationPermissionAvailable) && microphonePermissionResolved &&
        !shuttingDown.get() && !isDestroyed

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || isDestroyed) return
        if (!isActivityResumed) {
            CarPlayBackgroundSession.releaseForegroundStartup(this)
            return
        }
        startupRetryStopped = CarPlayBackgroundSession.retryStopped()
        startupRetryButton?.visibility = if (startupRetryStopped) View.VISIBLE else View.GONE
        val current = CarPlayBackgroundSession.snapshot()
        if (controller != null && controller !== current?.controller) {
            ++restartGeneration
            releaseTouch()
            activeAirPlaySession = null
            activeScreenStreamTypes.clear()
            screenEntrySession = null
            controller = null
            sink = null
            sessionDisplay = null
            screenSelectionPendingSession = null
            observedScreenOwner = null
        }
        vehicleIgnitionOff = CarPlayBackgroundSession.ignitionOff
        handshakeResetInProgress = CarPlayBackgroundSession.startBlockReason() == "closing"
        if (current != null && (controller !== current.controller || !CarPlayBackgroundSession.isOwner(this))) {
            adoptBackgroundSession()
            return
        }
        if (!foregroundStartupReady()) {
            CarPlayBackgroundSession.setForegroundStartupReady(this, false)
            return
        }
        if (controller != null) {
            CarPlayBackgroundSession.setForegroundStartupReady(this, true)
            return
        }
        // Submit the latest viewport before releasing the foreground readiness gate.
        startCarPlay(activeDisplaySize ?: return)
    }

    private fun reconnectAfterLoss(
        reason: String,
        startupFailure: WirelessStartupFailure? = null,
        reuseController: Boolean = false,
    ) {
        val current = controller ?: return
        if (!CarPlayBackgroundSession.isOwner(this) || shuttingDown.get() || handshakeResetInProgress) return
        reconnectScheduled = CarPlayBackgroundSession.requestRecovery(current, reason, startupFailure, reuseController)
        startupRetryStopped = CarPlayBackgroundSession.retryStopped()
        startupRetryButton?.visibility = if (startupRetryStopped) View.VISIBLE else View.GONE
        if (reconnectScheduled) reconnectAttempts += 1
    }

    /** Submit configuration and let the process owner replace the transport after cleanup. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || !CarPlayBackgroundSession.isOwner(this)) return
        appendLog(reason)
        loadConnectionSettings()
        val previous = controller
        if (previous == null) { maybeStartCarPlay(); return }
        CarPlayBackgroundSession.setForegroundStartupReady(this, false)
        ++restartGeneration
        reconnectScheduled = false
        releaseTouch()
        activeAirPlaySession = null
        activeScreenStreamTypes.clear()
        screenEntrySession = null
        ClusterActivityOutput.setStreamActive(false)
        controller = null
        sink = null
        sessionDisplay = null
        screenSelectionPendingSession = null
        observedScreenOwner = null
        handshakeResetInProgress = true
        CarPlayBackgroundSession.requestConfigurationRestart(previous, reason)
        maybeStartCarPlay()
    }

    /** The process owner has stopped the round; clear only this Activity's projection state. */
    private fun disconnectForVehicleIgnitionOff() {
        if (vehicleIgnitionOff || shuttingDown.get()) return
        vehicleIgnitionOff = true
        ++reconnectRequestId
        reconnectScheduled = false
        reconnectAttempts = 0
        startupRetryButton?.visibility = View.GONE
        ++restartGeneration
        releaseTouch()
        activeAirPlaySession = null
        screenEntrySession = null
        activeScreenStreamTypes.clear()
        ClusterActivityOutput.setStreamActive(false)
        MapMirrors.setStreamActive(false)
        CenterMapOverlay.hide()
        setConnectionStage(getString(R.string.carplay_vehicle_ignition_off))
        appendLog("vehicle ignition off; stopping media and retiring transports")
        controller = null
        sink = null
        sessionDisplay = null
        screenSelectionPendingSession = null
        observedScreenOwner = null
        handshakeResetInProgress = false
    }

    /** Sends only the disconnected launcher branch to the software task. */
    private fun redirectLauncherToSoftware() {
        CarPlayBackgroundSession.recordUiDiagnostic("launcher Host entry routed to software: no retained CarPlay session")
        startActivity(Intent(this, DiPlaySettingsActivity::class.java)
            .putExtra("page", "home")
            .putExtra(LAUNCHER_SOFTWARE_FALLBACK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun showDiPlayHome(page: String = "home") = showDiPlayHome(page, 0L)

    private fun showDiPlayHome(page: String, disconnectedAtNs: Long, preserveAutomaticEntry: Boolean = false) {
        cancelConnectionLossReturn()
        if (page == "home" && CarPlayBackgroundSession.connectedSessionAvailable()) {
            // Back follows the phone's vehicle icon while connected, retaining this session
            // so the next app launch can restore CarPlay without showing software home.
            CarPlayBackgroundSession.cancelAutomaticEntry("host-back-to-vehicle")
            releaseTouch()
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (error: RuntimeException) {
                appendLog("Vehicle launcher could not open: ${error.javaClass.simpleName}")
                moveTaskToBack(true)
            }
            return
        }
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} host return page=$page preserveAutomaticEntry=$preserveAutomaticEntry keepSession=${controller != null}")
        if (!preserveAutomaticEntry) {
            CarPlayBackgroundSession.cancelAutomaticEntry("host-page-$page")
            controller?.selectProjectionScreen(false)
        }
        releaseTouch()
        startActivity(Intent(this, DiPlaySettingsActivity::class.java)
            .putExtra("page", page).putExtra("disconnected_at_ns", disconnectedAtNs)
            .putExtra(EXPLICIT_SOFTWARE_PAGE, page == "home")
            .putExtra("internal_connection_recovery", preserveAutomaticEntry)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        releaseTouch()
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(this, ::closePicturePanel)
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12)
                marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun shutdown(reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) {
            CarPlayBackgroundSession.stop(completion)
            return
        }
        CarPlayBackgroundSession.stopIgnitionWatch(this)
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        releaseTouch()
        releaseMainSurface()
        controller = null
        sink = null
        sessionDisplay = null
        Log.i(TAG, "shutdown UI reason=$reason")
        CarPlayBackgroundSession.stop {
            airPlayCommandExecutor.shutdown()
            teardownExecutor.shutdown()
            completion()
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    private val touchMapper = CarPlayTouchMapper()

    private fun releaseTouch() {
        controller?.sendTouch(emptyList())
        touchMapper.clear()
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (HongqiNavigationControls.onTouch(this, event)) return true

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            releaseTouch()
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore sequences that begin outside the visible viewport.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = touchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
                if (type == SCREEN_TYPE_MAIN) completeAutomaticEntryIfVisible()
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                if (active) ensureClusterPresentation()
                clusterPresentation?.setStreamActive(active)
                ClusterActivityOutput.setStreamActive(active)
                MapMirrors.setStreamActive(active)
                if (active) {
                    mainHandler.removeCallbacks(hideIdleCenterMap)
                    if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
                } else {
                    mainHandler.postDelayed(hideIdleCenterMap, CENTER_MAP_IDLE_MILLIS)
                }
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message)
        updateDebugOverlays()
    }

    private fun cancelPreparationReveal() {
        preparationRevealGeneration++
        preparationRevealPanel?.animate()?.cancel()
        preparationRevealPanel?.alpha = 1f
        preparationRevealPanel = null
        preparationRevealSurface = null
        preparationRevealSession = null
    }

    private fun updateDebugOverlays() {
        AutunnSettingsPage.stylePreparation(this)
        val panel = connectionPanel ?: return
        if (CarPlayBackgroundSession.connectedSessionAvailable()) {
            // All entry paths stay on CarPlay while connected, including video restoration
            // after HOME. Only a real connection-state change may expose the software cover.
            if (preparationRevealPanel != null) cancelPreparationReveal()
            panel.animate().cancel()
            panel.alpha = 1f
            if (panel.visibility != View.GONE)
                appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} preparation visible=false connected=true")
            panel.visibility = View.GONE
            if (primaryScreenRendered()) finishClusterEntryDeferral()
            return
        }
        val ready = primaryScreenRendered()
        val sameReveal = preparationRevealPanel === panel && preparationRevealSurface === currentSurface &&
            preparationRevealSession === activeAirPlaySession
        if (preparationRevealPanel != null && (!ready || !sameReveal || !isActivityResumed))
            cancelPreparationReveal()
        if (!ready) {
            if (panel.visibility != View.VISIBLE) appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} preparation visible=true mainStream=${SCREEN_TYPE_MAIN in activeScreenStreamTypes} resumed=$isActivityResumed")
            panel.alpha = 1f
            panel.visibility = View.VISIBLE
            return
        }
        if (preparationRevealPanel === panel) return
        if (panel.visibility == View.GONE) {
            finishClusterEntryDeferral()
            return
        }
        if (!panel.isAttachedToWindow) {
            panel.visibility = View.GONE
            panel.alpha = 1f
            finishClusterEntryDeferral()
            return
        }
        // Focus/resume callbacks recheck readiness; do not hard-cut a visible cover between them.
        if (!isActivityResumed || !hasWindowFocus()) return
        preparationRevealPanel = panel
        preparationRevealSurface = currentSurface
        preparationRevealSession = activeAirPlaySession
        val generation = ++preparationRevealGeneration
        appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} preparation reveal started after primary presentation")
        panel.animate().alpha(0f).setDuration(160).withEndAction {
            if (generation == preparationRevealGeneration && connectionPanel === panel && primaryScreenRendered()) {
                panel.visibility = View.GONE
                cancelPreparationReveal()
                appendLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} preparation visible=false primary presentation revealed")
                finishClusterEntryDeferral()
            } else if (generation == preparationRevealGeneration) {
                cancelPreparationReveal()
                updateDebugOverlays()
            }
        }.start()
    }

    private fun startBlockStage(reason: String?): String {
        if (reason == "preparation-failed") return CarPlayBackgroundSession.preparationError()
            ?: "CarPlay准备失败，请返回主页重试。"
        if (reason == "saving-control") return "正在保存连接状态"
        if (reason == "cleanup-blocked") return "上一连接尚未释放，已暂停重连。请等待恢复，或重新打开软件；原数据会保留。"
        return getString(when (reason) {
        "closing" -> R.string.carplay_waiting_for_previous_connection
        "manual-stop" -> R.string.carplay_manually_disconnected
        "ignition-off", "parked" -> R.string.carplay_vehicle_ignition_off
        "retry-stopped" -> R.string.wireless_startup_retries_exhausted
        else -> R.string.carplay_vehicle_waiting
        })
    }

    private fun friendlyStage(message: String): String = ConnectionStatusText.friendly(this, message)

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        AsyncDiagnosticLog.append(sessionLog, safe)
    }

    private fun appendFileLog(message: String) {
        AsyncDiagnosticLog.append(sessionLog, message)
    }

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        sessionLog = AsyncDiagnosticLog.open(logFile,
            "Autunn Play log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${Process.myPid()}")
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String =
        ConnectionStatusText.describe(this@CarPlayHostActivity, this, wirelessHotspotMode, wirelessEnabled)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NATIVE_RESOLUTION_PERCENT = 100
        const val DEFAULT_NAVIGATION_STREAM_TYPE = 14
        const val CH341_VENDOR_ID = CarPlaySessionFactory.CH341_VENDOR_ID
        const val CH341_PRODUCT_ID = CarPlaySessionFactory.CH341_PRODUCT_ID
        const val CARPLAY_USB_INTERFACE_NUMBER = CarPlaySessionFactory.CARPLAY_USB_INTERFACE_NUMBER
        const val PRIMARY_INPUT_TOUCH = 1
        const val PRIMARY_INPUT_KNOB = 3
        const val LOCAL_NETWORK_PERMISSION_SDK = 37
        const val VIDEO_DIMENSION_ALIGNMENT = 2
        const val MAX_ENLARGED_CANVAS_LONG_SIDE = 3840
        const val MAX_ENLARGED_CANVAS_SHORT_SIDE = 2160
        const val VIRTUAL_CLUSTER_WIDTH_PIXELS = 1280
        const val VIRTUAL_CLUSTER_HEIGHT_PIXELS = 720
        const val DISPLAY_ASPECT_CHANGE_THRESHOLD = 0.08
        const val MAX_FAILURE_CLASS_LENGTH = 64
        const val TURN_OVERLAY_REFRESH_INTERVAL_MILLIS = 1_000L
        const val CLUSTER_PRESENTATION_RETRY_MILLIS = 3_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val CONNECTION_LOSS_GRACE_MILLIS = 5_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
    }

    private data class DisplaySize(val width: Int, val height: Int)
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
)

