package com.shilapi.xcertplay

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.AutunnOutputSettings
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.json.JSONArray
import org.json.JSONObject

/** Retains the last negotiated display, while rebuilding identity and runtime settings on recovery. */
internal object CarPlayConfigurationStore {
    private const val PREFS = "faw_session_display"
    private const val VERSION = 1

    /** Repeated foreground readiness notifications are not new connection parameters. */
    class StartupConfiguration(private val a: CarPlayResumeConfiguration) {
        // Callers can retain these arrays/lists. Remember the submitted contents, so a later
        // mutation of the caller's instance is still a real change on its next submission.
        private val privateKey = a.identity.privateKey.copyOf()
        private val publicKey = a.identity.publicKey.copyOf()
        private val hostMac = a.runtime.hostMac.copyOf()
        private val iphoneDevices = a.runtime.iphoneDevices.toList()
        private val ch341Devices = a.runtime.ch341Devices.toList()
        private val airPlay = a.airPlay.copy(icons = emptyList())
        private val icons = a.airPlay.icons.map { it.copy(data = it.data.copyOf()) }

        fun matches(b: CarPlayResumeConfiguration): Boolean {
            // Runtime is deliberately not a data class, and the identity/icon/MAC arrays use
            // reference equality in their enclosing data classes. Compare their contents without
            // serializing credentials or reducing trust material to a collision-prone hash.
            val x = a.runtime
            val y = b.runtime
            return a.width == b.width && a.height == b.height && a.display == b.display &&
                a.preferSoftwareHevcDecoder == b.preferSoftwareHevcDecoder &&
                a.advancedAudioChannelMapping == b.advancedAudioChannelMapping &&
                a.navigationStreamType == b.navigationStreamType &&
                a.microphoneEnabled == b.microphoneEnabled && a.audioCaptureDirectory == b.audioCaptureDirectory &&
                a.identity.pairingId == b.identity.pairingId &&
                privateKey.contentEquals(b.identity.privateKey) &&
                publicKey.contentEquals(b.identity.publicKey) &&
                airPlay == b.airPlay.copy(icons = emptyList()) &&
                icons.size == b.airPlay.icons.size &&
                icons.zip(b.airPlay.icons).all { (left, right) ->
                    left.widthPixels == right.widthPixels && left.heightPixels == right.heightPixels &&
                        left.data.contentEquals(right.data)
                } &&
                iphoneDevices == y.iphoneDevices && x.mfiTarget == y.mfiTarget &&
                ch341Devices == y.ch341Devices && x.ch341MfiResetGpio == y.ch341MfiResetGpio &&
                x.linuxI2cPath == y.linuxI2cPath && x.remoteMfiServer == y.remoteMfiServer &&
                x.remoteMfiToken == y.remoteMfiToken && hostMac.contentEquals(y.hostMac) &&
                x.linkLocal == y.linkLocal &&
                x.identification.copy(wireless = null) == y.identification.copy(wireless = null) &&
                x.identification.wireless?.bluetoothMac == y.identification.wireless?.bluetoothMac &&
                x.identification.wireless?.ssid == y.identification.wireless?.ssid &&
                x.availableCurrentMilliAmps == y.availableCurrentMilliAmps &&
                x.label == y.label && x.hostName == y.hostName && x.transport == y.transport &&
                x.wirelessHotspotMode == y.wirelessHotspotMode &&
                x.manualHotspotSsid == y.manualHotspotSsid &&
                x.manualHotspotPassphrase == y.manualHotspotPassphrase &&
                x.manualHotspotBand == y.manualHotspotBand &&
                x.manualHotspotChannel == y.manualHotspotChannel &&
                x.manualHotspotSecurity == y.manualHotspotSecurity &&
                x.wirelessBluetoothDeviceAddress == y.wirelessBluetoothDeviceAddress &&
                x.locationReportingEnabled == y.locationReportingEnabled &&
                x.wifiP2pPreferredChannel == y.wifiP2pPreferredChannel &&
                x.existingWifiSsid == y.existingWifiSsid && x.existingWifiPassphrase == y.existingWifiPassphrase
        }
    }

    fun save(context: Context, config: CarPlayResumeConfiguration) {
        val a = config.airPlay
        val d = config.display
        val json = JSONObject().put("version", VERSION)
            .put("width", config.width).put("height", config.height)
            .put("display", JSONArray(listOf(d.width, d.height, d.rotation,
                d.hideTopBar, d.hideBottomBar, d.windowWidth, d.windowHeight)))
            .put("main", encodeDisplay(a.main))
            .put("cluster", a.cluster?.let(::encodeDisplay))
            .put("icons", JSONArray().also { icons -> a.icons.forEach { icon ->
                icons.put(JSONObject().put("w", icon.widthPixels).put("h", icon.heightPixels)
                    .put("data", Base64.encodeToString(icon.data, Base64.NO_WRAP)))
            } })
            .put("source", a.sourceVersion).put("port", a.port)
            .put("sampleRate", a.entertainmentSampleRate).put("disableAudio", a.disableAudioOutput)
            .put("fastPeer", a.fastPeerDetection).put("receiverCluster", a.receiverManagedClusterUi)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("configuration", json.toString()).apply()
    }

    fun load(context: Context, preparedMfiTarget: MfiTarget? = null): CarPlayResumeConfiguration? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("configuration", null) ?: return null
        return try {
            val j = JSONObject(text)
            require(j.getInt("version") == VERSION)
            val width = j.getInt("width"); val height = j.getInt("height")
            require(width in 1..8192 && height in 1..8192)
            val d = j.getJSONArray("display")
            val display = CarPlaySessionDisplay(d.getInt(0), d.getInt(1), d.getInt(2),
                d.getBoolean(3), d.getBoolean(4), d.getInt(5), d.getInt(6))
            val identity = AirPlayPersistence.loadIdentity(context)
            val id = DiPlayBootstrap.deviceId(identity)
            val runtime = CarPlayRuntimeSettings.load(context, identity)
            val target = runtime.mfiTarget
            val manufacturer = runtime.identification.manufacturer
            val model = runtime.identification.modelIdentifier
            val icons = j.getJSONArray("icons")
            val airPlay = AirPlayConfig("Autunn Play", id, DiPlayBluetooth.localAddress(context) ?: id,
                j.getString("source"), decodeDisplay(j.getJSONObject("main")),
                cluster = j.optJSONObject("cluster")?.let(::decodeDisplay)
                    ?.takeIf { AirPlayPersistence.loadClusterMapEnabled(context) },
                rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
                port = j.getInt("port"), entertainmentSampleRate = j.getInt("sampleRate"),
                hevc = AirPlayPersistence.loadHevcEnabled(context),
                disableAudioOutput = j.getBoolean("disableAudio"),
                microphone = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
                manufacturer = manufacturer, model = model, oemLabel = AirPlayPersistence.loadOemLabel(context),
                icons = (0 until icons.length()).map { index -> icons.getJSONObject(index).let {
                    AirPlayIcon(it.getInt("w"), it.getInt("h"), Base64.decode(it.getString("data"), Base64.NO_WRAP))
                } },
                videoInCar = AutunnOutputSettings.videoWhileParkedActive(context),
                fastPeerDetection = j.getBoolean("fastPeer"),
                receiverManagedClusterUi = j.getBoolean("receiverCluster"),
            )
            // An in-process configuration has already prepared this target. A changed target
            // or cold service recovery still validates its authentication assets here.
            if (target != preparedMfiTarget) DiPlayBootstrap.ensure(context, target)
            CarPlayResumeConfiguration(runtime, airPlay, identity, width, height, display,
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context),
                AirPlayPersistence.loadAdvancedAudioChannelMapping(context),
                AirPlayPersistence.loadNavigationStreamType(context), airPlay.microphone)
        } catch (error: Exception) {
            CarPlayBackgroundSession.recordUiDiagnostic("saved display unavailable=${error.javaClass.simpleName}; waiting for foreground configuration")
            null
        }
    }

    private fun encodeInsets(v: AirPlayInsets?): JSONObject? = v?.let {
        JSONObject().put("t", it.top).put("b", it.bottom).put("l", it.left).put("r", it.right)
    }
    private fun decodeInsets(v: JSONObject?): AirPlayInsets? = v?.let {
        AirPlayInsets(it.getInt("t"), it.getInt("b"), it.getInt("l"), it.getInt("r"))
    }
    private fun encodeDisplay(v: AirPlayDisplayConfig): JSONObject = JSONObject()
        .put("w", v.widthPixels).put("h", v.heightPixels)
        .put("mmW", v.widthPhysicalMm).put("mmH", v.heightPhysicalMm).put("fps", v.fps)
        .put("input", v.primaryInputDevice).put("view", encodeInsets(v.viewArea))
        .put("safe", encodeInsets(v.safeArea)).put("outside", v.safeAreaDrawOutside)
        .put("url", v.initialUrl).put("features", v.features)
    private fun decodeDisplay(j: JSONObject): AirPlayDisplayConfig {
        val w = j.getInt("w"); val h = j.getInt("h")
        require(w in 1..8192 && h in 1..8192)
        return AirPlayDisplayConfig(w, h,
            widthPhysicalMm = if (j.has("mmW")) j.getInt("mmW") else null,
            heightPhysicalMm = if (j.has("mmH")) j.getInt("mmH") else null,
            fps = j.getInt("fps"), primaryInputDevice = j.getInt("input"),
            viewArea = decodeInsets(j.optJSONObject("view")), safeArea = decodeInsets(j.optJSONObject("safe")),
            safeAreaDrawOutside = if (j.has("outside")) j.getBoolean("outside") else null,
            initialUrl = if (j.has("url")) j.getString("url") else null,
            features = if (j.has("features")) j.getInt("features") else null)
    }
}
