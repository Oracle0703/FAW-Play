package com.shilapi.xcertplay

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioQualityLimit
import com.shilapi.xcertplay.airplay.AudioQualityProfiles
import com.shilapi.xcertplay.airplay.AudioQualitySettings
import com.shilapi.xcertplay.media.AudioQualityCapabilities
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.components.Banner
import com.shilapi.xcertplay.ui.components.BannerKind
import com.shilapi.xcertplay.ui.components.FieldRow
import com.shilapi.xcertplay.ui.components.HelpText
import com.shilapi.xcertplay.ui.components.IconVectors
import com.shilapi.xcertplay.ui.components.KeyValueList
import com.shilapi.xcertplay.ui.components.LargeTopBar
import com.shilapi.xcertplay.ui.components.LinearMeter
import com.shilapi.xcertplay.ui.components.ListRow
import com.shilapi.xcertplay.ui.components.LvIcon
import com.shilapi.xcertplay.ui.components.LvIconButton
import com.shilapi.xcertplay.ui.components.PillKind
import com.shilapi.xcertplay.ui.components.ProvideGroupColor
import com.shilapi.xcertplay.ui.components.SectionHeader
import com.shilapi.xcertplay.ui.components.SegmentedRow
import com.shilapi.xcertplay.ui.components.SelectRow
import com.shilapi.xcertplay.ui.components.SettingsGroup
import com.shilapi.xcertplay.ui.components.SliderRow
import com.shilapi.xcertplay.ui.components.SmallTopBar
import com.shilapi.xcertplay.ui.components.StatusPill
import com.shilapi.xcertplay.ui.components.SwitchRow
import com.shilapi.xcertplay.ui.components.TrailingValue
import com.shilapi.xcertplay.ui.components.tabular
import com.shilapi.xcertplay.ui.home.HOME_SCREEN_ICONS
import com.shilapi.xcertplay.ui.home.HomeScreen
import com.shilapi.xcertplay.ui.home.StreamingLogOverlay
import com.shilapi.xcertplay.ui.i18n.I18n
import com.shilapi.xcertplay.ui.i18n.tr
import com.shilapi.xcertplay.ui.i18n.trOr
import com.shilapi.xcertplay.ui.theme.LavenderCode
import com.shilapi.xcertplay.ui.theme.LavenderDark
import com.shilapi.xcertplay.ui.theme.LavenderLight
import com.shilapi.xcertplay.ui.theme.Radius
import com.shilapi.xcertplay.ui.theme.Space
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import com.shilapi.xcertplay.vehicle.VehicleDataConfig
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import com.shilapi.xcertplay.vehicle.VehicleLocationProvider
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.MainMediaAudioBuffer
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.media.MicrophoneGain
import com.shilapi.xcertplay.media.MicrophoneLevelMonitor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Full-screen CarPlay host. It renders decoded video through a [SurfaceView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiHighPerformanceEnabled = wifiHighPerformanceEnabled,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
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
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private var vehicleSpeedPermissionPending = false
    private var vehicleSpeedPermissionAttempted = false
    private val vehicleSpeedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vehicleSpeedPermissionPending = false
            appendLog(if (granted) "Vehicle speed permission granted; save and reconnect to retry the source"
                else "Vehicle speed permission denied; wheel speed remains unavailable")
            updateVehicleDataStatus()
            if (!menuOpen) requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
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
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "certificate")) {
                localMfiCertificateUri = uri.toString()
                mfiError = null
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "private key")) {
                localMfiPrivateKeyUri = uri.toString()
                mfiError = null
            }
        }

    // Settings and UI state is snapshot state, so the Compose UI follows every change.
    private var mainWebDisplay by mutableStateOf(CarPlayWebDisplayConfig())
    private var clusterWebDisplay by mutableStateOf(CarPlayWebDisplayConfig(width = 1920))
    private val webDisplays get() = CarPlayWebDisplayFactory.find(sink)
    private var clusterPreview: TextureView? = null
    private var clusterPreviewSurface: Surface? = null
    private var clusterPreviewWindow: FrameLayout? = null
    private var clusterPreviewHandle: android.widget.ImageView? = null
    private var clusterPreviewMinimize: android.widget.ImageView? = null
    private var clusterWindowX = 0f
    private var clusterWindowY = 0f
    private var clusterWindowScale = 1f
    private var clusterPreviewContainer: FrameLayout? = null
    private var clusterPreviewCollapsed = false
    private val webDisplayDrafts = mutableStateMapOf<Int, WebDisplayDraft>()
    private var webDisplayAddresses by mutableStateOf<Map<Int, String>>(emptyMap())
    private var videoView: SurfaceView? = null
    private var videoArea: FrameLayout? = null
    private var contentRoot: FrameLayout? = null
    private var mainFrame: FrameLayout? = null
    private var gestureOverlay: View? = null
    private var mfiError by mutableStateOf<String?>(null)
    private var settingsBaseline: SettingsBaseline? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var micTestRunning by mutableStateOf(false)
    private var micPeakPercent by mutableIntStateOf(0)
    private var micLevelText by mutableStateOf("0%")
    private var connectionStatus by mutableStateOf("Preparing CarPlay")
    private var handshakeTimeline by mutableStateOf<HandshakeTimeline?>(null)
    private var timelineRevision by mutableIntStateOf(0)
    private var timelineDirty = false
    private var lastAttemptFailure: String? = null
    private var configSummary by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var manualHotspotError by mutableStateOf<String?>(null)
    private var manualHotspotChannelText by mutableStateOf("0")
    private var airPlayIcon by mutableStateOf<ImageBitmap?>(null)
    private var airPlayIconCustom by mutableStateOf(false)
    private var safeAreaSummaryText by mutableStateOf("")
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive by mutableStateOf(false)
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var activeDisplaySize: DisplaySize? = null
    private data class DisplayLayout(val splitScreen: Boolean, val portrait: Boolean)
    private fun currentDisplayLayout() = DisplayLayout(
        isInMultiWindowMode,
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
    )
    private var activeDisplayLayout: DisplayLayout? = null
    private var pendingDisplayLayout: DisplayLayout? = null
    private var pendingDisplaySize: DisplaySize? = null
    /** View areas declared by the current controller; null for a web display or no controller. */
    private var viewAreas: CarPlayViewAreas? = null
    private var displayScaleTenths by mutableIntStateOf(CarPlayDisplayScale.DEFAULT_TENTHS)
    private var hevcEnabled by mutableStateOf(true)
    private var hevcSoftwareDecoderEnabled by mutableStateOf(false)
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping by mutableStateOf(false)
    private var audioFocusEnabled by mutableStateOf(true)
    private var audioQuality by mutableStateOf(AudioQualitySettings())
    private var audioQualityOpen by mutableStateOf(false)
    private var audioQualityCapabilities by mutableStateOf<AudioQualityCapabilities.Result?>(null)
    private var audioQualityChecking by mutableStateOf(false)
    private var audioQualityProbeFailure by mutableStateOf<String?>(null)
    private var audioQualityProbeJob: Job? = null
    private var mainMediaAudioBufferDurationMs by mutableIntStateOf(MainMediaAudioBuffer.DEFAULT_DURATION_MS)
    // Snapshot state is safe to read from the media and log threads.
    private var debugLogsEnabled by mutableStateOf(false)
    private var videoFrameLogsEnabled by mutableStateOf(false)
    private var mediaMetricsEnabled by mutableStateOf(false)
    private var audioPacketCaptureEnabled by mutableStateOf(false)
    private var moreGesturesToSettings by mutableStateOf(false)
    private var autoStartOnBoot by mutableStateOf(false)
    private var manufacturer by mutableStateOf(AirPlayPersistence.DEFAULT_MANUFACTURER)
    private var model by mutableStateOf(AirPlayPersistence.DEFAULT_MODEL)
    private var oemLabel by mutableStateOf(AirPlayPersistence.DEFAULT_OEM_LABEL)
    private var fps by mutableIntStateOf(AirPlayDisplaySettings.DEFAULT_FPS)
    private var widthPhysicalMm by mutableIntStateOf(AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM)
    private var physicalSizeBasis by mutableStateOf(AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS)
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive by mutableStateOf(false)
    private var hideTopBar by mutableStateOf(true)
    private var hideBottomBar by mutableStateOf(true)
    private var safeAreaDrawOutside by mutableStateOf(true)
    private var currentVehicleProvider: com.shilapi.xcertplay.vehicle.source.VehicleDataSource? = null
    private var diagnosticVehicleProvider: com.shilapi.xcertplay.vehicle.source.VehicleDataSource? = null
    private var vehicleProfileJson by mutableStateOf("")
    /** Where [vehicleProfileJson] came from; remembered on Save & Reconnect like other settings. */
    private var vehicleProfileSource by mutableStateOf("")
    private var vehicleProfileMessage by mutableStateOf("")
    private val bundledProfileNames by lazy { VehicleProfileRepository(this).bundledProfiles() }
    private val vehicleDataEntries by lazy { InstalledVehicleDataEntries.all { selectedVehicleProfile() } }
    private val vehicleProfileImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VehicleProfileRepository(this@CarPlayHostActivity).import(uri) } }
                .onSuccess { stopVehicleDiagnostics(); vehicleProfileJson=it; vehicleProfileSource=VehicleProfileRepository.IMPORTED; vehicleProfileMessage="Validated draft. Save & Reconnect applies it." }
                .onFailure { vehicleProfileMessage="Import rejected; previous profile retained: ${it.message}" }
        }
    }
    /**
     * Many head units ship without a document picker, so launching OPEN_DOCUMENT throws
     * ActivityNotFoundException on the main thread. Probe first and never kill the Activity.
     */
    private fun launchDocumentPicker(
        launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        mimeTypes: Array<String>,
        onUnavailable: (String) -> Unit = {},
    ) {
        val probe = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeTypes.firstOrNull() ?: "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        if (probe.resolveActivity(packageManager) == null) { onUnavailable("no document picker on this device"); return }
        runCatching { launcher.launch(mimeTypes) }.onFailure { onUnavailable("${it.javaClass.simpleName}: ${it.message}") }
    }
    /**
     * Fallback import for picker-less head units: the profile is read from the app's external
     * files directory, which adb can push into without any storage permission.
     */
    private fun importVehicleProfileFromDisk(reason: String) {
        val file = VehicleProfileRepository(this).externalImportFile()
        lifecycleScope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VehicleProfileRepository(this@CarPlayHostActivity).importFile(file) } }
                .onSuccess {
                    stopVehicleDiagnostics(); vehicleProfileJson=it; vehicleProfileSource=VehicleProfileRepository.IMPORTED
                    vehicleProfileMessage="Loaded ${file.name}. Save & Reconnect applies it. (picker unavailable: $reason)"
                }
                .onFailure {
                    vehicleProfileMessage="Picker unavailable ($reason). Push a profile to ${file.absolutePath}, then tap Import JSON again. ${it.message}"
                }
        }
    }
    private fun loadVehicleProfile() {
        val selection = VehicleProfileRepository(this).load()
        vehicleProfileJson = selection.text
        vehicleProfileSource = selection.source
        vehicleProfileMessage = selection.message.orEmpty()
    }
    private fun selectedVehicleProfile() = com.shilapi.xcertplay.vehicle.profile.VehicleProfileParser.parse(vehicleProfileJson)
    private fun selectedVehicleDataEntry() =
        com.shilapi.xcertplay.vehicle.source.VehicleDataEntries.select(vehicleDataEntries, vehicleDataConfig.sourceId)
    private fun createVehicleDataSource(diagnostic: Boolean=false): com.shilapi.xcertplay.vehicle.source.VehicleDataSource =
        // The source is handed to the long-lived controller; never let it capture the Activity.
        selectedVehicleDataEntry().create(com.shilapi.xcertplay.vehicle.source.VehicleDataSourceRequest(
            applicationContext, vehicleDataConfig, diagnostic))
    private fun stopVehicleDiagnostics() { diagnosticVehicleProvider?.close(); diagnosticVehicleProvider=null }
    private fun startVehicleDiagnostics() {
        stopVehicleDiagnostics()
        val provider=createVehicleDataSource(true);diagnosticVehicleProvider=provider;provider.start()
        lifecycleScope.launch {
            repeat(600) {
                kotlinx.coroutines.delay(1000)
                if(diagnosticVehicleProvider !== provider) return@launch
                updateVehicleDataStatus()
            }
            if(diagnosticVehicleProvider===provider) { stopVehicleDiagnostics(); vehicleProfileMessage="Diagnostic sampling finished (10 minutes). Stored values are no longer live." }
        }
    }
    private var currentGpsProvider: VehicleLocationProvider? = null
    private var currentWheelProvider: com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider? = null
    private var vehicleDataStatusText by mutableStateOf("")
    private var lastVehicleDataDiagnostics: String? = null
    private var vehicleDataConfig by mutableStateOf(VehicleDataConfig())
    private var locationReportingEnabled by mutableStateOf(false)
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var microphoneGainPercent by mutableIntStateOf(MicrophoneGain.DEFAULT_PERCENT)
    private var wirelessEnabled by mutableStateOf(false)
    private var wifiHighPerformanceEnabled by mutableStateOf(true)
    private var mfiTarget by mutableStateOf(MfiTarget.USB_CH341)
    private var mfiI2cPath by mutableStateOf(AirPlayPersistence.DEFAULT_MFI_I2C_PATH)
    private var remoteMfiServer by mutableStateOf("")
    private var remoteMfiToken by mutableStateOf("")
    private var localMfiCertificateUri by mutableStateOf("")
    private var localMfiPrivateKeyUri by mutableStateOf("")
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode by mutableStateOf(WirelessHotspotMode.WIFI_P2P)
    private var manualHotspotSsid by mutableStateOf("")
    private var manualHotspotPassphrase by mutableStateOf("")
    private var manualHotspotBand by mutableStateOf(ManualHotspotBand.AUTO)
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity by mutableStateOf(ManualHotspotSecurity.OPEN)
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus by mutableStateOf(HotspotStatus(state = "off"))
    private var menuOpen by mutableStateOf(false)
    private var settingsCategory by mutableStateOf<SettingsCategory?>(null)
    private var settingsTwoPane = false
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()
    private var mainStreamActive by mutableStateOf(false)
    private var handshakeResetInProgress by mutableStateOf(false)
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    /** Automatic retries ran out; the home screen offers a manual reconnect instead. */
    private var reconnectExhausted by mutableStateOf(false)
    private var screenLogs by mutableStateOf<List<ScreenLogLine>>(emptyList())
    private val sessionLog get() = CarPlayBackgroundSession.log
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Only exitApplication shuts these down, and Activities are recreated often (language,
    // back and reopen), so their single worker retires when idle instead of leaking per instance.
    private val teardownExecutor: ExecutorService = idleRetiringSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = idleRetiringSingleThreadExecutor()

    private fun idleRetiringSingleThreadExecutor(): ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, IDLE_WORKER_KEEP_ALIVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
        ).apply { allowCoreThreadTimeOut(true) }
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    // Log receipt and log display are decoupled: any thread only queues records, and the UI
    // takes them in one batch per LOG_FRAME_MILLIS, however fast the session logs.
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingHandshakeLogs = ArrayDeque<PendingRecord>()
    private val pendingScreenLogsLock = Any()
    private var logFrameScheduled = false
    @Volatile private var lastLogFrameAt = 0L
    private var lastTimelinePublishAt = 0L
    private var inLogFrame = false
    private var screenLogsDirty = false
    private val logFrame = Runnable {
        val (handshake, screen) = synchronized(pendingScreenLogsLock) {
            logFrameScheduled = false
            (pendingHandshakeLogs.toList() to pendingScreenLogs.toList()).also {
                pendingHandshakeLogs.clear()
                pendingScreenLogs.clear()
            }
        }
        val now = SystemClock.uptimeMillis()
        lastLogFrameAt = now
        inLogFrame = true
        try {
            handshake.forEach { recordHandshake(it.generation, it.record, it.timestampMillis) }
            if (debugLogsEnabled && !menuOpen) {
                screen.forEach { entry ->
                    if (entry.generation == restartGeneration) {
                        appendScreenLog(entry.timestampMillis, entry.message)
                    }
                }
            }
        } finally {
            inLogFrame = false
        }
        if (timelineDirty) {
            timelineDirty = false
            timelineRevision++
            lastTimelinePublishAt = now
        }
        if (screenLogsDirty) {
            screenLogsDirty = false
            refreshLogView(System.currentTimeMillis())
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var mainStreamReadyAt = 0L
    private val revealMainStream = Runnable { maybeRevealMainStream() }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    // The holder owns the surface; it is only handed to the decoder, never released here.
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface
            currentSurface = surface
            val refreshHz = window.decorView.display?.refreshRate
            appendLog("Video surface created refreshRate=${refreshHz ?: "unknown"}Hz")
            attachSurface(surface)
            updateWebDisplayPreviews()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            currentSurface?.let { detachSurface(SCREEN_TYPE_MAIN, it) }
            currentSurface = null
            appendLog("Video surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconVectors.preload(applicationContext.resources, HOME_SCREEN_ICONS)
        initializeSessionLog()
        I18n.init(this)
        darkMode = isDarkMode(resources.configuration.uiMode)
        prepareEdgeToEdgeWindow()
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        when {
                            safeAreaEditorActive -> closeSafeAreaEditor()
                            audioQualityOpen -> audioQualityOpen = false
                            settingsCategory != null && !settingsTwoPane -> settingsCategory = null
                            else -> cancelSettingsEdits()
                        }
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        mainWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_MAIN)
        clusterWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_ALT)
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        audioQuality = AirPlayPersistence.loadAudioQuality(this)
        mainMediaAudioBufferDurationMs =
            AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        videoFrameLogsEnabled = AirPlayPersistence.loadVideoFrameLogsEnabled(this)
        sessionLog.videoFramesEnabled = videoFrameLogsEnabled
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
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
        loadVehicleProfile()
        vehicleDataConfig = AirPlayPersistence.loadVehicleDataConfig(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        wifiHighPerformanceEnabled = AirPlayPersistence.loadWifiHighPerformanceEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        manualHotspotChannelText = manualHotspotChannel.toString()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED) && !vehicleSpeedPermissionAttempted &&
            canRequestVehicleSpeedPermission() && checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
            requestVehicleSpeedPermission()
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
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
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

    override fun onResume() {
        super.onResume()
        CarPlayBackgroundSession.audioFocus(this).apply {
            retain(this@CarPlayHostActivity)
            setEnabled(audioFocusEnabled)
            requestFromHost()
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onStop() {
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only reached for language changes if `locale` is ever added to configChanges;
        // otherwise the system recreates the activity and onCreate reloads the strings.
        I18n.refresh(this)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
            styleClusterPreviewControls()
        }
        applyFullscreenMode()
        mainFrame?.post {
            val frame = mainFrame ?: return@post
            scheduleDisplaySize(frame.width, frame.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshDisplaySizeAfterLayout()
    }

    override fun onDestroy() {
        stopVehicleDiagnostics()
        CarPlayBackgroundSession.audioFocus(this).release(this)
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        clusterPreviewSurface?.let { detachSurface(SCREEN_TYPE_ALT, it); it.release() }
        clusterPreviewSurface = null
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(logFrame)
        mainHandler.removeCallbacks(revealMainStream)
        currentSurface?.let { detachSurface(SCREEN_TYPE_MAIN, it) }
        currentSurface = null
        sessionLog.append("Activity destroyed; background session and log remain active")
        super.onDestroy()
    }

    /**
     * View layers keep the video plumbing (video surfaces, touch forwarding, overlays drawn over
     * video); everything else is one Compose layer on top. The window is edge-to-edge: the video
     * is padded by the system bars and display cutout, Compose content by `safeDrawing`.
     */
    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        contentRoot = root
        val video = SurfaceView(this).apply {
            holder.addCallback(surfaceCallback)
        }
        // Spans the active area; the video inside spans the whole stream and is clipped to it.
        val area = FrameLayout(this).apply {
            addView(video, fullFrameParams())
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val frame = FrameLayout(this).apply { addView(area, fullFrameParams()) }
        frame.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
                updateWebDisplayPreviews()
                scheduleDisplaySize(right - left, bottom - top)
            }
        }
        mainFrame = frame
        val cluster = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(clusterWebDisplay.width, clusterWebDisplay.height)
                    clusterPreviewSurface = Surface(texture).also { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    clusterPreviewSurface?.let { detachSurface(SCREEN_TYPE_ALT, it); it.release() }
                    clusterPreviewSurface = null
                    return true
                }
            }
        }
        clusterPreview = cluster
        val displays = FrameLayout(this).apply {
            addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        buildClusterPreviewWindow(displays, cluster)
        padForSystemBars(displays)
        root.addView(displays, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Areas without a composable underneath pass touches through to the layers below.
        val compose = ComposeView(this).apply {
            setContent {
                XcertplayTheme {
                    HostOverlay()
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateWebDisplayPreviews()
            }
        }
        videoView = video
        videoArea = area
        gestureOverlay = gestureLayer
        safeAreaEditor = editor
        handshakeTimeline = HandshakeTimeline(wirelessEnabled, System.currentTimeMillis())
        timelineRevision++
        updateWebDisplayPreviews()
        updateDebugOverlays()
        return root
    }

    /**
     * The main display always fills the screen; the instrument preview floats over it as a
     * draggable picture-in-picture window that can be tucked into a handle on the left edge.
     */
    private fun buildClusterPreviewWindow(container: FrameLayout, cluster: TextureView) {
        val minimize = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_ms_arrow_back)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = I18n.text("settings.web.instrument")
            setOnClickListener { setClusterPreviewCollapsed(true) }
        }
        val window = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            elevation = dp(6).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            setBackgroundColor(Color.BLACK)
            addView(cluster, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(minimize, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(8), dp(8), 0, 0)
            })
            setOnTouchListener(clusterWindowDragListener())
        }
        val handle = android.widget.ImageView(this).apply {
            visibility = View.GONE
            setImageResource(R.drawable.ic_ms_chevron_right)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            elevation = dp(3).toFloat()
            contentDescription = I18n.text("settings.web.instrument")
            setOnClickListener { setClusterPreviewCollapsed(false) }
        }
        container.addView(window, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        container.addView(handle, FrameLayout.LayoutParams(dp(24), dp(64), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16)
        })
        clusterPreviewWindow = window
        clusterPreviewMinimize = minimize
        clusterPreviewHandle = handle
        clusterPreviewContainer = container
        clusterWindowX = dp(16).toFloat()
        clusterWindowY = dp(16).toFloat()
        styleClusterPreviewControls()
        var area = 0 to 0
        container.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val next = (right - left - view.paddingLeft - view.paddingRight) to (bottom - top - view.paddingTop - view.paddingBottom)
            if (next != area) {
                area = next
                view.post { updateWebDisplayPreviews() }
            }
        }
    }

    /** Lavender roles for the preview controls; reapplied when the system switches light/dark. */
    private fun styleClusterPreviewControls() {
        val scheme = if (darkMode) LavenderDark else LavenderLight
        val container = scheme.surfaceContainerHigh.toArgb()
        val content = android.content.res.ColorStateList.valueOf(scheme.onSurface.toArgb())
        clusterPreviewMinimize?.apply {
            imageTintList = content
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(container)
            }
        }
        clusterPreviewHandle?.apply {
            imageTintList = content
            background = android.graphics.drawable.GradientDrawable().apply {
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(container)
                setStroke(dp(1), scheme.outlineVariant.toArgb())
            }
        }
    }

    /**
     * One finger drags the preview window past the touch slop; two fingers pinch it around its
     * centre. A gesture that pinched does not drag afterwards, so lifting one finger cannot jump.
     */
    private fun clusterWindowDragListener() = object : View.OnTouchListener {
        private val slop = android.view.ViewConfiguration.get(this@CarPlayHostActivity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var pinched = false
        private val pinch = android.view.ScaleGestureDetector(
            this@CarPlayHostActivity,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val params = clusterPreviewWindow?.layoutParams ?: return false
                    val centerX = clusterWindowX + params.width / 2f
                    val centerY = clusterWindowY + params.height / 2f
                    clusterWindowScale *= detector.scaleFactor
                    layoutClusterWindow()
                    moveClusterWindow(centerX - params.width / 2f, centerY - params.height / 2f)
                    return true
                }
            },
        )

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = clusterWindowX; startY = clusterWindowY
                    dragging = false
                    pinched = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> { pinched = true; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (pinched) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) moveClusterWindow(startX + dx, startY + dy)
                }
            }
            return true
        }
    }

    /**
     * Sizes the preview window: a default fit inside the safe area times the pinch
     * [clusterWindowScale], from [CLUSTER_WINDOW_MIN_WIDTH_DP] wide up to filling the safe area.
     */
    private fun layoutClusterWindow() {
        val window = clusterPreviewWindow ?: return
        val container = clusterPreviewContainer ?: return
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        val availableHeight = container.height - container.paddingTop - container.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return
        val fit = minOf(availableWidth.toDouble() / clusterWebDisplay.width, availableHeight.toDouble() / clusterWebDisplay.height)
        val default = minOf(
            availableWidth * CLUSTER_WINDOW_MAX_WIDTH_FRACTION / clusterWebDisplay.width,
            availableHeight * CLUSTER_WINDOW_MAX_HEIGHT_FRACTION / clusterWebDisplay.height,
        )
        val min = minOf(dp(CLUSTER_WINDOW_MIN_WIDTH_DP).toDouble() / clusterWebDisplay.width, default)
        clusterWindowScale = clusterWindowScale.coerceIn((min / default).toFloat(), (fit / default).toFloat())
        val scale = default * clusterWindowScale
        val params = window.layoutParams
        val width = (clusterWebDisplay.width * scale).toInt().coerceAtLeast(1)
        val height = (clusterWebDisplay.height * scale).toInt().coerceAtLeast(1)
        if (params.width != width || params.height != height) { params.width = width; params.height = height; window.layoutParams = params }
        moveClusterWindow(clusterWindowX, clusterWindowY)
    }

    /** Places the preview window at ([x], [y]) inside the safe area, clamped to stay fully visible. */
    private fun moveClusterWindow(x: Float, y: Float) {
        val window = clusterPreviewWindow ?: return
        val container = clusterPreviewContainer ?: return
        val maxX = container.width - container.paddingLeft - container.paddingRight - window.layoutParams.width
        val maxY = container.height - container.paddingTop - container.paddingBottom - window.layoutParams.height
        clusterWindowX = x.coerceIn(0f, maxOf(0, maxX).toFloat())
        clusterWindowY = y.coerceIn(0f, maxOf(0, maxY).toFloat())
        window.translationX = clusterWindowX
        window.translationY = clusterWindowY
    }

    /** Keeps [view]'s content (the CarPlay video) clear of visible system bars and the cutout. */
    private fun padForSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Transparent system bars over our own surfaces; layout runs into the cutout area. */
    private fun prepareEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_MAIN, mainWebDisplay)
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_ALT, clusterWebDisplay)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveWifiHighPerformanceEnabled(this, wifiHighPerformanceEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveVehicleDataConfig(this, vehicleDataConfig)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveAudioFocusEnabled(this, audioFocusEnabled)
        AirPlayPersistence.saveAudioQuality(this, audioQuality)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(
            this,
            mainMediaAudioBufferDurationMs,
        )
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveVideoFrameLogsEnabled(this, videoFrameLogsEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        resetWebDisplayDrafts()
        updateWebDisplayPreviews()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        syncMfiSettingsControls()
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun webDisplayConfig(type: Int) = if (type == SCREEN_TYPE_MAIN) mainWebDisplay else clusterWebDisplay

    private fun setWebDisplayConfig(type: Int, config: CarPlayWebDisplayConfig) {
        if (type == SCREEN_TYPE_MAIN) mainWebDisplay = config else clusterWebDisplay = config
        updateWebDisplayPreviews()
        updateResolutionMenu()
    }

    private fun updateWebDisplayAddresses() {
        webDisplayAddresses = listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT).associateWith { type ->
            CarPlayWebDisplayFactory.urls(type).joinToString("\n")
        }
    }

    private fun validateWebDisplaySettings(): Boolean {
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            if (!config.enabled) continue
            val draft = webDisplayDrafts[type] ?: continue
            val updated = runCatching {
                config.copy(width = draft.width.trim().toInt(), height = draft.height.trim().toInt())
            }.getOrElse {
                webDisplayDrafts[type] = draft.copy(error = true)
                settingsCategory = SettingsCategory.WEB
                return false
            }
            setWebDisplayConfig(type, updated)
        }
        return true
    }

    private fun updateMfiTargetFields() {
        mfiError = null
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return "Not selected"
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return "Selection unavailable"
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: "Selected document"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected document"
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi $label document permission", failure)
        Toast.makeText(
            this,
            "Could not keep access to the selected $label",
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun syncMfiSettingsControls() {
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
        MfiTarget.LOCAL_FILES -> "Local files"
    }

    private fun canRequestVehicleSpeedPermission(): Boolean = runCatching {
        val info = packageManager.getPermissionInfo("android.car.permission.CAR_SPEED", 0)
        val isCar = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            runCatching { Class.forName("android.car.Car") }.isSuccess
        isCar && (info.protection) ==
            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private fun requestVehicleSpeedPermission() {
        if (vehicleSpeedPermissionPending || !vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED) ||
            !canRequestVehicleSpeedPermission() || checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) return
        vehicleSpeedPermissionAttempted = true
        vehicleSpeedPermissionPending = true
        vehicleSpeedPermission.launch("android.car.permission.CAR_SPEED")
    }

    private fun updateVehicleDataStatus() {
        val provider=diagnosticVehicleProvider ?: currentVehicleProvider
        if(provider==null) { vehicleDataStatusText="Settings pauses CarPlay. Start a separate 10-minute read-only diagnostic session. No data is sent to the phone during this session.\n" + (lastVehicleDataDiagnostics ?: "No previous connection samples.");return }
        vehicleDataStatusText=buildString {
            append(if(diagnosticVehicleProvider!=null) "Independent diagnostic session (no phone output)" else "Connection snapshot")
            append("\nSource: ").append(selectedVehicleDataEntry().displayName)
            append("\n").append(provider.diagnostics())
        }
    }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        String.format(
            Locale.US,
            "%d ms",
            MainMediaAudioBuffer.sanitizeDurationMs(durationMs),
        )

    private fun microphoneGainLabel(percent: Int): String {
        val gain = MicrophoneGain.sanitize(percent)
        // Sub-unity steps are only ~0.06x apart, so one decimal would show duplicates.
        return String.format(Locale.US, if (gain < 100) "%.2fx" else "%.1fx", gain / 100.0)
    }

    private fun syncMainMediaAudioBufferControls() {
        mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainPercent = MicrophoneGain.sanitize(microphoneGainPercent)
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            micLevelText = tr("settings.audio.permission_required")
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    micPeakPercent = peak
                    micLevelText = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    micTestRunning = false
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        micPeakPercent = 0
                        micLevelText = error.message ?: tr("settings.audio.test_failed")
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        micTestRunning = true
        micPeakPercent = 0
        micLevelText = tr("settings.audio.listening")
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            micTestRunning = false
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        micTestRunning = false
        micPeakPercent = 0
        micLevelText = "0%"
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: ${error.message}")
            Toast.makeText(this, "System Bluetooth settings are unavailable", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: ${error.message}")
            Toast.makeText(this, "Cannot open system Bluetooth settings", Toast.LENGTH_LONG).show()
        }
    }

    /** Black backdrop and drag surface; its title and buttons are drawn by [SafeAreaEditorChrome]. */
    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        padForSystemBars(overlay)
        safeAreaEditorView = editor
        return overlay
    }

    private fun updateManualHotspotFields() {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) manualHotspotError = null
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                "I2C device path is required"
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                "Remote server address is required"
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                "Remote server address must start with http:// or https://"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                "Select a local certificate"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                "Select a local private key"
            '\u0000' in mfiI2cPath -> "I2C device path contains U+0000"
            '\u0000' in remoteMfiServer -> "Remote server address contains U+0000"
            '\u0000' in remoteMfiToken -> "Remote token contains U+0000"
            '\u0000' in localMfiCertificateUri -> "Local certificate URI contains U+0000"
            '\u0000' in localMfiPrivateKeyUri -> "Local private key URI contains U+0000"
            else -> null
        }
        mfiError = error
        if (error != null) settingsCategory = SettingsCategory.CONNECTION
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> "Hotspot SSID is required"
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                "Hotspot SSID must be at most 32 UTF-8 bytes"
            '\u0000' in manualHotspotSsid -> "Hotspot SSID contains U+0000"
            manualHotspotChannel !in 0..196 -> "Channel must be 0 or 1-196"
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                "Channel is not valid for the selected band"
            '\u0000' in manualHotspotPassphrase -> "Hotspot password contains U+0000"
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                "Password must be empty when security is Open"
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                "WPA2/WPA3 password must be 8-63 characters"
            else -> null
        }
        manualHotspotError = error
        if (error != null) settingsCategory = SettingsCategory.CONNECTION
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> "Manual hotspot"
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    /** The hotspot card reads [hotspotStatus] directly; kept as the single update hook. */
    private fun updateHotspotStatusBlock() = Unit

    private fun updateResolutionMenu() {
        val native = activeDisplaySize ?: currentActivitySize()
        val handshake = if (native == null) {
            tr("summary.waiting_display")
        } else {
            val negotiated = mainWebDisplay.override(CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            ))
            "${native.width} × ${native.height} → ${negotiated.widthPixels} × ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            tr(if (hevcSoftwareDecoderEnabled) "summary.video.hevc_software" else "summary.video.hevc_hardware")
        }
        configSummary = buildList {
            add(tr("summary.handshake") to handshake)
            add(tr("summary.scale") to CarPlayDisplayScale.label(displayScaleTenths))
            add(tr("summary.identity") to "${normalizedManufacturer()} / ${normalizedModel()}")
            add(tr("summary.oem_label") to oemLabel.ifBlank { tr("summary.empty") })
            add(tr("summary.frame_rate") to tr("unit.fps", "value" to fps))
            add(tr("summary.detected_max") to "$maximumDetectedWidthPixels × $maximumDetectedHeightPixels px")
            add(
                tr("summary.physical_reference") to tr(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> "summary.physical_reference.width"
                        AirPlayPhysicalSizeBasis.HEIGHT -> "summary.physical_reference.height"
                    },
                    "value" to widthPhysicalMm,
                ),
            )
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                add(tr("summary.physical_size") to "${physical.widthMm} × ${physical.heightMm} mm")
            }
            add(
                tr("summary.driving_side") to
                    if (rightHandDrive) tr("settings.identity.driving_side.right") else tr("settings.identity.driving_side.left"),
            )
            add(
                tr("summary.system_bars") to tr(
                    "summary.system_bars.value",
                    "top" to tr(if (hideTopBar) "summary.hidden" else "summary.shown"),
                    "bottom" to tr(if (hideBottomBar) "summary.hidden" else "summary.shown"),
                ),
            )
            add(tr("summary.video") to transport)
            add(tr("summary.location") to tr(if (locationReportingEnabled) "summary.enabled" else "summary.disabled"))
            if (advancedAudioChannelMappingSupported) {
                add(
                    tr("summary.audio_mapping") to
                        tr(if (advancedAudioChannelMapping) "summary.audio_mapping.aaos" else "summary.audio_mapping.mobile"),
                )
            }
            add(tr("summary.safe_area") to safeAreaSummary())
        }
    }

    private fun createViewAreas(size: DisplaySize): CarPlayViewAreas? {
        if (mainWebDisplay.enabled) return null
        return CarPlayViewAreas.build(
            currentWidth = size.width,
            currentHeight = size.height,
            savedSizes = AirPlayPersistence.loadSafeAreaSizes(this),
            scaleTenths = displayScaleTenths,
        ) { width, height -> AirPlayPersistence.loadSafeAreaRect(this, width, height) }
    }

    private fun createAirPlayConfig(size: DisplaySize, areas: CarPlayViewAreas?): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val display = if (areas != null) {
            // The display is the envelope of all areas; its physical size keeps the current
            // activity's pixel density.
            AirPlayDisplayConfig(
                widthPixels = areas.widthPixels,
                heightPixels = areas.heightPixels,
                widthPhysicalMm = Math.round(physical.widthMm * areas.activityWidthPixels.toDouble() / size.width).toInt(),
                heightPhysicalMm = Math.round(physical.heightMm * areas.activityHeightPixels.toDouble() / size.height).toInt(),
                fps = fps,
                safeAreaDrawOutside = safeAreaDrawOutside,
                viewAreas = areas.areas,
                initialViewArea = checkNotNull(areas.indexFor(size.width, size.height)),
            )
        } else {
            val baseDisplay = AirPlayDisplayConfig(
                widthPixels = size.width,
                heightPixels = size.height,
                widthPhysicalMm = physical.widthMm,
                heightPhysicalMm = physical.heightMm,
                fps = fps,
            )
            val scaledDisplay = CarPlayDisplayScale.apply(
                baseDisplay,
                displayScaleTenths,
            )
            mainWebDisplay.override(scaledDisplay.copy(
                safeArea = AirPlaySafeArea.toInsets(
                    mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                    activityWidthPixels = size.width,
                    activityHeightPixels = size.height,
                    displayWidthPixels = scaledDisplay.widthPixels,
                    displayHeightPixels = scaledDisplay.heightPixels,
                ),
                safeAreaDrawOutside = safeAreaDrawOutside,
            ))
        }
        val cluster = clusterWebDisplay.takeIf { it.enabled }?.let {
            it.override(AirPlayDisplayConfig(it.width, it.height, widthPhysicalMm = widthPhysicalMm, fps = fps,
                primaryInputDevice = 0))
        }
        return AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            cluster = cluster,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            // 用进程级缓存而不是设置页状态：是否按实测收窄，不能取决于这次有没有点进音频质量页。
            // 本进程还没探测过时为 null，保持协议默认声明。
            audioQuality = effectiveAudioQuality(buildQualityOptions(AudioQualityCapabilities.cached())),
            audioSupportedFormats = AudioQualityCapabilities.cached()?.allowedFormatBits,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        airPlayIcon = bitmap?.asImageBitmap()
        airPlayIconCustom = customBitmap != null
    }

    private fun currentActivitySize(): DisplaySize? {
        val frame = mainFrame
        if (frame != null && frame.width > 0 && frame.height > 0) {
            return DisplaySize(frame.width, frame.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return tr("safe_area.waiting")
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            tr("safe_area.full", "width" to size.width, "height" to size.height)
        } else {
            tr(
                "safe_area.custom",
                "rect_width" to mapping.width,
                "rect_height" to mapping.height,
                "left" to mapping.left,
                "top" to mapping.top,
                "width" to size.width,
                "height" to size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryText = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            Toast.makeText(this, tr("safe_area.unavailable"), Toast.LENGTH_SHORT).show()
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        applySystemBarAppearance()
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        applySystemBarAppearance()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            Toast.makeText(this, tr("safe_area.unavailable"), Toast.LENGTH_SHORT).show()
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        mainFrame?.post {
            val frame = mainFrame ?: return@post
            scheduleDisplaySize(frame.width, frame.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        airPlayConfig: AirPlayConfig,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        val outputs = CarPlayWebDisplayFactory.create(
            context = applicationContext,
            configs = mapOf(SCREEN_TYPE_MAIN to mainWebDisplay, SCREEN_TYPE_ALT to clusterWebDisplay),
            sendTouch = { CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(it) ?: false },
        )
        return try {
            AndroidMediaSink(
                context = applicationContext,
                videoWidth = airPlayConfig.main.widthPixels,
                videoHeight = airPlayConfig.main.heightPixels,
                videoSizes = buildMap {
                    put(SCREEN_TYPE_MAIN, airPlayConfig.main.widthPixels to airPlayConfig.main.heightPixels)
                    airPlayConfig.cluster?.let { put(SCREEN_TYPE_ALT, it.widthPixels to it.heightPixels) }
                },
                preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
                advancedAudioChannelMapping = advancedAudioChannelMapping,
                mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
                microphoneGainPercent = microphoneGainPercent,
                mediaMetricsMonitor = mediaMetricsMonitor,
                onClosed = { outputs?.close() },
                mediaTap = outputs,
                audioFocus = CarPlayBackgroundSession.audioFocus(this),
                onScreenStreamActiveChanged = { type, active ->
                    onScreenStreamStateChanged(controllerGeneration, type, active)
                },
            ).also { outputs?.bind(it) }
        } catch (error: Exception) { outputs?.close(); throw error }
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
            videoPacketLog = CarPlayBackgroundSession.log::appendVideoPacket,
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    reconnectAttempts = 0
                    reconnectExhausted = false
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    failHandshake("CarPlay session ended", "iPhone closed the AirPlay session")
                    setConnectionStage("CarPlay session ended; reconnecting")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    failHandshake("Transport error", message)
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                // Decoded here, on the session thread, so the log frame only updates the model.
                val handshake = if (HandshakeTimeline.isRelevant(message)) HandshakeTimeline.prepare(message) else null
                val summary = if (debugLogsEnabled) ScreenLogPolicy.summary(message) else null
                if (handshake == null && summary == null) return
                synchronized(pendingScreenLogsLock) {
                    if (handshake != null) pendingHandshakeLogs.addLast(PendingRecord(controllerGeneration, now, handshake))
                    if (summary != null && debugLogsEnabled) {
                        pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, summary))
                        if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    }
                    scheduleLogFrameLocked()
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> {
                    failHandshake("CarPlay failed", description)
                    reconnectAfterLoss(description)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        viewAreas = snapshot.viewAreas
        handshakeTimeline = snapshot.handshakeTimeline
        activeScreenStreamTypes = snapshot.activeScreenStreamTypes
        timelineRevision++
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        if (snapshot.width > 0 && snapshot.height > 0) {
            val restored = snapshot.viewAreas?.activitySize(snapshot.controller.viewAreaIndex)
            activeDisplaySize = restored?.let { DisplaySize(it.first, it.second) }
                ?: DisplaySize(snapshot.width, snapshot.height)
            activeDisplayLayout = currentDisplayLayout()
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, type, active)
        }
        currentSurface?.let(::attachSurface)
        clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
        updateWebDisplayPreviews()
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
                "CarPlay service already running"
            } else {
                "CarPlay session already running"
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        handshakeTimeline = HandshakeTimeline(wirelessEnabled, System.currentTimeMillis(), lastAttemptFailure)
        lastAttemptFailure = null
        timelineRevision++
        updateDebugOverlays()
        val config = createRuntimeConfig()
        val areas = createViewAreas(size)
        val airPlayConfig = createAirPlayConfig(size, areas)
        stopVehicleDiagnostics()
        val vehicleProvider = createVehicleDataSource().also { it.start() }

        lastVehicleDataDiagnostics = null
        currentVehicleProvider = vehicleProvider
        currentGpsProvider = null
        val positionProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                VehicleLocationProvider(
                    AndroidCarPlayLocationProvider(
                        this, precise = vehicleDataConfig.isEnabled(VehicleDataFeature.PRECISE_POSITIONING),
                    ), vehicleDataConfig,
                    android.os.SystemClock::elapsedRealtime,
                ).also { currentGpsProvider = it }
            } else {
                null
            }
        currentWheelProvider = null
        val locationProvider = if (vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED))
            com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider(positionProvider, vehicleProvider).also { currentWheelProvider = it }
            else positionProvider
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "viewAreas=${areas ?: "single"} " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = try {
            createMediaSink(airPlayConfig, controllerGeneration)
        } catch (error: Exception) {
            // Nothing owns these providers yet; release the car service binding and worker.
            vehicleProvider.close()
            currentVehicleProvider = null
            currentGpsProvider = null
            currentWheelProvider = null
            appendLog("Could not create web displays: ${error.message}")
            failHandshake("Could not start web displays", error.message ?: "Check resolution and port 8080")
            setConnectionStage("Could not start web displays; check resolution and port 8080")
            return
        }
        sink = renderer
        currentSurface?.let(::attachSurface)
        clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
        updateWebDisplayPreviews()
        val media = createMediaEngine(renderer)
        // The controller outlives this Activity in CarPlayBackgroundSession, so its callbacks
        // must hold only the application context.
        val app = applicationContext
        val pairings = AirPlayPersistence.loadPairings(app) { id, key ->
            AirPlayPersistence.savePairing(app, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(app, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
            locationProvider = locationProvider,
            vehicleStatusProvider = vehicleProvider,
        )
        controller = next
        viewAreas = areas
        updateWebDisplayPreviews()
        CarPlayBackgroundSession.store(
            next, renderer, size.width, size.height,
            checkNotNull(handshakeTimeline), activeScreenStreamTypes, areas,
        )
        next.start()
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        val layout = currentDisplayLayout()
        if (size == pendingDisplaySize && layout == pendingDisplayLayout) return
        val active = activeDisplaySize
        if (size == active && layout == activeDisplayLayout) {
            // Back to the shown size before the debounce fired: the change was transient.
            pendingDisplaySize = null
            mainHandler.removeCallbacks(applyDisplaySize)
            return
        }
        pendingDisplaySize = size
        pendingDisplayLayout = layout
        mainHandler.removeCallbacks(applyDisplaySize)
        // A rotation onto a declared area is only a view area switch, so it need not wait.
        mainHandler.postDelayed(
            applyDisplaySize,
            if (viewAreas?.turnsWithScreen == true) 0L else DISPLAY_CHANGE_DEBOUNCE_MILLIS,
        )
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get()) return
        val previous = activeDisplaySize
        val layout = currentDisplayLayout()
        val layoutChanged = activeDisplayLayout != null && layout != activeDisplayLayout
        activeDisplaySize = size
        activeDisplayLayout = layout
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (applyViewArea(size)) return
        if (size == previous && !layoutChanged) {
            maybeStartCarPlay()
            return
        }
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null) {
            maybeStartCarPlay()
        } else if (mainWebDisplay.enabled) {
            // The web display owns its negotiated size; only the preview follows the window.
            updateWebDisplayPreviews()
        } else if (!layoutChanged && canKeepDisplayWindow(size)) {
            appendLog(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height} " +
                    "inside declared ${viewAreas?.activityWidthPixels}x${viewAreas?.activityHeightPixels}; " +
                    "keeping session",
            )
            updateWebDisplayPreviews()
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun canKeepDisplayWindow(size: DisplaySize): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (currentDisplayLayout().splitScreen) return false
        val window = areas.activitySize(active.viewAreaIndex)
        // Temporary overlays may shrink the current layout, but cannot expand it using another orientation.
        return size.width <= window.first && size.height <= window.second &&
            (size.width > size.height) == (window.first > window.second) &&
            (areas.indexFor(size.width, size.height) == null || active.viewAreaIndex == areas.indexFor(size.width, size.height))
    }

    /** Switches to a declared area; failure falls through to the renegotiation decision. */
    private fun applyViewArea(size: DisplaySize): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (active.isClosed() || menuOpen || handshakeResetInProgress || shuttingDown.get()) return false
        val current = active.viewAreaIndex
        val (currentWidth, currentHeight) = areas.activitySize(current)
        val turned = (size.width > size.height) != (currentWidth > currentHeight)
        if (turned && !areas.turnsWithScreen) return false
        val layout = currentDisplayLayout()
        val target = areas.indexFor(size.width, size.height, layout.splitScreen, layout.portrait) ?: return false
        if (!turned && target == current && !layout.splitScreen) return false
        if (!active.showViewArea(target)) return false
        appendLog(
            "View area $current -> $target for ${size.width}x${size.height} " +
                "(${areas.areas[target].widthPixels}x${areas.areas[target].heightPixels})",
        )
        updateWebDisplayPreviews()
        return true
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled || reconnectExhausted) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectExhausted = true
            setConnectionStage("$reason; stopped after $MAX_RECONNECT_ATTEMPTS attempts")
            appendLog("$reason; stopped retrying after $MAX_RECONNECT_ATTEMPTS attempts, waiting for manual reconnect")
            return
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            minOf(
                RECONNECT_DELAY_MILLIS shl reconnectAttempts.coerceAtMost(RECONNECT_MAX_SHIFT),
                RECONNECT_MAX_DELAY_MILLIS,
            )
        }
        reconnectAttempts++
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                if (generation != restartGeneration) return@postDelayed
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** The home screen's retry button: reconnects now, and that try opens a fresh retry budget. */
    private fun reconnectManually() {
        if (!reconnectExhausted || shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        reconnectExhausted = false
        reconnectAttempts = 1
        restartCarPlay("Manual reconnect")
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        handshakeResetInProgress = true
        reconnectScheduled = false
        activeAirPlaySession = null
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (!shuttingDown.get() && generation == restartGeneration) {
                            handshakeResetInProgress = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        updateVehicleDataStatus()
        lastVehicleDataDiagnostics = vehicleDataStatusText
        stopMicrophoneGainTest()
        settingsBaseline = captureSettingsBaseline()
        menuOpen = true
        updateVehicleDataStatus()
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        reconnectScheduled = false
        reconnectAttempts = 0
        reconnectExhausted = false
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        val generation = ++restartGeneration
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        activeScreenStreamTypes.clear()
        setConnectionStage("Reconnecting after settings")
        gestureOverlay?.visibility = View.GONE
        settingsCategory = null
        audioQualityOpen = false
        audioQualityCapabilities = null
        audioQualityChecking = false
        audioQualityProbeFailure = null
        audioQualityProbeJob?.cancel()
        audioQualityProbeJob = null
        mfiError = null
        manualHotspotError = null
        resetWebDisplayDrafts()
        syncMainMediaAudioBufferControls()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened; CarPlay handshake reset")
        updateResolutionMenu()
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (shuttingDown.get() || generation != restartGeneration) {
                            return@runOnUiThread
                        }
                        handshakeResetInProgress = false
                        if (!menuOpen && startAfterHandshakeReset) {
                            startAfterHandshakeReset = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        if (!validateWebDisplaySettings()) return
        VehicleProfileRepository(this).save(VehicleProfileRepository.Selection(vehicleProfileJson, vehicleProfileSource))
        stopVehicleDiagnostics()
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        stopVehicleDiagnostics()
        loadVehicleProfile()
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsCategory = null
        audioQualityOpen = false
        audioQualityChecking = false
        audioQualityProbeJob?.cancel()
        audioQualityProbeJob = null
        gestureOverlay?.visibility = View.VISIBLE
        updateWebDisplayPreviews()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            sessionLog.append("CarPlay stopped; clean=$clean")
            sessionLog.close()
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) = attachDisplaySurface(SCREEN_TYPE_MAIN, surface)

    private fun attachDisplaySurface(type: Int, surface: Surface) {
        val outputs = CarPlayWebDisplayFactory.find(sink)
        if (outputs?.owns(type) == true) outputs.preview(type, surface) else sink?.setSurface(type, surface)
    }

    private fun detachSurface(type: Int, surface: Surface) {
        val outputs = CarPlayWebDisplayFactory.find(sink)
        if (outputs?.owns(type) == true) outputs.releasePreview(type, surface) else sink?.clearSurface(type, surface)
    }

    private fun updateWebDisplayPreviews() {
        val view = videoView
        view?.visibility = if (!mainWebDisplay.enabled || mainWebDisplay.preview) View.VISIBLE else View.INVISIBLE
        val area = videoArea
        val placement = mainFrame?.let { mainAreaPlacement(it.width, it.height) }
        if (view != null && area != null) {
            // The decoder output fills the SurfaceView, so the view spans the whole stream while
            // its parent spans the active area. The parent clips the hole the view punches in the
            // opaque window, which hides the rest of the stream.
            if (placement == null) {
                placeView(area, 0, 0, MATCH_PARENT, MATCH_PARENT)
                placeView(view, 0, 0, MATCH_PARENT, MATCH_PARENT)
            } else {
                val (rect, share) = placement
                placeView(area, rect[0].roundToInt(), rect[1].roundToInt(), rect[2].roundToInt(), rect[3].roundToInt())
                placeView(view, 0, 0, (rect[2] / share[0]).roundToInt(), (rect[3] / share[1]).roundToInt())
            }
        }
        clusterPreviewWindow?.let { window ->
            val shown = clusterWebDisplay.enabled && clusterWebDisplay.preview
            window.visibility = if (shown && !clusterPreviewCollapsed) View.VISIBLE else View.GONE
            clusterPreviewHandle?.visibility = if (shown && clusterPreviewCollapsed) View.VISIBLE else View.GONE
            layoutClusterWindow()
        }
        currentSurface?.let { webDisplays?.preview(SCREEN_TYPE_MAIN, it) }
        clusterPreviewSurface?.let { webDisplays?.preview(SCREEN_TYPE_ALT, it) }
    }

    private fun fullFrameParams() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP or Gravity.LEFT)

    /** Moves a child of a FrameLayout, requesting layout only when its placement changes. */
    private fun placeView(view: View, left: Int, top: Int, width: Int, height: Int) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) return
        params.leftMargin = left
        params.topMargin = top
        params.width = width
        params.height = height
        view.layoutParams = params
    }

    /**
     * Where the main stream's active area is drawn in a view of this size, as
     * [left, top, width, height], plus the area's [width, height] share of the stream. Null when
     * the stream simply fills the view.
     */
    private fun mainAreaPlacement(viewWidth: Int, viewHeight: Int): Pair<DoubleArray, DoubleArray>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (mainWebDisplay.enabled) {
            return CarPlayTouchMapper.contentRect(viewWidth, viewHeight, mainWebDisplay.width, mainWebDisplay.height) to
                doubleArrayOf(1.0, 1.0)
        }
        val areas = viewAreas ?: return null
        val index = controller?.viewAreaIndex?.takeIf { it in 0 until areas.size } ?: return null
        val area = areas.areas[index]
        val share = doubleArrayOf(
            area.widthPixels.toDouble() / areas.widthPixels,
            area.heightPixels.toDouble() / areas.heightPixels,
        )
        // The area's own activity size fills the view; any other size (inside the envelope) is
        // letterboxed so the phone's layout keeps its aspect ratio.
        val rect = if (areas.activitySize(index) == (viewWidth to viewHeight)) {
            doubleArrayOf(0.0, 0.0, viewWidth.toDouble(), viewHeight.toDouble())
        } else {
            CarPlayTouchMapper.contentRect(viewWidth, viewHeight, area.widthPixels, area.heightPixels)
        }
        return rect to share
    }

    private fun setClusterPreviewCollapsed(collapsed: Boolean) {
        clusterPreviewCollapsed = collapsed
        updateWebDisplayPreviews()
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                edgeSettingsGestureCaptured = moreGesturesToSettings &&
                    event.x in 0f..(view.width / 8f) &&
                    event.y in 0f..(view.height / 4f)
                edgeSettingsGestureEligible = edgeSettingsGestureCaptured
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        if (mainWebDisplay.enabled && !mainWebDisplay.preview) return true
        val placement = mainAreaPlacement(view.width, view.height)
        val contacts = if (placement == null) {
            CarPlayTouchMapper.contacts(event, view.width, view.height)
        } else {
            // Touch is normalized to the active area, then to the whole declared display.
            val (rect, share) = placement
            CarPlayTouchMapper.contacts(event, rect)
                .map { it.copy(x = it.x * share[0], y = it.y * share[1]) }
        }
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

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
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
        connectionStatus = message
        updateDebugOverlays()
    }

    /** Re-derives what the overlay shows; the Compose layer reads the state set here. */
    private fun updateDebugOverlays() {
        if (SCREEN_TYPE_MAIN !in activeScreenStreamTypes) {
            mainHandler.removeCallbacks(revealMainStream)
            mainStreamReadyAt = 0L
            mainStreamActive = false
        } else if (!mainStreamActive) {
            if (mainStreamReadyAt == 0L) mainStreamReadyAt = SystemClock.uptimeMillis()
            maybeRevealMainStream()
            return
        }
        applySystemBarAppearance()
        updateMediaMetricsOverlay()
    }

    /**
     * Video replaces the home screen only once the handshake list has shown its last record for
     * LOG_FRAME_MILLIS, so the final step is seen; a session that never stops logging waits at
     * most MAIN_STREAM_REVEAL_MAX_WAIT_MILLIS.
     */
    private fun maybeRevealMainStream() {
        mainHandler.removeCallbacks(revealMainStream)
        if (mainStreamActive || SCREEN_TYPE_MAIN !in activeScreenStreamTypes) return
        val now = SystemClock.uptimeMillis()
        val pending = synchronized(pendingScreenLogsLock) { pendingHandshakeLogs.isNotEmpty() }
        val settledAt = if (pending || timelineDirty) now + LOG_FRAME_MILLIS else lastTimelinePublishAt + LOG_FRAME_MILLIS
        val revealAt = minOf(settledAt, mainStreamReadyAt + MAIN_STREAM_REVEAL_MAX_WAIT_MILLIS)
        if (revealAt > now) {
            mainHandler.postDelayed(revealMainStream, revealAt - now)
            return
        }
        mainStreamReadyAt = 0L
        mainStreamActive = true
        applySystemBarAppearance()
        updateMediaMetricsOverlay()
    }

    private fun scheduleLogFrameLocked() {
        if (logFrameScheduled) return
        logFrameScheduled = true
        mainHandler.postAtTime(logFrame, maxOf(SystemClock.uptimeMillis(), lastLogFrameAt + LOG_FRAME_MILLIS))
    }

    /** Dark bar icons only over our own light surfaces; video and the safe-area editor are dark. */
    private fun applySystemBarAppearance() {
        val overThemeSurface = !safeAreaEditorActive && (menuOpen || !mainStreamActive)
        val light = overThemeSurface && !darkMode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun recordHandshake(controllerGeneration: Int, record: HandshakeTimeline.Prepared, atMillis: Long) {
        if (controllerGeneration != restartGeneration) return
        val timeline = handshakeTimeline ?: return
        if (timeline.accept(record, atMillis)) notifyTimelineChanged()
    }

    /** The next log frame publishes the change, together with whatever else arrived. */
    private fun notifyTimelineChanged() {
        timelineDirty = true
        if (!inLogFrame) synchronized(pendingScreenLogsLock) { scheduleLogFrameLocked() }
    }

    private fun failHandshake(title: String, detail: String) {
        val timeline = handshakeTimeline ?: return
        timeline.fail(title, detail, System.currentTimeMillis())
        lastAttemptFailure = timeline.failure?.let { "${it.title}: ${it.detail}" }
        notifyTimelineChanged()
    }

    private fun updateMediaMetricsOverlay() {
        val frame = mainFrame ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(frame::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while the overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            frame.addView(it, metricsLayoutParams(frame.width, frame.height))
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(frame.width, frame.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = metricsLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun metricsLayoutParams(width: Int, height: Int): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (width.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (height.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        val summary = ScreenLogPolicy.summary(message) ?: return
        logLines.add(timestampMillis, formattedLogLine(summary, timestampMillis))
        screenLogsDirty = true
        if (!inLogFrame) synchronized(pendingScreenLogsLock) { scheduleLogFrameLocked() }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog.appendTimestamped(message, timestampMillis)

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        sessionLog.start(
            logFile,
            "xcertplay log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${Process.myPid()} path=${logFile.absolutePath}",
            onError = { Log.e(TAG, it) },
        )
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        screenLogs = logLines.snapshot()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
        }
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        screenLogsDirty = false
        screenLogs = emptyList()
    }

    private fun applyFullscreenMode() {
        // Always edge-to-edge: the video pads itself by the visible bars, Compose by safeDrawing.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTopBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottomBar) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        applySystemBarAppearance()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -----------------------------------------------------------------------------------------
    // Compose UI: home, settings (two levels) and safe-area editor chrome
    // -----------------------------------------------------------------------------------------

    private enum class SettingsCategory(val key: String, @param:DrawableRes val icon: Int) {
        CONNECTION("connection", R.drawable.ic_ms_link),
        VEHICLE("vehicle", R.drawable.ic_ms_directions_car),
        AUDIO("audio", R.drawable.ic_ms_graphic_eq),
        DISPLAY("display", R.drawable.ic_ms_display_settings),
        WEB("web", R.drawable.ic_ms_cast),
        IDENTITY("identity", R.drawable.ic_ms_badge),
        WINDOW("window", R.drawable.ic_ms_fullscreen),
        DIAGNOSTICS("diagnostics", R.drawable.ic_ms_bug_report),
        ;

        val title: String get() = tr("settings.category.$key")
        val summary: String get() = tr("settings.category.$key.summary")
    }

    private data class WebDisplayDraft(val width: String, val height: String, val error: Boolean = false)

    @Composable
    private fun HostOverlay() {
        // The home screen stays composed while it fades out over the video already decoding below.
        val homeAlpha = remember { Animatable(if (mainStreamActive) 0f else 1f) }
        LaunchedEffect(mainStreamActive) {
            if (mainStreamActive) {
                homeAlpha.animateTo(0f, tween(STREAM_REVEAL_FADE_MILLIS, easing = LinearEasing))
            } else {
                homeAlpha.snapTo(1f)
            }
        }
        val homeVisible = !mainStreamActive || homeAlpha.targetValue != 0f || homeAlpha.isRunning
        Box(Modifier.fillMaxSize()) {
            when {
                safeAreaEditorActive -> SafeAreaEditorChrome()
                menuOpen -> SettingsScreen()
                homeVisible -> HomeScreen(
                    timeline = handshakeTimeline,
                    revision = timelineRevision,
                    status = connectionStatus,
                    wireless = handshakeTimeline?.wireless ?: wirelessEnabled,
                    debugLogs = debugLogsEnabled,
                    logLines = screenLogs,
                    onOpenSettings = { openSettingsMenu() },
                    onRetry = if (reconnectExhausted) ::reconnectManually else null,
                    // Read in the draw phase only: the fade redraws frames without recomposing.
                    modifier = Modifier.graphicsLayer { alpha = homeAlpha.value },
                )
                debugLogsEnabled -> StreamingLogOverlay(screenLogs, Modifier.align(Alignment.BottomStart))
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val scheme = MaterialTheme.colorScheme
        M3Surface(Modifier.fillMaxSize(), color = scheme.surface, contentColor = scheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
                SideEffect { settingsTwoPane = twoPane }
                if (twoPane) {
                    val selected = settingsCategory ?: SettingsCategory.CONNECTION
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(CATEGORY_PANE_WIDTH).fillMaxHeight()) {
                            SmallTopBar(
                                title = tr("settings.title"),
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_close, tr("settings.discard"), { cancelSettingsEdits() })
                                },
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = Space.s3),
                                verticalArrangement = Arrangement.spacedBy(Space.s1),
                            ) {
                                SettingsCategory.entries.forEach { category ->
                                    ListRow(
                                        headline = category.title,
                                        support = category.summary,
                                        leadingIcon = category.icon,
                                        selected = category == selected,
                                        onClick = { settingsCategory = category; audioQualityOpen = false },
                                    )
                                }
                            }
                            SettingsActions(stacked = true)
                        }
                        M3Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = Space.s3, end = Space.s3, bottom = Space.s3),
                            shape = RoundedCornerShape(Radius.xl),
                            color = scheme.surfaceContainerLow,
                        ) {
                            ProvideGroupColor(scheme.surfaceContainerHigh) {
                                Column(Modifier.fillMaxSize()) {
                                    SmallTopBar(
                                        title = if (audioQualityOpen) tr("settings.audio.quality") else selected.title,
                                        navigation = if (audioQualityOpen) {
                                            { LvIconButton(R.drawable.ic_ms_arrow_back, tr("common.back"), { audioQualityOpen = false }) }
                                        } else null,
                                    )
                                    CategoryContent(selected, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    val current = settingsCategory
                    Column(Modifier.fillMaxSize()) {
                        if (current == null) {
                            LargeTopBar(
                                title = tr("settings.title"),
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_close, tr("settings.discard"), { cancelSettingsEdits() })
                                },
                            )
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                SettingsCategory.entries.forEach { category ->
                                    ListRow(
                                        headline = category.title,
                                        support = category.summary,
                                        leadingIcon = category.icon,
                                        leadingBox = true,
                                        onClick = { settingsCategory = category; audioQualityOpen = false },
                                        trailing = { TrailingValue(null) },
                                    )
                                }
                                Spacer(Modifier.height(Space.s4))
                            }
                        } else {
                            SmallTopBar(
                                title = if (audioQualityOpen) tr("settings.audio.quality") else current.title,
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_arrow_back, tr("common.back"), {
                                        if (audioQualityOpen) audioQualityOpen = false else settingsCategory = null
                                    })
                                },
                            )
                            CategoryContent(current, Modifier.weight(1f))
                        }
                        SettingsActions(stacked = false)
                    }
                }
            }
        }
    }

    /** Save is the one filled action per screen; leaving the app is a separate, quieter action. */
    @Composable
    private fun SettingsActions(stacked: Boolean) {
        val scheme = MaterialTheme.colorScheme
        val save: @Composable (Modifier) -> Unit = { modifier ->
            Button(
                onClick = { saveSettingsAndReconnect() },
                modifier = modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
            ) {
                LvIcon(R.drawable.ic_ms_refresh, size = 18.dp)
                Spacer(Modifier.width(Space.s2))
                Text(tr("settings.save"), style = MaterialTheme.typography.labelLarge)
            }
        }
        val exit: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(
                onClick = { exitApplication() },
                modifier = modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
            ) {
                LvIcon(R.drawable.ic_ms_power_settings_new, size = 18.dp)
                Spacer(Modifier.width(Space.s2))
                Text(tr("settings.exit"), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                save(Modifier.fillMaxWidth())
                exit(Modifier.fillMaxWidth())
            }
        } else {
            M3Surface(color = scheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    exit(Modifier)
                    Spacer(Modifier.weight(1f))
                    save(Modifier)
                }
            }
        }
    }

    @Composable
    private fun CategoryContent(category: SettingsCategory, modifier: Modifier) {
        key(category, audioQualityOpen) {
            Column(
                modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Space.s6),
            ) {
                when (category) {
                    SettingsCategory.CONNECTION -> ConnectionSettings()
                    SettingsCategory.VEHICLE -> VehicleSettings()
                    SettingsCategory.AUDIO -> if (audioQualityOpen) AudioQualitySettingsContent() else AudioSettings()
                    SettingsCategory.DISPLAY -> DisplaySettings()
                    SettingsCategory.WEB -> WebDisplaySettings()
                    SettingsCategory.IDENTITY -> IdentitySettings()
                    SettingsCategory.WINDOW -> WindowSettings()
                    SettingsCategory.DIAGNOSTICS -> DiagnosticsSettings()
                }
            }
        }
    }

    @Composable
    private fun ConnectionSettings() {
        SectionHeader(tr("settings.connection.mfi"))
        SettingsGroup {
            row {
                SelectRow(
                    title = tr("settings.mfi.target"),
                    options = listOf(
                        MfiTarget.USB_CH341 to tr("settings.mfi.target.ch341"),
                        MfiTarget.I2C to tr("settings.mfi.target.i2c"),
                        MfiTarget.REMOTE to tr("settings.mfi.target.remote"),
                        MfiTarget.LOCAL_FILES to tr("settings.mfi.target.local"),
                    ),
                    selected = mfiTarget,
                    onSelected = ::onMfiTargetSelected,
                )
            }
            when (mfiTarget) {
                MfiTarget.USB_CH341 -> row { HelpText(tr("settings.mfi.ch341_help")) }
                MfiTarget.I2C -> row {
                    FieldRow(
                        label = tr("settings.mfi.i2c_path"),
                        value = mfiI2cPath,
                        onValueChange = {
                            mfiI2cPath = it
                            mfiError = null
                        },
                        help = tr("settings.mfi.i2c_help"),
                        monospace = true,
                    )
                }
                MfiTarget.REMOTE -> {
                    row {
                        FieldRow(
                            label = tr("settings.mfi.server"),
                            value = remoteMfiServer,
                            onValueChange = {
                                remoteMfiServer = it
                                mfiError = null
                            },
                            monospace = true,
                        )
                    }
                    row {
                        FieldRow(
                            label = tr("settings.mfi.token"),
                            value = remoteMfiToken,
                            onValueChange = { remoteMfiToken = it },
                            password = true,
                            help = tr("settings.mfi.remote_help"),
                        )
                    }
                }
                MfiTarget.LOCAL_FILES -> {
                    row {
                        DocumentRow(tr("settings.mfi.certificate"), localMfiCertificateUri) {
                            externalActivityInProgress = true
                            launchDocumentPicker(localMfiCertificatePicker, arrayOf("*/*")) {
                                externalActivityInProgress = false; mfiError = "No document picker on this device: $it"
                            }
                        }
                    }
                    row {
                        DocumentRow(tr("settings.mfi.private_key"), localMfiPrivateKeyUri) {
                            externalActivityInProgress = true
                            launchDocumentPicker(localMfiPrivateKeyPicker, arrayOf("*/*")) {
                                externalActivityInProgress = false; mfiError = "No document picker on this device: $it"
                            }
                        }
                    }
                    row { HelpText(tr("settings.mfi.local_help")) }
                }
            }
        }
        mfiError?.let {
            Spacer(Modifier.height(Space.s3))
            Banner(BannerKind.ERR, tr("settings.mfi.error_title"), body = it)
        }

        SectionHeader(tr("settings.connection.transport"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.wireless"),
                    support = tr("settings.wireless.support"),
                    checked = wirelessEnabled,
                    onCheckedChange = ::onWirelessChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.wifi_high_performance"),
                    support = tr("settings.wifi_high_performance.support"),
                    checked = wifiHighPerformanceEnabled,
                    onCheckedChange = { wifiHighPerformanceEnabled = it },
                )
            }
            row {
                SelectRow(
                    title = tr("settings.wifi_session"),
                    options = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(WirelessHotspotMode.WIFI_P2P to tr("settings.wifi_session.p2p"))
                        }
                        add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to tr("settings.wifi_session.local_only"))
                        add(WirelessHotspotMode.MANUAL to tr("settings.wifi_session.manual"))
                    },
                    selected = wirelessHotspotMode,
                    onSelected = ::onHotspotModeSelected,
                )
            }
        }

        if (wirelessHotspotMode == WirelessHotspotMode.MANUAL) {
            SectionHeader(tr("settings.manual_hotspot"))
            SettingsGroup {
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.ssid"),
                        value = manualHotspotSsid,
                        onValueChange = {
                            manualHotspotSsid = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    SegmentedRow(
                        title = tr("settings.manual_hotspot.band"),
                        options = listOf(
                            ManualHotspotBand.AUTO to tr("settings.manual_hotspot.band.auto"),
                            ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                            ManualHotspotBand.GHZ_5 to "5 GHz",
                        ),
                        selected = manualHotspotBand,
                        onSelected = {
                            manualHotspotBand = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.channel"),
                        value = manualHotspotChannelText,
                        onValueChange = { value ->
                            manualHotspotChannelText = value
                            manualHotspotChannel = value.toIntOrNull() ?: -1
                            manualHotspotError = null
                        },
                        numeric = true,
                        help = tr("settings.manual_hotspot.channel.help"),
                    )
                }
                row {
                    SelectRow(
                        title = tr("settings.manual_hotspot.security"),
                        options = listOf(
                            ManualHotspotSecurity.OPEN to tr("settings.manual_hotspot.security.open"),
                            ManualHotspotSecurity.WPA2 to "WPA2",
                            ManualHotspotSecurity.WPA3_TRANSITION to tr("settings.manual_hotspot.security.wpa3_transition"),
                            ManualHotspotSecurity.WPA3 to "WPA3",
                        ),
                        selected = manualHotspotSecurity,
                        onSelected = {
                            manualHotspotSecurity = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.password"),
                        value = manualHotspotPassphrase,
                        onValueChange = {
                            manualHotspotPassphrase = it
                            manualHotspotError = null
                        },
                        password = true,
                    )
                }
            }
            manualHotspotError?.let {
                Spacer(Modifier.height(Space.s3))
                Banner(BannerKind.ERR, tr("settings.manual_hotspot.error_title"), body = it)
            }
        }

        SectionHeader(tr("settings.hotspot_status"))
        SettingsGroup { row { HotspotStatusBlock() } }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(BannerKind.INFO, tr("settings.compat.title"), body = tr("settings.compat.p2p"))
        }
    }

    @Composable
    private fun HotspotStatusBlock() {
        val status = hotspotStatus
        val (kind, icon) = when {
            !wirelessEnabled -> PillKind.IDLE to R.drawable.ic_ms_radio_button_unchecked
            status.state == "Error" -> PillKind.ERR to R.drawable.ic_ms_error_fill
            status.state in setOf("Ready", "Running", "Active") -> PillKind.OK to R.drawable.ic_ms_check_circle_fill
            status.state in setOf("off", "stopped") -> PillKind.IDLE to R.drawable.ic_ms_radio_button_unchecked
            else -> PillKind.INFO to R.drawable.ic_ms_pending
        }
        val stateKey = if (!wirelessEnabled) "off" else status.state
        val label = trOr("hotspot.state.${stateKey.lowercase(Locale.ROOT).replace(' ', '_')}", stateKey)
        Column(Modifier.fillMaxWidth().padding(top = Space.s3)) {
            StatusPill(kind, icon, label, Modifier.padding(horizontal = Space.s4))
            if (wirelessEnabled) {
                val entries = buildList {
                    status.ssid?.let { add(tr("hotspot.ssid") to it) }
                    status.backend?.let { add(tr("hotspot.backend") to it) }
                    status.band?.let { add(tr("hotspot.band") to it) }
                    status.channel?.let {
                        add(tr("hotspot.channel") to if (it == 0) tr("settings.manual_hotspot.band.auto") else it.toString())
                    }
                }
                if (entries.isNotEmpty()) KeyValueList(entries) else Spacer(Modifier.height(Space.s3))
            } else {
                HelpText(tr("hotspot.off_help"))
            }
        }
    }

    @Composable
    private fun DocumentRow(label: String, uri: String, onChoose: () -> Unit) {
        val name = remember(uri) { localMfiDocumentLabel(uri) }
        ListRow(
            headline = label,
            support = name,
            leadingIcon = R.drawable.ic_ms_description,
            trailing = {
                FilledTonalButton(onClick = onChoose) {
                    Text(tr("common.choose"), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    @Composable
    private fun VehicleSettings() {
        SectionHeader(tr("settings.vehicle.location"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.vehicle.report_location"),
                    support = tr("settings.vehicle.report_location.support"),
                    checked = locationReportingEnabled,
                    onCheckedChange = ::onLocationReportingChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.vehicle.precise_positioning"),
                    support = tr("settings.vehicle.precise_positioning.support"),
                    checked = vehicleDataConfig.isEnabled(VehicleDataFeature.PRECISE_POSITIONING),
                    onCheckedChange = { onVehicleFeatureChanged(VehicleDataFeature.PRECISE_POSITIONING, it) },
                    enabled = locationReportingEnabled,
                )
            }
        }

        SectionHeader(tr("settings.vehicle.properties"))
        val profile = runCatching { selectedVehicleProfile() }.getOrNull()
        val vehicleEntry = selectedVehicleDataEntry()
        SettingsGroup {
            if (vehicleDataEntries.size > 1) {
                row { ListRow(headline = tr("settings.vehicle.source"), support = vehicleEntry.displayName) }
                row { FlowButtons {
                    vehicleDataEntries.forEach { entry ->
                        val select = {
                            stopVehicleDiagnostics()
                            vehicleDataConfig = vehicleDataConfig.copy(sourceId = entry.id)
                            updateVehicleDataStatus()
                        }
                        if (entry.id == vehicleEntry.id) FilledTonalButton(onClick = select) { Text(entry.displayName) }
                        else OutlinedButton(onClick = select) { Text(entry.displayName) }
                    }
                } }
            }
            if (vehicleEntry is JsonProfileVehicleDataEntry) {
            row { ListRow(headline = "Vehicle JSON profile", support = (profile?.name ?: "No profile") +
                if (vehicleProfileSource == VehicleProfileRepository.IMPORTED) " · imported" else "") }
            row { FlowButtons {
                OutlinedButton(onClick = {
                    launchDocumentPicker(vehicleProfileImport,
                        arrayOf("application/json", "text/*", "application/octet-stream")) { reason ->
                        importVehicleProfileFromDisk(reason)
                    }
                }) { Text("Import JSON") }
                bundledProfileNames.forEach { (fileName, label) ->
                    val select = {
                        stopVehicleDiagnostics()
                        VehicleProfileRepository(this@CarPlayHostActivity).bundled(fileName)?.let {
                            vehicleProfileJson = it
                            vehicleProfileSource = VehicleProfileRepository.BUNDLED_PREFIX + fileName
                            vehicleProfileMessage = "$label draft selected. Save & Reconnect applies it."
                        }
                        Unit
                    }
                    if (vehicleProfileSource == VehicleProfileRepository.BUNDLED_PREFIX + fileName) FilledTonalButton(onClick = select) { Text(label) }
                    else OutlinedButton(onClick = select) { Text(label) }
                }
            } }
            // What the profile feeds; the feature switches below decide what is actually sent.
            profile?.signals?.filter { it.target != null }?.forEach { signal -> row {
                ListRow(headline = signal.target!!.label, support = "${signal.name} [${signal.id}]" +
                    (signal.unit?.let { " · $it" } ?: ""))
            } }
            row { HelpText(vehicleProfileMessage) }
            }
            // Precise positioning is a location option and sits under the location switch instead.
            VehicleDataFeature.entries.filter { it != VehicleDataFeature.PRECISE_POSITIONING }.forEach { feature ->
                row {
                    if (!vehicleEntry.canProvide(feature)) {
                        ListRow(
                            headline = feature.label,
                            support = tr("settings.vehicle.feature_unavailable", "reason" to feature.description),
                            enabled = false,
                        )
                    } else {
                        SwitchRow(
                            title = feature.label,
                            support = feature.description,
                            checked = vehicleDataConfig.isEnabled(feature),
                            onCheckedChange = { onVehicleFeatureChanged(feature, it) },
                        )
                    }
                }
            }
        }
        HelpText(tr("settings.vehicle.applies_on_close"))

        SectionHeader(tr("settings.vehicle.diagnostics"))
        SettingsGroup {
            row {
                Text(
                    vehicleDataStatusText,
                    style = LavenderCode.medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(Space.s4),
                )
            }
            row {
                FlowButtons {
                    FilledTonalButton(onClick = { startVehicleDiagnostics() }) {
                        LvIcon(R.drawable.ic_ms_refresh, size = 18.dp)
                        Spacer(Modifier.width(Space.s2))
                        Text("Start live diagnostics (10 min)", style = MaterialTheme.typography.labelLarge)
                    }
                    if (canRequestVehicleSpeedPermission()) {
                        OutlinedButton(onClick = { requestVehicleSpeedPermission() }) {
                            LvIcon(R.drawable.ic_ms_speed, size = 18.dp)
                            Spacer(Modifier.width(Space.s2))
                            Text(tr("settings.vehicle.grant_speed"), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        HelpText(tr("settings.vehicle.no_adapter"))
        HelpText(tr("settings.vehicle.requirements"))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FlowButtons(content: @Composable () -> Unit) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) { content() }
    }

    @Composable
    private fun AudioSettings() {
        SettingsGroup {
            row {
                ListRow(
                    headline = tr("settings.audio.quality"),
                    support = tr("settings.audio.quality.summary"),
                    onClick = ::openAudioQualitySettings,
                    trailing = { TrailingValue(null) },
                )
            }
        }
        SectionHeader(tr("settings.audio.playback"))
        val bufferValues = remember {
            (MainMediaAudioBuffer.MIN_DURATION_MS..MainMediaAudioBuffer.MAX_DURATION_MS step
                MainMediaAudioBuffer.STEP_DURATION_MS).toList()
        }
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.audio.buffer"),
                    support = tr("settings.audio.buffer.support"),
                    valueLabel = mainMediaAudioBufferLabel(mainMediaAudioBufferDurationMs),
                    index = bufferValues.indexOf(mainMediaAudioBufferDurationMs).coerceAtLeast(0),
                    count = bufferValues.size,
                    onIndexChange = { mainMediaAudioBufferDurationMs = bufferValues[it] },
                )
            }
            if (advancedAudioChannelMappingSupported) {
                row {
                    SwitchRow(
                        title = tr("settings.audio.channel_mapping"),
                        support = tr("settings.audio.channel_mapping.support"),
                        checked = advancedAudioChannelMapping,
                        onCheckedChange = ::onAdvancedAudioChannelMappingChanged,
                    )
                }
            }
            row {
                SwitchRow(
                    title = tr("settings.audio.focus"),
                    support = tr("settings.audio.focus.support"),
                    checked = audioFocusEnabled,
                    onCheckedChange = ::onAudioFocusEnabledChanged,
                )
            }
        }

        SectionHeader(tr("settings.audio.microphone"))
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.audio.mic_gain"),
                    support = tr("settings.audio.mic_gain.support"),
                    valueLabel = microphoneGainLabel(microphoneGainPercent),
                    index = MicrophoneGain.stepForPercent(microphoneGainPercent),
                    count = MicrophoneGain.STEP_COUNT,
                    onIndexChange = { microphoneGainPercent = MicrophoneGain.percentForStep(it) },
                )
            }
            row { MicrophoneTestRow() }
        }
    }

    private enum class QualityStream(val route: AudioQualityProfiles.Route, val key: String) {
        MUSIC(AudioQualityProfiles.Route.MUSIC, "music"),
        ASSISTANT(AudioQualityProfiles.Route.ASSISTANT, "assistant"),
        NAVIGATION(AudioQualityProfiles.Route.NAVIGATION, "navigation"),
        PHONE(AudioQualityProfiles.Route.PHONE, "phone"),
        MICROPHONE(AudioQualityProfiles.Route.MICROPHONE, "microphone"),
    }

    /** 每一路当前可选的声道与采样率：协议允许 ∩ 本机能力 ∩ 用户设置的上限。 */
    private class QualityOptions(private val byStream: Map<QualityStream, Map<Int, List<Int>>>) {
        // 预先算好，避免每次重组都过滤排序一遍。
        private val channelLists = byStream.mapValues { (_, byChannels) ->
            byChannels.filterValues { it.isNotEmpty() }.keys.sorted()
        }

        fun channels(stream: QualityStream): List<Int> = channelLists[stream].orEmpty()

        fun rates(stream: QualityStream, channels: Int): List<Int> =
            byStream[stream]?.get(channels).orEmpty()

        /** 用户设置是上限：在可用项里取不超过它的最大值。 */
        fun effective(stream: QualityStream, requested: AudioQualityLimit): AudioQualityLimit? {
            val channels = channels(stream).lastOrNull { it <= requested.channels } ?: return null
            val rate = rates(stream, channels).lastOrNull { it <= requested.sampleRate } ?: return null
            return AudioQualityLimit(rate, channels)
        }
    }

    private fun openAudioQualitySettings() {
        audioQualityOpen = true
        // 先渲染缓存结果，设备没变化时不会出现"检测中"跳变，也不会重复探测。
        audioQualityCapabilities = AudioQualityCapabilities.cached()
        requestAudioQualityProbe()
    }

    /**
     * 只在实际需要时探测。探测结果按音频设备指纹缓存在 [AudioQualityCapabilities] 里，
     * 设备不变时这次调用几乎立即返回，不会重复创建 AudioTrack / MediaCodec。
     */
    private fun requestAudioQualityProbe(force: Boolean = false) {
        audioQualityProbeJob?.cancel()
        audioQualityProbeFailure = null
        val known = AudioQualityCapabilities.cached()
        // 已有可用结果时不再显示"检测中"：设备没变化的话这轮只是校验指纹，
        // 让页面在这里闪一下再展开属实没必要。
        audioQualityChecking = known == null || known.failure != null
        audioQualityProbeJob = lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(AudioQualityCapabilities.probe(applicationContext, force))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            audioQualityChecking = false
            if (!audioQualityOpen) return@launch
            val result = outcome.getOrNull()
            // 命中缓存时返回的是同一个实例；跳过赋值可以省掉整页重组。
            if (result != null && result !== known) audioQualityCapabilities = result
            outcome.exceptionOrNull()?.let { error ->
                audioQualityProbeFailure = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "音频能力检测失败", error)
            }
        }
    }

    private fun qualityLimit(stream: QualityStream): AudioQualityLimit = when (stream) {
        QualityStream.MUSIC -> audioQuality.music
        QualityStream.ASSISTANT -> audioQuality.assistant
        QualityStream.NAVIGATION -> audioQuality.navigation
        QualityStream.PHONE -> audioQuality.phone
        QualityStream.MICROPHONE -> audioQuality.microphone
    }

    private fun setQualityLimit(stream: QualityStream, limit: AudioQualityLimit) {
        audioQuality = when (stream) {
            QualityStream.MUSIC -> audioQuality.copy(music = limit)
            QualityStream.ASSISTANT -> audioQuality.copy(assistant = limit)
            QualityStream.NAVIGATION -> audioQuality.copy(navigation = limit)
            QualityStream.PHONE -> audioQuality.copy(phone = limit)
            QualityStream.MICROPHONE -> audioQuality.copy(microphone = limit)
        }
    }

    private fun buildQualityOptions(
        caps: AudioQualityCapabilities.Result? = audioQualityCapabilities,
    ): QualityOptions {
        if (caps == null || caps.failure != null) return QualityOptions(emptyMap())
        val opus = audioQuality.opusEnabled
        return QualityOptions(
            QualityStream.entries.associateWith { stream ->
                AudioQualityProfiles.channels(stream.route, wirelessEnabled, opus).associateWith { channels ->
                    AudioQualityProfiles.rates(stream.route, wirelessEnabled, opus)
                        .filter { it in machineRates(caps, stream.route, channels, opus) }
                }
            },
        )
    }

    /** 某一路某种声道数下，本机真正能处理的采样率。 */
    private fun machineRates(
        caps: AudioQualityCapabilities.Result,
        route: AudioQualityProfiles.Route,
        channels: Int,
        opus: Boolean,
    ): List<Int> = when {
        // 无线且关闭 Opus 时，麦克风上行走 ELD 编码，由编码器实测结果决定。
        route == AudioQualityProfiles.Route.MICROPHONE && wirelessEnabled && !opus -> caps.eldMicrophoneRates
        route == AudioQualityProfiles.Route.MICROPHONE && !wirelessEnabled -> caps.microphoneCaptureRates
        else -> caps.rates(AudioQualityProfiles.codec(route, wirelessEnabled, opus), channels)
    }

    /**
     * 生成真正写进 /info 的上限。任何一路没有可用组合时退回该路的原始设置，
     * 而不是让整个配置构造失败：手机自己会再筛一遍。
     */
    private fun effectiveAudioQuality(options: QualityOptions): AudioQualitySettings {
        fun limit(stream: QualityStream): AudioQualityLimit =
            options.effective(stream, qualityLimit(stream)) ?: qualityLimit(stream)
        return audioQuality.copy(
            music = limit(QualityStream.MUSIC),
            assistant = limit(QualityStream.ASSISTANT),
            navigation = limit(QualityStream.NAVIGATION),
            phone = limit(QualityStream.PHONE),
            microphone = limit(QualityStream.MICROPHONE),
        )
    }

    @Composable
    private fun AudioQualitySettingsContent() {
        val caps = audioQualityCapabilities
        val options = remember(caps, audioQuality, wirelessEnabled) { buildQualityOptions() }
        HelpText(tr("settings.audio.quality.help"))
        val compatibleAvailable = caps?.compatibleModeAvailable == true
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.audio.quality.opus"),
                    support = tr("settings.audio.quality.opus.support"),
                    checked = audioQuality.opusEnabled,
                    // 关闭 Opus 需要本机真的能用 ELD 收和发；打开永远允许。
                    enabled = !audioQualityChecking && (!audioQuality.opusEnabled || compatibleAvailable),
                    onCheckedChange = { audioQuality = audioQuality.copy(opusEnabled = it) },
                )
            }
        }
        // 有线 CarPlay 全部走 LPCM，这条只对无线有意义。
        if (!audioQualityChecking && wirelessEnabled && !compatibleAvailable) {
            HelpText(tr("settings.audio.quality.compatibility_unavailable"))
        }
        when {
            audioQualityChecking -> {
                HelpText(tr("settings.audio.quality.checking"))
                return
            }
            caps == null || caps.failure != null -> {
                HelpText(tr("settings.audio.quality.check_failed"))
                (caps?.failure ?: audioQualityProbeFailure)?.let {
                    HelpText(tr("settings.audio.quality.check_failed_reason", "reason" to it))
                }
                TextButton(onClick = { requestAudioQualityProbe(force = true) }) { Text(tr("settings.audio.quality.retry")) }
                return
            }
        }
        for (stream in QualityStream.entries) {
            val requested = qualityLimit(stream)
            val channels = options.channels(stream)
            val effective = options.effective(stream, requested)
            val selectedChannels = effective?.channels ?: channels.firstOrNull() ?: requested.channels
            SectionHeader(tr("settings.audio.quality.${stream.key}"))
            val rates = options.rates(stream, selectedChannels)
            SettingsGroup {
                row {
                    SelectRow(
                        title = tr("settings.audio.quality.sample_rate"),
                        options = rates.map { it to rateLabel(it) },
                        selected = effective?.sampleRate ?: requested.sampleRate,
                        support = if (effective == null) tr("settings.audio.quality.unsupported") else null,
                        enabled = rates.isNotEmpty(),
                        onSelected = { setQualityLimit(stream, requested.copy(sampleRate = it)) },
                    )
                }
                row {
                    SelectRow(
                        title = tr("settings.audio.quality.channels"),
                        options = channels.map {
                            it to tr(if (it == 1) "settings.audio.quality.mono" else "settings.audio.quality.stereo")
                        },
                        selected = selectedChannels,
                        enabled = channels.size > 1,
                        onSelected = { setQualityLimit(stream, requested.copy(channels = it)) },
                    )
                }
            }
        }
        SectionHeader(tr("settings.audio.quality.bitrate"))
        SettingsGroup { row { ListRow(headline = tr("settings.audio.quality.automatic")) } }
    }

    private fun rateLabel(rate: Int): String =
        if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", rate / 1000.0)

    @Composable
    private fun MicrophoneTestRow() {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            LvIcon(R.drawable.ic_ms_mic, tint = if (micTestRunning) scheme.primary else scheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row {
                    Text(tr("settings.audio.peak_level"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(micLevelText, style = MaterialTheme.typography.labelMedium.tabular, color = scheme.onSurfaceVariant)
                }
                LinearMeter(micPeakPercent / 100f)
            }
            FilledTonalButton(
                onClick = { if (microphoneLevelMonitor?.isRunning == true) stopMicrophoneGainTest() else startMicrophoneGainTest() },
                enabled = menuOpen && !handshakeResetInProgress,
            ) {
                Text(
                    if (micTestRunning) tr("settings.audio.stop") else tr("settings.audio.test"),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    @Composable
    private fun DisplaySettings() {
        SectionHeader(tr("settings.display.resolution"))
        val fpsValues = remember {
            (AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS step AirPlayDisplaySettings.FPS_STEP).toList()
        }
        val lengthValues = remember {
            (AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM step
                AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP).toList()
        }
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.display.scale"),
                    support = tr("settings.display.scale.support"),
                    valueLabel = CarPlayDisplayScale.label(displayScaleTenths),
                    index = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS,
                    count = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS + 1,
                    onIndexChange = {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(CarPlayDisplayScale.MIN_TENTHS + it)
                        updateResolutionMenu()
                    },
                    startLabel = CarPlayDisplayScale.label(CarPlayDisplayScale.MIN_TENTHS),
                    endLabel = CarPlayDisplayScale.label(CarPlayDisplayScale.MAX_TENTHS),
                )
            }
            row {
                SliderRow(
                    title = tr("settings.display.fps"),
                    valueLabel = tr("unit.fps", "value" to fps),
                    index = fpsValues.indexOf(fps).coerceAtLeast(0),
                    count = fpsValues.size,
                    onIndexChange = {
                        fps = fpsValues[it]
                        updateResolutionMenu()
                    },
                )
            }
            row {
                SegmentedRow(
                    title = tr("settings.display.physical_basis"),
                    options = listOf(
                        AirPlayPhysicalSizeBasis.WIDTH to tr("settings.display.physical_basis.width"),
                        AirPlayPhysicalSizeBasis.HEIGHT to tr("settings.display.physical_basis.height"),
                    ),
                    selected = physicalSizeBasis,
                    onSelected = {
                        physicalSizeBasis = it
                        updateResolutionMenu()
                    },
                )
            }
            row {
                SliderRow(
                    title = tr("settings.display.physical_length"),
                    valueLabel = tr("unit.mm", "value" to widthPhysicalMm),
                    index = lengthValues.indexOf(widthPhysicalMm).coerceAtLeast(0),
                    count = lengthValues.size,
                    onIndexChange = {
                        widthPhysicalMm = lengthValues[it]
                        updateResolutionMenu()
                    },
                )
            }
        }

        SectionHeader(tr("settings.display.video"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.display.hevc"),
                    support = tr("settings.display.hevc.support"),
                    checked = hevcEnabled,
                    onCheckedChange = ::onHevcChanged,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                row {
                    SwitchRow(
                        title = tr("settings.display.hevc_software"),
                        support = tr("settings.display.hevc_software.support"),
                        checked = hevcSoftwareDecoderEnabled,
                        enabled = hevcEnabled,
                        onCheckedChange = ::onHevcSoftwareDecoderChanged,
                    )
                }
            }
        }

        SectionHeader(tr("settings.display.safe_area"))
        SettingsGroup {
            row {
                ListRow(
                    headline = tr("settings.display.safe_area.current"),
                    support = safeAreaSummaryText,
                    leadingIcon = R.drawable.ic_ms_crop_free,
                )
            }
            row {
                FlowButtons {
                    FilledTonalButton(onClick = { openSafeAreaEditor() }) {
                        Text(tr("settings.display.safe_area.edit"), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { resetSafeAreaForCurrentSize() }) {
                        LvIcon(R.drawable.ic_ms_restart_alt, size = 18.dp)
                        Spacer(Modifier.width(Space.s2))
                        Text(tr("settings.display.safe_area.reset"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            row {
                SwitchRow(
                    title = tr("settings.display.safe_area.draw_outside"),
                    support = tr("settings.display.safe_area.draw_outside.support"),
                    checked = safeAreaDrawOutside,
                    onCheckedChange = {
                        safeAreaDrawOutside = it
                        updateResolutionMenu()
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(BannerKind.INFO, tr("settings.compat.title"), body = tr("settings.compat.hevc"))
        }
    }

    @Composable
    private fun WebDisplaySettings() {
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            val draft = webDisplayDrafts[type] ?: WebDisplayDraft(config.width.toString(), config.height.toString())
            SectionHeader(if (type == SCREEN_TYPE_MAIN) tr("settings.web.main") else tr("settings.web.instrument"))
            SettingsGroup {
                row {
                    SwitchRow(
                        title = tr("settings.web.enabled"),
                        support = tr("settings.web.enabled.support"),
                        checked = config.enabled,
                        onCheckedChange = { setWebDisplayConfig(type, webDisplayConfig(type).copy(enabled = it)) },
                    )
                }
                if (config.enabled) {
                    row {
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FieldRow(
                                    label = tr("settings.web.width"),
                                    value = draft.width,
                                    onValueChange = { webDisplayDrafts[type] = draft.copy(width = it, error = false) },
                                    numeric = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FieldRow(
                                    label = tr("settings.web.height"),
                                    value = draft.height,
                                    onValueChange = { webDisplayDrafts[type] = draft.copy(height = it, error = false) },
                                    numeric = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                if (draft.error) tr("settings.web.dimension_error") else tr("settings.web.dimension_help"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (draft.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Space.s8, end = Space.s4, bottom = Space.s3),
                            )
                        }
                    }
                    row {
                        SwitchRow(
                            title = tr("settings.web.preview"),
                            support = tr("settings.web.preview.support"),
                            checked = config.preview,
                            onCheckedChange = { setWebDisplayConfig(type, webDisplayConfig(type).copy(preview = it)) },
                        )
                    }
                    row {
                        Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                            Text(
                                tr("settings.web.address"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    webDisplayAddresses[type].orEmpty().ifEmpty { tr("settings.web.no_network") },
                                    style = LavenderCode.large,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        HelpText(tr("settings.web.https_note"), Modifier.padding(top = Space.s2))
        HelpText(tr("settings.web.apply_note"))
    }

    @Composable
    private fun IdentitySettings() {
        SectionHeader(tr("settings.identity.vehicle"))
        SettingsGroup {
            row {
                FieldRow(tr("settings.identity.manufacturer"), manufacturer, {
                    manufacturer = it
                    updateResolutionMenu()
                })
            }
            row {
                FieldRow(tr("settings.identity.model"), model, {
                    model = it
                    updateResolutionMenu()
                })
            }
            row {
                FieldRow(tr("settings.identity.oem_label"), oemLabel, {
                    oemLabel = it
                    updateResolutionMenu()
                })
            }
        }

        SectionHeader(tr("settings.identity.appearance"))
        SettingsGroup {
            row { AirPlayIconRow() }
            row {
                SegmentedRow(
                    title = tr("settings.identity.driving_side"),
                    options = listOf(false to tr("settings.identity.driving_side.left"), true to tr("settings.identity.driving_side.right")),
                    selected = rightHandDrive,
                    onSelected = {
                        rightHandDrive = it
                        updateResolutionMenu()
                    },
                )
            }
        }
    }

    @Composable
    private fun AirPlayIconRow() {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(Radius.md)).background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val icon = airPlayIcon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = tr("settings.identity.icon"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LvIcon(R.drawable.ic_ms_image, tint = scheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(tr("settings.identity.icon"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(
                    if (airPlayIconCustom) tr("settings.identity.icon.custom") else tr("settings.identity.icon.default"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilledTonalButton(onClick = {
                        externalActivityInProgress = true
                        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        if (intent.resolveActivity(packageManager) == null) {
                            externalActivityInProgress = false
                            appendLog("No image picker on this device")
                        } else runCatching { imagePicker.launch("image/*") }.onFailure {
                            externalActivityInProgress = false
                            appendLog("Image picker failed: ${it.javaClass.simpleName}")
                        }
                    }) { Text(tr("settings.identity.icon.choose"), style = MaterialTheme.typography.labelLarge) }
                    TextButton(
                        onClick = {
                            AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                            updateAirPlayIconPreview()
                        },
                        enabled = airPlayIconCustom,
                    ) { Text(tr("settings.identity.icon.reset"), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }

    @Composable
    private fun WindowSettings() {
        SectionHeader(tr("settings.window.startup"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.auto_start"),
                    support = tr("settings.window.auto_start.support"),
                    checked = autoStartOnBoot,
                    onCheckedChange = {
                        autoStartOnBoot = it
                        appendLog("Boot auto-start ${if (it) "enabled" else "disabled"}")
                    },
                )
            }
        }
        SectionHeader(tr("settings.window.fullscreen"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.hide_top"),
                    support = tr("settings.window.hide_top.support"),
                    checked = hideTopBar,
                    onCheckedChange = {
                        hideTopBar = it
                        applyFullscreenMode()
                        refreshDisplaySizeAfterLayout()
                    },
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.window.hide_bottom"),
                    support = tr("settings.window.hide_bottom.support"),
                    checked = hideBottomBar,
                    onCheckedChange = {
                        hideBottomBar = it
                        applyFullscreenMode()
                        refreshDisplaySizeAfterLayout()
                    },
                )
            }
        }
        SectionHeader(tr("settings.window.gestures"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.edge_gesture"),
                    support = tr("settings.window.edge_gesture.support"),
                    checked = moreGesturesToSettings,
                    onCheckedChange = { moreGesturesToSettings = it },
                )
            }
        }
        HelpText(tr("settings.window.three_finger_help"))
    }

    @Composable
    private fun DiagnosticsSettings() {
        SectionHeader(tr("settings.diagnostics.monitoring"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.latency"),
                    support = tr("settings.diagnostics.latency.support"),
                    checked = mediaMetricsEnabled,
                    onCheckedChange = ::onMediaMetricsChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.debug_logs"),
                    support = tr("settings.diagnostics.debug_logs.support"),
                    checked = debugLogsEnabled,
                    onCheckedChange = ::onDebugLogsChanged,
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.capture"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.audio_capture"),
                    support = tr("settings.diagnostics.audio_capture.support"),
                    checked = audioPacketCaptureEnabled,
                    onCheckedChange = ::onAudioPacketCaptureChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.raw_video"),
                    support = tr("settings.diagnostics.raw_video.support"),
                    checked = videoFrameLogsEnabled,
                    onCheckedChange = ::onVideoFrameLogsChanged,
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.system"))
        SettingsGroup {
            row {
                ListRow(
                    headline = tr("settings.diagnostics.bluetooth"),
                    support = tr("settings.diagnostics.bluetooth.support"),
                    leadingIcon = R.drawable.ic_ms_bluetooth,
                    onClick = { openSystemBluetoothSettings() },
                    trailing = { LvIcon(R.drawable.ic_ms_open_in_new, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.summary"))
        SettingsGroup { row { KeyValueList(configSummary) } }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(
                BannerKind.INFO,
                tr("settings.compat.title"),
                body = tr("settings.compat.p2p") + "\n" + tr("settings.compat.hevc"),
            )
        }
    }

    /** Title and Save/Cancel over the safe-area editor; the editor view below takes the drags. */
    @Composable
    private fun SafeAreaEditorChrome() {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s4)) {
            M3Surface(
                modifier = Modifier.align(Alignment.TopStart),
                shape = RoundedCornerShape(Radius.lg),
                color = scheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s3)) {
                    Text(tr("safe_area.editor.title"), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Text(tr("safe_area.editor.help"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            M3Surface(
                modifier = Modifier.align(Alignment.BottomCenter),
                shape = RoundedCornerShape(Radius.xl),
                color = scheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(Space.s3),
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    TextButton(onClick = { closeSafeAreaEditor() }) {
                        Text(tr("common.cancel"), style = MaterialTheme.typography.labelLarge)
                    }
                    Button(onClick = { saveSafeAreaEditor() }) {
                        Text(tr("common.save"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    // ----- Settings change handlers (formerly view listeners) --------------------------------

    private fun onWirelessChanged(checked: Boolean) {
        if (wirelessEnabled == checked) return
        wirelessEnabled = checked
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        appendLog("Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; applies when settings close")
        requestStartupPrerequisites()
    }

    private fun onMfiTargetSelected(target: MfiTarget) {
        if (mfiTarget == target) return
        mfiTarget = target
        updateMfiTargetFields()
        appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
    }

    private fun onHotspotModeSelected(mode: WirelessHotspotMode) {
        if (wirelessHotspotMode == mode) return
        wirelessHotspotMode = mode
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        updateManualHotspotFields()
        appendLog("Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; applies when settings close")
    }

    private fun onVehicleFeatureChanged(feature: VehicleDataFeature, checked: Boolean) {
        vehicleDataConfig = vehicleDataConfig.with(feature, checked)
        if (feature == VehicleDataFeature.WHEEL_SPEED && checked) requestVehicleSpeedPermission()
        updateVehicleDataStatus()
    }

    private fun onAdvancedAudioChannelMappingChanged(checked: Boolean) {
        advancedAudioChannelMapping = checked
        appendLog("Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; applies when settings close")
        updateResolutionMenu()
    }

    private fun onAudioFocusEnabledChanged(checked: Boolean) {
        if (audioFocusEnabled == checked) return
        audioFocusEnabled = checked
        AirPlayPersistence.saveAudioFocusEnabled(this, checked)
        CarPlayBackgroundSession.audioFocus(this).apply {
            setEnabled(checked)
            requestFromHost()
        }
        appendLog("Audio focus ${if (checked) "enabled" else "disabled"}")
    }

    private fun onHevcChanged(checked: Boolean) {
        if (hevcEnabled == checked) return
        hevcEnabled = checked
        appendLog("HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; applies when settings close")
        updateResolutionMenu()
    }

    private fun onHevcSoftwareDecoderChanged(checked: Boolean) {
        if (hevcSoftwareDecoderEnabled == checked) return
        hevcSoftwareDecoderEnabled = checked
        appendLog(
            "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
    }

    private fun onDebugLogsChanged(checked: Boolean) {
        debugLogsEnabled = checked
        if (!checked) clearScreenLogs()
        appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
        updateDebugOverlays()
    }

    private fun onVideoFrameLogsChanged(checked: Boolean) {
        videoFrameLogsEnabled = checked
        sessionLog.videoFramesEnabled = checked
        appendLog("Raw video file logging ${if (checked) "enabled" else "disabled"}")
    }

    private fun onMediaMetricsChanged(checked: Boolean) {
        mediaMetricsEnabled = checked
        appendLog("Media latency monitor ${if (checked) "enabled" else "disabled"}")
        updateMediaMetricsOverlay()
    }

    private fun onAudioPacketCaptureChanged(checked: Boolean) {
        audioPacketCaptureEnabled = checked
        appendLog("Audio diagnostic capture ${if (checked) "enabled" else "disabled"}; applies when settings close")
    }

    private fun resetWebDisplayDrafts() {
        webDisplayDrafts.clear()
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            webDisplayDrafts[type] = WebDisplayDraft(config.width.toString(), config.height.toString())
        }
        updateWebDisplayAddresses()
    }

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, " +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val CLUSTER_WINDOW_MAX_WIDTH_FRACTION = 0.45
        const val CLUSTER_WINDOW_MAX_HEIGHT_FRACTION = 0.35
        const val CLUSTER_WINDOW_MIN_WIDTH_DP = 160
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        /** One UI refresh of the handshake list and screen log, however fast records arrive. */
        const val LOG_FRAME_MILLIS = 200L
        const val MAIN_STREAM_REVEAL_MAX_WAIT_MILLIS = 1_500L
        const val STREAM_REVEAL_FADE_MILLIS = 200
        const val IDLE_WORKER_KEEP_ALIVE_SECONDS = 30L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val RECONNECT_MAX_DELAY_MILLIS = 30_000L
        const val RECONNECT_MAX_SHIFT = 4
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        val TWO_PANE_MIN_WIDTH = 720.dp
        val CATEGORY_PANE_WIDTH = 320.dp
        val NO_VIDEO_BACKGROUND = Color.BLACK
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private class PendingRecord(
        val generation: Int,
        val timestampMillis: Long,
        val record: HandshakeTimeline.Prepared,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/**
 * Collects the current size and every saved safe area size, or its swap, in the current orientation.
 * Areas share the display origin and fit inside its envelope; updateViewArea switches declared sizes.
 */
class CarPlayViewAreas private constructor(
    private val activitySizes: List<Pair<Int, Int>>,
    val areas: List<AirPlayViewArea>,
) {
    /** Envelope of all areas in negotiated display pixels. */
    val widthPixels: Int = areas.maxOf { it.widthPixels }
    val heightPixels: Int = areas.maxOf { it.heightPixels }

    /** Envelope of all areas in activity pixels. */
    val activityWidthPixels: Int = activitySizes.maxOf { it.first }
    val activityHeightPixels: Int = activitySizes.maxOf { it.second }

    /** Both orientations are declared, so rotating is an area switch rather than a reconnect. [build] never does this. */
    val turnsWithScreen: Boolean =
        activitySizes.any { (w, h) -> w > h } && activitySizes.any { (w, h) -> w < h }

    val size: Int get() = areas.size

    fun activitySize(index: Int): Pair<Int, Int> = activitySizes[index]

    var splitScreenRatio: Double? = null
        private set

    fun indexFor(width: Int, height: Int, splitScreen: Boolean = false, portrait: Boolean = height > width): Int? {
        if (splitScreen) {
            val fullWidth = if (portrait) minOf(activityWidthPixels, activityHeightPixels) else activityWidthPixels
            val fullHeight = if (portrait) activityHeightPixels else minOf(activityWidthPixels, activityHeightPixels)
            splitScreenRatio = minOf(width.toDouble() / fullWidth, height.toDouble() / fullHeight)
        }
        // Match only declared dimensions; unknown split-screen sizes require renegotiation.
        return activitySizes.indexOf(width to height).takeIf { it >= 0 }
    }

    /** Whether an activity of this size still fits inside the declared envelope. */
    fun contains(width: Int, height: Int): Boolean =
        width <= activityWidthPixels && height <= activityHeightPixels

    override fun toString(): String = activitySizes.mapIndexed { index, (w, h) ->
        val area = areas[index]
        "$index:${w}x$h->${area.widthPixels}x${area.heightPixels}"
    }.joinToString(prefix = "[", postfix = "] envelope=${widthPixels}x$heightPixels")

    companion object {
        fun build(
            currentWidth: Int,
            currentHeight: Int,
            savedSizes: List<Pair<Int, Int>>,
            scaleTenths: Int,
            safeArea: (width: Int, height: Int) -> SafeAreaRect?,
        ): CarPlayViewAreas {
            require(currentWidth > 0 && currentHeight > 0) { "Activity dimensions must be positive" }
            // Only the current orientation is declared: the envelope of a landscape and a portrait
            // area is a longest-edge square, and the phone encodes that whole square every frame.
            // Rotating therefore renegotiates instead of switching areas.
            val orientation = Integer.signum(currentWidth - currentHeight)
            val sizes = linkedSetOf<Pair<Int, Int>>()
            (listOf(currentWidth to currentHeight) + savedSizes).forEach { (w, h) ->
                if (w > 0 && h > 0) {
                    if (Integer.signum(w - h) == orientation) sizes += w to h
                    if (Integer.signum(h - w) == orientation) sizes += h to w
                }
            }
            val declared = sizes.toList()
            val areas = declared.map { (w, h) ->
                val scaled = CarPlayDisplayScale.apply(AirPlayDisplayConfig(w, h), scaleTenths)
                AirPlayViewArea(
                    widthPixels = scaled.widthPixels,
                    heightPixels = scaled.heightPixels,
                    // A swapped size without its own saved rectangle stays full screen: the
                    // rotation direction is unknown, so the other orientation's rectangle is not reused.
                    safeArea = AirPlaySafeArea.toInsets(
                        mapping = safeArea(w, h),
                        activityWidthPixels = w,
                        activityHeightPixels = h,
                        displayWidthPixels = scaled.widthPixels,
                        displayHeightPixels = scaled.heightPixels,
                    ),
                )
            }
            return CarPlayViewAreas(declared, areas)
        }
    }
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    val log = SessionLog()
    private var audioFocusSession: AndroidMediaSink.AudioFocusSession? = null

    @Synchronized
    fun audioFocus(context: android.content.Context): AndroidMediaSink.AudioFocusSession =
        audioFocusSession ?: AndroidMediaSink.createAudioFocus(context).also { audioFocusSession = it }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val handshakeTimeline: HandshakeTimeline,
        val activeScreenStreamTypes: MutableSet<Int>,
        val viewAreas: CarPlayViewAreas?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var handshakeTimeline: HandshakeTimeline? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()
    private var viewAreas: CarPlayViewAreas? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val timeline = handshakeTimeline ?: return null
        return Snapshot(currentController, currentSink, width, height, timeline, activeScreenStreamTypes, viewAreas)
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        handshakeTimeline: HandshakeTimeline,
        activeScreenStreamTypes: MutableSet<Int>,
        viewAreas: CarPlayViewAreas?,
    ) {
        this.viewAreas = viewAreas
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.handshakeTimeline = handshakeTimeline
        this.activeScreenStreamTypes = activeScreenStreamTypes
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
        handshakeTimeline = null
        activeScreenStreamTypes = mutableSetOf()
        viewAreas = null
    }
}
