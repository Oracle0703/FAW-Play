package com.shilapi.xcertplay.media

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    private val phoneChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    private val videoSizes: Map<Int, Pair<Int, Int>> = emptyMap(),
) : MediaSink {
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    @Volatile private var diagnosticListener = onAudioDiagnostic
    private val reportAudio: (String) -> Unit = { diagnosticListener(it) }
    @Volatile private var sinkClosed = false
    private val closeLock = Any()
    private var sinkResourcesReleased = false
    private var mediaStoppedNotificationPending = false
    fun setAudioDiagnosticListener(listener: (String) -> Unit) { diagnosticListener = listener }
    fun selectMediaAudioSource() { if (!sinkClosed) audioFocusCoordinator.selectMediaSource() }
    @Volatile var onNativeAudioFocusChanged: (Int) -> Unit = {}
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        reportAudio,
        onNativeMainAudioFocusChanged = { onNativeAudioFocusChanged(it) },
    )
    private val mixingOwners = mutableSetOf<Any>()
    private val speechAudioRouting = SpeechAudioRoutingState()
    private var audioContextSession: Any? = null
    private var mainAudioAllowed = true
    private val requestedMicrophones = mutableMapOf<AudioStreamId, MicrophoneConfig>()
    init {
        if (audioFocusEnabled && appContext != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            HongqiNavigationMixing.recover(appContext, reportAudio)
    }
    @Synchronized
    override fun onCarPlaySessionActive(session: Any) {
        if (audioContextSession !== session) {
            speechAudioRouting.reset()
            audioFocusCoordinator.setPhoneActive(false)
            audioContextSession = session
        }
        if (sinkClosed || !audioFocusEnabled || appContext == null || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        synchronized(mixingOwners) {
            mixingOwners.add(session)
            HongqiNavigationMixing.acquire(appContext, session, reportAudio)
        }
    }
    @Synchronized
    override fun onCarPlaySessionEnded(session: Any) {
        if (audioContextSession === session) {
            audioContextSession = null
            speechAudioRouting.reset()
            audioFocusCoordinator.setPhoneActive(false)
        }
        synchronized(mixingOwners) { mixingOwners.remove(session); HongqiNavigationMixing.release(session) }
    }
    @Synchronized
    override fun onPlaybackContext(session: Any, playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?) {
        if (audioContextSession !== session) return
        audioFocusCoordinator.setPhoneActive(phone == true)
        if (phone == true) stopFinishingAudio()
        if (speechAudioRouting.update(playback, phone, speech))
            reportAudio("Audio: speech context playing=${playback.playing} sourceKnown=${playback.sourceApp != null} " +
                "phone=$phone speech=$speech")
    }
    @Synchronized
    override fun onSiriRequested(session: Any) {
        if (audioContextSession === session) {
            stopFinishingAudio()
            speechAudioRouting.siriRequested()
            reportAudio("Audio: speech context siriRequested=true")
        }
    }
    @Synchronized
    override fun onResourceModes(session: Any, state: com.shilapi.xcertplay.airplay.AirPlayResourceModes.State) {
        if (!sinkClosed && audioContextSession === session) {
            val changed = mainAudioAllowed != state.mainAudioAllowed
            runCatching { reportAudio("Audio: resource mainAudio=${state.mainAudio ?: "unknown"} allowed=${state.mainAudioAllowed}") }
            mainAudioAllowed = state.mainAudioAllowed
            audioFocusCoordinator.setMainAudioAllowed(state.mainAudioAllowed)
            if (changed) {
                if (mainAudioAllowed) requestedMicrophones.toMap().forEach { (id, config) -> onMicrophoneStarted(id, config) }
                else microphoneUplinks.keys.toList().forEach { id ->
                    retireMicrophone(microphoneUplinks.remove(id))
                    restoreAudioMode(id)
                }
            }
        }
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    private val videoOwnershipLock = Any()
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    // SETUP/start owns admission. A late packet after end/stop must never recreate an audio worker.
    private val startedAudioStreams = mutableSetOf<AudioStreamId>()
    private val audioRestartCounts = mutableMapOf<AudioStreamId, Int>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    // A stream leaving its map has requested close; its worker may still own native resources.
    private val retiredVideoDecoders = ConcurrentHashMap.newKeySet<VideoDecoder>()
    private val retiredAudioRenderers = ConcurrentHashMap.newKeySet<AudioRenderer>()
    private val retiredMicrophones = ConcurrentHashMap.newKeySet<MicrophoneUplink>()

    private fun retireVideo(decoder: VideoDecoder?) {
        retiredVideoDecoders.removeAll { it.hasExited }
        if (decoder != null) { retiredVideoDecoders.add(decoder); decoder.close() }
    }

    private fun retireAudio(renderer: AudioRenderer?) {
        retiredAudioRenderers.removeAll { it.hasExited }
        if (renderer != null) { retiredAudioRenderers.add(renderer); renderer.close() }
    }

    private fun retireMicrophone(uplink: MicrophoneUplink?) {
        retiredMicrophones.removeAll { it.hasExited }
        if (uplink != null) { retiredMicrophones.add(uplink); uplink.close() }
    }
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Boolean>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = ConcurrentHashMap.newKeySet<Int>()
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = videoOwnershipLock
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Boolean) {
        synchronized(videoOwnershipLock) { if (!sinkClosed) videoRecoveryHandlers[type] = handler }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        synchronized(videoOwnershipLock) {
            if (sinkClosed) return
            if (videoDiagnosticHandlers.put(type, handler) !== handler) {
                // A SETUP owns its decoder callbacks, including first-frame evidence.
                retireVideo(videoDecoders.remove(type))
                mirrorDecoders.keys.filter { it.first == type }.forEach { retireVideo(mirrorDecoders.remove(it)) }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
    }

    /** Only output rendered on this currently attached primary Surface counts as visible. */
    fun hasRenderedVideoFrame(type: Int, surface: Surface): Boolean = synchronized(videoOwnershipLock) {
        !sinkClosed && (surfaces[type] ?: defaultSurface) === surface &&
            videoDecoders[type]?.hasRenderedSurface(surface) == true
    }

    private fun requestVideoRecovery(type: Int, completed: (Boolean) -> Unit): Boolean {
        val handler = videoRecoveryHandlers[type] ?: return false
        if (sinkClosed || !recoveryPending.add(type)) return false
        try {
            recoveryExecutor.execute {
                val sent = try { !sinkClosed && handler() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error); false }
                finally { recoveryPending.remove(type) }
                completed(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recoveryPending.remove(type)
            return false
        }
        return true
    }

    @Volatile private var radioDiagnosticSource: () -> String = { "wireless=unavailable" }
    override fun setWirelessDiagnosticSource(source: () -> String) { radioDiagnosticSource = source }
    private val resourceSampler = appContext?.let { PlaybackResourceSampler(it,
        decoders = { videoDecoders.size + synchronized(mirrorLock) { mirrorDecoders.size } },
        active = { videoDecoders.isNotEmpty() || audioRenderers.isNotEmpty() },
        report = { line -> reportAudio(line); runCatching { reportAudio("Playback radio ${radioDiagnosticSource()}") } }) }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (sinkClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.takeIf { it.isRunning }?.setSurface(surface)
                ?: videoDecoder(type)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) retireVideo(videoDecoders.remove(type))
            if (defaultSurface === surface) {
                defaultSurface = null
                videoDecoders.entries.filter { surfaces[it.key] == null }.forEach { (id, decoder) ->
                    videoDecoders.remove(id, decoder)
                    retireVideo(decoder)
                }
            }
        }
    }

    /** Each visible mirror owns a decoder; absent surfaces never block other consumers. */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(videoOwnershipLock) {
            if (sinkClosed) return
            if (mirrorSurfaces[id] === surface && mirrorDecoders[id]?.isRunning == true) return
            retireVideo(mirrorDecoders.remove(id))
            if (surface == null) mirrorSurfaces.remove(id) else mirrorSurfaces[id] = surface
            if (surface != null) mirrorDecoders(type)
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> {
        if (sinkClosed) return emptyList()
        return mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders[id]?.takeIf { it.isRunning } ?: newVideoDecoder(type, surface,
                " stream=$type mirror=${id.second}").also { decoder ->
                mirrorDecoders[id] = decoder
                lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            if (sinkClosed) return
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) { if (!sinkClosed) pendingVideoCodec[type] = codec }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (sinkClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            val data = codecData.copyOf()
            lastVideoConfig[type] = codec to data
            // Configure existing consumers; newly created ones pick up the retained configuration.
            videoDecoders[type]?.takeIf { it.isRunning }?.configure(codec, data) ?: videoDecoder(type)
            mirrorDecoders.filterKeys { it.first == type }.values.forEach { it.configure(codec, data) }
            mirrorDecoders(type)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        prepareVideoFrame(type)(naluBytes)
    }

    override fun prepareVideoFrame(type: Int): (ByteArray) -> Unit =
        prepareVideoFrame(type, System.nanoTime(), 0L)

    override fun prepareVideoFrame(type: Int, arrivalNanos: Long, senderNanos: Long): (ByteArray) -> Unit {
        val targets = synchronized(videoOwnershipLock) {
            if (sinkClosed) emptyList() else listOfNotNull(videoDecoder(type)) + mirrorDecoders(type)
        }
        // Backpressure belongs outside lifecycle locks. Captured consumers cannot switch generations.
        return { bytes -> targets.forEach { it.submit(bytes, arrivalNanos, senderNanos) } }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (sinkClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                retireVideo(videoDecoders.remove(type))
                mirrorDecoders.keys.filter { it.first == type }.forEach { retireVideo(mirrorDecoders.remove(it)) }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (sinkClosed) return
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    @Synchronized
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (sinkClosed) return
        startedAudioStreams.add(id)
        audioRestartCounts.remove(id)
        if (format.audioType == "telephony" || format.audioType == "speechrecognition") stopFinishingAudio()
        val renderer = audioRenderer(id, format) ?: return
        renderer.start()
        if (sinkClosed) return
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    @Synchronized
    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (sinkClosed || id !in startedAudioStreams) return
        audioRenderer(id, format)?.let { renderer ->
            // start() is idempotent, including when a failed native worker was just replaced.
            renderer.start()
            renderer.submit(rtp, sample)
        }
    }

    @Synchronized
    override fun onAudioStopped(id: AudioStreamId) {
        startedAudioStreams.remove(id)
        audioRestartCounts.remove(id)
        retireAudio(audioRenderers.remove(id))
        updateMediaAudio(id, false)
    }

    @Synchronized
    override fun onAudioEnded(id: AudioStreamId) {
        startedAudioStreams.remove(id)
        // Retain the renderer until replacement/disconnection can cancel its asynchronous tail.
        val voiceActive = audioRenderers.values.any {
            it.isRunning && (it.format.audioType == "telephony" || it.format.audioType == "speechrecognition")
        }
        if (speechAudioRouting.interruptsNavigationTail() || voiceActive) onAudioStopped(id)
        else audioRenderers[id]?.finish()
        updateMediaAudio(id, false)
    }

    private fun stopFinishingAudio() {
        audioRenderers.values.filter { it.isFinishing }.forEach { it.close() }
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active && !sinkClosed) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    @Synchronized
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (sinkClosed) return
        requestedMicrophones[id] = config
        if (!mainAudioAllowed) return
        val startedAt = System.nanoTime()
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            if (config.audioType == "telephony") enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) { MicrophoneUplink(config, reportAudio) }
            if (!uplink.start()) {
                if (microphoneUplinks.remove(id, uplink)) retireMicrophone(uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, reportAudio)
            onMicrophoneStopped(id)
        } finally {
            runCatching { reportAudio("Microphone: lifecycle start elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}") }
        }
    }

    @Synchronized
    override fun onMicrophoneStopped(id: AudioStreamId) {
        requestedMicrophones.remove(id)
        val startedAt = System.nanoTime()
        try {
            retireMicrophone(microphoneUplinks.remove(id))
        } finally {
            restoreAudioMode(id)
            runCatching { reportAudio("Microphone: lifecycle stop elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}") }
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
            reportCommunicationMode("enter", manager)
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?, strict: Boolean = false) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            try {
                // setMode is owned per caller. Replaying the previous global mode
                // can adopt the OEM Bluetooth call's mode after that call has ended.
                // MODE_NORMAL releases our request and lets Android select any
                // remaining owner's mode.
                manager.mode = AudioManager.MODE_NORMAL
                communicationModeStream = null
                Log.i("xcertplay-usb", "audio communication mode released; current=${manager.mode}")
                reportCommunicationMode("release", manager)
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not release audio communication mode", error)
                if (strict) throw error
            }
        }
    }

    private fun reportCommunicationMode(action: String, manager: AudioManager) {
        runCatching { reportAudio("Audio: communication mode action=$action current=${manager.mode} " +
            "speakerphone=${manager.isSpeakerphoneOn} bluetoothSco=${manager.isBluetoothScoOn} " +
            "voiceVolume=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} " +
            "voiceMaxVolume=${manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
            "voiceMuted=${manager.isStreamMute(AudioManager.STREAM_VOICE_CALL)}") }
    }

    fun close() = synchronized(closeLock) {
        if (sinkResourcesReleased) return@synchronized
        // Stop admission permanently. A failed cleanup can be retried without allowing old RTP
        // packets to create a new AudioTrack or microphone while that retry is pending.
        synchronized(this) {
            sinkClosed = true; startedAudioStreams.clear(); audioRestartCounts.clear(); requestedMicrophones.clear()
        }
        val failures = mutableListOf<Pair<String, Throwable>>()
        fun release(name: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (error: Throwable) {
            failures.add(name to error)
            false
        }
        // Stop sound before waiting on decoders, callback handlers or discovery teardown.
        audioRenderers.values.forEach { renderer -> release("audio stop") { renderer.close() } }
        microphoneUplinks.entries.toList().forEach { (id, uplink) ->
            if (release("microphone") {
                uplink.close()
                check(uplink.awaitClosed(MEDIA_CLOSE_TIMEOUT_MILLIS)) { "Microphone worker is still running" }
            }) microphoneUplinks.remove(id, uplink)
        }
        audioRenderers.entries.toList().forEach { (id, renderer) ->
            if (release("audio worker") {
                renderer.close()
                check(renderer.awaitClosed(MEDIA_CLOSE_TIMEOUT_MILLIS)) { "Audio worker is still running" }
            }) audioRenderers.remove(id, renderer)
        }
        retiredMicrophones.toList().forEach { uplink ->
            if (release("retiring microphone") {
                uplink.close()
                check(uplink.awaitClosed(MEDIA_CLOSE_TIMEOUT_MILLIS)) { "Retiring microphone worker is still running" }
            }) retiredMicrophones.remove(uplink)
        }
        retiredAudioRenderers.toList().forEach { renderer ->
            if (release("retiring audio worker") {
                renderer.close()
                check(renderer.awaitClosed(MEDIA_CLOSE_TIMEOUT_MILLIS)) { "Retiring audio worker is still running" }
            }) retiredAudioRenderers.remove(renderer)
        }
        release("audio focus") { audioFocusCoordinator.close() }
        release("audio mode") { restoreAudioMode(null, strict = true) }
        synchronized(videoOwnershipLock) {
            videoDecoders.entries.toList().forEach { (type, decoder) ->
                if (release("video decoder") {
                    decoder.close()
                    check(decoder.awaitRelease(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MEDIA_CLOSE_TIMEOUT_MILLIS))) {
                        "Video worker is still running"
                    }
                }) videoDecoders.remove(type, decoder)
            }
            mirrorDecoders.entries.toList().forEach { (key, decoder) ->
                if (release("mirror decoder") {
                    decoder.close()
                    check(decoder.awaitRelease(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MEDIA_CLOSE_TIMEOUT_MILLIS))) {
                        "Mirror video worker is still running"
                    }
                }) mirrorDecoders.remove(key)
            }
            retiredVideoDecoders.toList().forEach { decoder ->
                if (release("retiring video decoder") {
                    decoder.close()
                    check(decoder.awaitRelease(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MEDIA_CLOSE_TIMEOUT_MILLIS))) {
                        "Retiring video worker is still running"
                    }
                }) retiredVideoDecoders.remove(decoder)
            }
            mirrorSurfaces.clear()
            surfaces.clear()
            defaultSurface = null
            lastVideoConfig.clear()
            pendingVideoCodec.clear()
            videoRecoveryHandlers.clear()
            videoDiagnosticHandlers.clear()
        }
        radioDiagnosticSource = { "wireless=closed" }
        release("resource sampler") { resourceSampler?.close() }
        synchronized(mixingOwners) {
            mixingOwners.toList().forEach { owner ->
                if (release("navigation mixing") { HongqiNavigationMixing.release(owner) }) mixingOwners.remove(owner)
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.toList().forEach { type ->
                if (release("screen state callback") { screenStreamActiveChanged?.invoke(type, false) }) activeScreenTypes.remove(type)
            }
            if (activeScreenTypes.isEmpty()) screenStreamActiveChanged = null
        }
        release("video recovery worker") {
            recoveryExecutor.shutdownNow()
            check(recoveryExecutor.awaitTermination(MEDIA_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Video recovery worker is still running"
            }
        }
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) mediaStoppedNotificationPending = true
        if (mediaStoppedNotificationPending && release("media state callback") { onMediaAudioChanged(false) }) {
            mediaStoppedNotificationPending = false
        }
        if (failures.isNotEmpty()) {
            throw IOException("Media resources are not fully released: ${failures.joinToString { it.first }}",
                failures.first().second).also { error -> failures.drop(1).forEach { error.addSuppressed(it.second) } }
        }
        sinkResourcesReleased = true
    }

    private companion object { const val MEDIA_CLOSE_TIMEOUT_MILLIS = 2_000L }

    /** Caller holds videoOwnershipLock. Only the main screen may wait for its startup Surface. */
    private fun videoDecoder(type: Int): VideoDecoder? {
        if (sinkClosed) return null
        val surface = surfaces[type] ?: defaultSurface
        if (type != 110 && surface == null) return null
        return videoDecoders[type]?.takeIf { it.isRunning } ?: newVideoDecoder(type, surface).also { decoder ->
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
        }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null): VideoDecoder {
        val diagnosticHandler = videoDiagnosticHandlers[type]
        return VideoDecoder(
            type,
            surface,
            videoSizes[type]?.first ?: videoWidth,
            videoSizes[type]?.second ?: videoHeight,
            preferSoftwareHevcDecoder,
            requestKeyFrame = { completed ->
                !sinkClosed && videoDiagnosticHandlers[type] === diagnosticHandler &&
                    requestVideoRecovery(type, completed)
            },
            report = { message ->
                if (!sinkClosed && videoDiagnosticHandlers[type] === diagnosticHandler) {
                    // A mirror can display first; it cannot prove the primary output is visible.
                    diagnosticHandler?.invoke(if (statsLabel != null && message == "first frame rendered")
                        "mirror first frame rendered" else message)
                }
            },
            statsLabel = statsLabel,
            context = appContext,
        )
    }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer? {
        if (sinkClosed) return null
        val existing = audioRenderers[id]
        if (existing?.format == format && existing.isRunning) return existing
        if (existing != null && !existing.isFinishing && id in startedAudioStreams) {
            val failures = if (existing.hasSustainedOutput) 0 else audioRestartCounts[id] ?: 0
            if (failures >= 2) {
                if (failures == 2) reportAudio("Audio: recovery exhausted type=${id.type}; awaiting new stream setup")
                audioRestartCounts[id] = 3
                return null
            }
            audioRestartCounts[id] = failures + 1
        }
        retireAudio(existing)
        lateinit var renderer: AudioRenderer
        renderer = AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            phoneChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            reportAudio,
            speechAudioRouting,
            onChannelChanged = { channel ->
                if (audioRenderers[id] === renderer) updateMediaAudio(id, channel == AudioChannel.MEDIA)
            },
        )
        return renderer.also { audioRenderers[id] = it }
    }
}
