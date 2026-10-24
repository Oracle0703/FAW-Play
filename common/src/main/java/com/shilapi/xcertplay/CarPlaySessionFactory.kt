package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayUltraProvisioning
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.AutunnOutputSettings
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessAddressRecovery
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File

/** Negotiated session settings retained independently of the Activity that first connected. */
internal data class CarPlayResumeConfiguration(
    val runtime: CarPlayRuntimeConfig,
    val airPlay: AirPlayConfig,
    val identity: AirPlayIdentity,
    val width: Int,
    val height: Int,
    val display: CarPlaySessionDisplay,
    val preferSoftwareHevcDecoder: Boolean,
    val advancedAudioChannelMapping: Boolean,
    val navigationStreamType: Int,
    val microphoneEnabled: Boolean,
    val audioCaptureDirectory: File? = null,
    /** Build handlers afresh for each connection; never retain a previous session's runtime. */
    val ultraProvisioning: (() -> AirPlayUltraProvisioning)? = null,
)

/** Builds the same native transport/media stack for visible startup and background resumption. */
internal object CarPlaySessionFactory {
    const val CH341_VENDOR_ID = 0x1a86
    const val CH341_PRODUCT_ID = 0x5512
    const val CARPLAY_USB_INTERFACE_NUMBER = 3
    data class Created(val controller: CarPlayController, val sink: AndroidMediaSink)

    fun create(
        context: Context,
        configuration: CarPlayResumeConfiguration,
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
        onScreenStreamActiveChanged: (Int, Boolean) -> Unit,
        diagnosticLog: SessionLogFile?,
        addressRecovery: WirelessAddressRecovery = WirelessAddressRecovery(),
        onAddressRecoveryChanged: () -> Unit = {},
        connectionCycle: Long = 0L,
    ): Created {
        val appContext = context.applicationContext
        val runtime = configuration.runtime
        val provisioning = configuration.ultraProvisioning?.invoke()
        val ultra = configuration.airPlay.cluster?.let { cluster ->
            provisioning?.let { it.createConfig(cluster, it.createRuntime()) }
        }
        val airPlay = if (ultra == null && configuration.airPlay.ultra == null) configuration.airPlay
            else configuration.airPlay.copy(ultra = ultra)
        AsyncDiagnosticLog.append(diagnosticLog, "Ultra: provisioned=${ultra != null} " +
            "ready=${ultra?.readiness?.ready == true} " +
            "mode=${ultra?.negotiationMode} negotiationAllowed=${ultra?.negotiationAllowed == true} " +
            "missing=${ultra?.readiness?.missingRequirements?.joinToString { it.name } ?: "not-provisioned"}")
        val sink = AndroidMediaSink(
            surface = null,
            videoWidth = airPlay.main.widthPixels,
            videoHeight = airPlay.main.heightPixels,
            videoSizes = airPlay.cluster?.let { mapOf(111 to (it.widthPixels to it.heightPixels)) }.orEmpty(),
            preferSoftwareHevcDecoder = configuration.preferSoftwareHevcDecoder,
            advancedAudioChannelMapping = configuration.advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(appContext),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(appContext),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(appContext),
            phoneChannel = AirPlayPersistence.loadPhoneAudioChannel(appContext),
            context = appContext,
            navigationStreamType = configuration.navigationStreamType,
            onScreenStreamActiveChanged = onScreenStreamActiveChanged,
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(appContext),
            onAudioDiagnostic = { AsyncDiagnosticLog.append(diagnosticLog, it) },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
        var location: Iap2LocationProvider? = null
        try {
            location = when {
                !runtime.locationReportingEnabled -> null
                runtime.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(appContext), AutunnNavigationOutputs.wheelSpeed(appContext),
                )
                else -> AndroidCarPlayLocationProvider(appContext)
            }
            val media = CarPlayMediaEngine(sink, configuration.microphoneEnabled, configuration.audioCaptureDirectory)
            val pairings = AirPlayPersistence.loadPairings(appContext) { id, key ->
                AirPlayPersistence.savePairing(appContext, id, key)
            }
            val controller = CarPlayController(
                context = appContext,
                config = runtime,
                airPlayConfig = airPlay,
                identity = configuration.identity,
                pairings = pairings,
                listener = listener,
                media = media,
                reportStatus = reportStatus,
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(appContext) },
                savePairRecord = { AirPlayPersistence.saveLockdownRecord(appContext, it) },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(appContext) },
                locationProvider = location,
                vehicleStatusProvider = if (AutunnOutputSettings.batteryToIphoneActive(appContext)) {
                    AutunnNavigationOutputs.batteryStatus(appContext)
                } else null,
                addressRecovery = addressRecovery,
                onAddressRecoveryChanged = onAddressRecoveryChanged,
                connectionCycle = connectionCycle,
            )
            sink.onNativeAudioFocusChanged = controller::nativeAudioFocusChanged
            return Created(controller, sink)
        } catch (error: Throwable) {
            runCatching { location?.stop() }
            runCatching { sink.close() }
            throw error
        }
    }
}
