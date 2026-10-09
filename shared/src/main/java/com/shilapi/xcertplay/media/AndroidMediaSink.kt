package com.shilapi.xcertplay.media

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(
        val channel: AudioChannel,
        val attributes: AudioAttributes,
        var navigationGain: Float = FULL_VOLUME,
        var navigationFade: VolumeShaper? = null,
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val automotive = context?.packageManager
        ?.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) == true
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private class FocusState {
        var request: AudioFocusRequest? = null
        var channel: AudioChannel? = null
        var attributes: AudioAttributes? = null
        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        var failures = 0
        var retryAt = 0L
        var generation = 0L
    }
    private val transientFocus = FocusState()
    private val mediaFocus = FocusState()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var closed = false
    private val retry = Runnable {
        synchronized(this) {
            if (!closed) refreshRequest()
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || (!automotive && channel == AudioChannel.NAVIGATION)) return
        active.putIfAbsent(track, Entry(channel, attributes))
        refreshNavigationVolume()
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        active.remove(track)?.let { entry ->
            runCatching { entry.navigationFade?.close() }
            refreshNavigationVolume()
            refreshRequest()
        }
    }

    @Synchronized
    fun close() {
        closed = true
        if (enabled && manager != null) handler.removeCallbacks(retry)
        active.values.forEach { runCatching { it.navigationFade?.close() } }
        active.clear()
        abandon(transientFocus)
        abandon(mediaFocus)
    }

    private fun abandon(state: FocusState) {
        state.generation++
        state.request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        state.request = null
        state.channel = null
        state.attributes = null
        state.failures = 0
        state.retryAt = 0
    }

    private fun refreshRequest() {
        // Keep the vehicle's music source underneath temporary navigation/call focus.
        if (automotive) refresh(mediaFocus, active.values.firstOrNull { it.channel == AudioChannel.MEDIA }, true)
        val primary = active.values.filter { !automotive || it.channel != AudioChannel.MEDIA }
            .maxByOrNull { it.channel.focusPriority() }
        refresh(transientFocus, primary, false)
        handler.removeCallbacks(retry)
        if (closed) return
        listOf(mediaFocus, transientFocus).filter {
            it.request != null && it.result == AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }.minOfOrNull { it.retryAt }?.let {
            handler.postDelayed(retry, (it - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1))
        }
    }

    private fun refresh(state: FocusState, entry: Entry?, media: Boolean) {
        if (entry == null || closed) { abandon(state); return }
        val same = state.request != null && state.channel == entry.channel && state.attributes == entry.attributes
        val now = android.os.SystemClock.uptimeMillis()
        if (same && (state.result != AudioManager.AUDIOFOCUS_REQUEST_FAILED || now < state.retryAt)) return
        if (!same) abandon(state)
        val gain = when (entry.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT, AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val generation = state.generation
        val next = state.request ?: AudioFocusRequest.Builder(gain)
            .setAudioAttributes(entry.attributes)
            .setOnAudioFocusChangeListener({ change ->
                synchronized(this) {
                    if (closed || generation != state.generation) return@synchronized
                    runCatching { report("Audio: focus change=$change channel=${entry.channel} activeTracks=${active.size}") }
                    // A granted request losing focus must not start a failed-request retry loop.
                    if (change == AudioManager.AUDIOFOCUS_GAIN) state.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!media && !automotive) when (change) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                        AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                    }
                }
            }, handler).build()
        state.request = next
        state.channel = entry.channel
        state.attributes = entry.attributes
        state.result = runCatching { manager?.requestAudioFocus(next) }.getOrNull()
            ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        if (state.result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            val delay = RETRY_DELAYS[state.failures.coerceAtMost(RETRY_DELAYS.lastIndex)]
            state.failures++
            state.retryAt = android.os.SystemClock.uptimeMillis() + delay
        } else {
            state.failures = 0
            state.retryAt = 0
        }
        val line = "Audio: focus requested channel=${entry.channel} gain=$gain granted=${state.result} activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun refreshNavigationVolume() {
        if (!automotive) return
        val gain = if (active.values.any { it.channel == AudioChannel.NAVIGATION }) NAVIGATION_MUSIC_GAIN else FULL_VOLUME
        active.forEach { (track, entry) ->
            if (entry.channel != AudioChannel.MEDIA || entry.navigationGain == gain) return@forEach
            val duration = if (gain < entry.navigationGain) NAVIGATION_FADE_DOWN_MILLIS else NAVIGATION_FADE_UP_MILLIS
            runCatching {
                val previous = entry.navigationFade
                val current = previous?.volume ?: FULL_VOLUME
                val curve = VolumeShaper.Configuration.Builder(VolumeShaper.Configuration.SCURVE_RAMP)
                    .scaleToStartVolume(current)
                    .scaleToEndVolume(gain)
                    .setDuration(duration)
                    .build()
                if (previous == null) {
                    entry.navigationFade = track.createVolumeShaper(curve).also { it.apply(VolumeShaper.Operation.PLAY) }
                } else {
                    // Join from the current gain when prompts arrive during a recovery fade.
                    previous.replace(curve, VolumeShaper.Operation.PLAY, true)
                }
                entry.navigationGain = gain
                val line = "Audio: navigation music transition target=$gain durationMs=$duration"
                Log.i(TAG, line)
                runCatching { report(line) }
            }.onFailure { error ->
                runCatching { report("Audio: navigation music transition unavailable ${error.javaClass.simpleName}") }
            }
        }
    }

    private fun setMediaVolume(volume: Float) {
        active.filterValues { it.channel == AudioChannel.MEDIA }.keys.forEach { track ->
            runCatching { track.setStereoVolume(volume, volume) }
        }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.PHONE -> 4
        AudioChannel.ASSISTANT -> 3
        AudioChannel.NAVIGATION -> 2
        AudioChannel.MEDIA -> 1
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000)
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
        const val NAVIGATION_MUSIC_GAIN = 0.75f
        const val NAVIGATION_FADE_DOWN_MILLIS = 400L
        const val NAVIGATION_FADE_UP_MILLIS = 800L
    }
}

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
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
) : MediaSink {
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    @Volatile private var diagnosticListener = onAudioDiagnostic
    private val reportAudio: (String) -> Unit = { diagnosticListener(it) }
    @Volatile private var sinkClosed = false
    fun setAudioDiagnosticListener(listener: (String) -> Unit) { diagnosticListener = listener }
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        reportAudio,
    )
    private val mixingOwners = mutableSetOf<Any>()
    init {
        if (audioFocusEnabled && appContext != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            HongqiNavigationMixing.recover(appContext, reportAudio)
    }
    override fun onCarPlaySessionActive(session: Any) {
        if (sinkClosed || !audioFocusEnabled || appContext == null || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        synchronized(mixingOwners) {
            mixingOwners.add(session)
            HongqiNavigationMixing.acquire(appContext, session, reportAudio)
        }
    }
    override fun onCarPlaySessionEnded(session: Any) {
        synchronized(mixingOwners) { mixingOwners.remove(session); HongqiNavigationMixing.release(session) }
    }
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    @Volatile private var radioDiagnosticSource: () -> String = { "wireless=unavailable" }
    override fun setWirelessDiagnosticSource(source: () -> String) { radioDiagnosticSource = source }
    private val resourceSampler = appContext?.let { PlaybackResourceSampler(it,
        decoders = { videoDecoders.size + synchronized(mirrorLock) { mirrorDecoders.size } },
        active = { videoDecoders.isNotEmpty() || audioRenderers.isNotEmpty() },
        report = { line -> reportAudio(line); runCatching { reportAudio("Playback radio ${radioDiagnosticSource()}") } }) }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        videoDecoder(type).configure(codec, codecData)
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val startedAt = System.nanoTime()
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            if (config.audioType == "telephony") enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) { MicrophoneUplink(config, reportAudio) }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
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

    override fun onMicrophoneStopped(id: AudioStreamId) {
        val startedAt = System.nanoTime()
        try {
            microphoneUplinks.remove(id)?.close()
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
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
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
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not release audio communication mode", error)
            }
        }
    }

    fun close() {
        sinkClosed = true
        radioDiagnosticSource = { "wireless=closed" }
        resourceSampler?.close()
        audioFocusCoordinator.close()
        synchronized(mixingOwners) { mixingOwners.forEach(HongqiNavigationMixing::release); mixingOwners.clear() }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    fun onSurfaceUpdated(type: Int) { videoDecoders[type]?.onTextureUpdated() }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            reportAudio,
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private var keyFrameWaitStartedNs = System.nanoTime()
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    fun onTextureUpdated() { stats.onTextureUpdated() }

    private fun run() {
        PlaybackResourceSampler.liveDecoderThreads.incrementAndGet()
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            stats.onQueueAge(System.nanoTime() - job.receivedNs)
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    keyFrameWaitStartedNs = System.nanoTime()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
            PlaybackResourceSampler.liveDecoderThreads.decrementAndGet()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        val rebuildStarted = System.nanoTime()
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        keyFrameWaitStartedNs = System.nanoTime()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        stats.onRebuild(System.nanoTime() - rebuildStarted)
        if (next != null) runCatching {
            next.setOnFrameRenderedListener({ codec, _, nanoTime ->
                if (running && decoder === codec) stats.onPresented(nanoTime)
            }, PlaybackResourceSampler.handler)
        }.onFailure { report("frame rendered callback unavailable ${it.javaClass.simpleName}") }
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val inputWaitStarted = System.nanoTime()
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        stats.onInputWait(System.nanoTime() - inputWaitStarted)
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            if (referenceChain.needsKeyFrame) stats.onKeyFrameWait(System.nanoTime() - keyFrameWaitStartedNs)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        keyFrameWaitStartedNs = System.nanoTime()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var inputRetries = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (error: SecurityException) {
                Log.w(TAG, "audio thread priority unavailable", error)
            }
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = if (selection.channel == AudioChannel.NAVIGATION) {
            NavigationAudioBuffer.plan(format.sampleRate, format.channels, minBuffer)
        } else {
            MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
                format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        }
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /** The coordinator decides whether navigation needs a vehicle volume context. */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        val index = AudioInputPump.acquire(
            running = { running },
            dequeue = {
                diagnosticStage = "decoder-input"
                codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            },
            drain = {
                inputRetries++
                drainCodec(codec)
                maintainPlaybackBuffer()
            },
        )
        if (index < 0 || !running) return
        val input = checkNotNull(codec.getInputBuffer(index)) { "Audio decoder input buffer unavailable" }
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    val copiedBytes = try {
                        val output = if (size > 0) codec.getOutputBuffer(index) else null
                        if (output == null) {
                            0
                        } else {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            size
                        }
                    } finally {
                        // PCM is owned by this worker now. Do not hold a decoder buffer while
                        // AudioTrack blocks: the decoder needs it to keep accepting input.
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (copiedBytes > 0) writePcm(pcm, 0, copiedBytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA || mappedChannel == AudioChannel.NAVIGATION, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            if (mappedChannel == AudioChannel.NAVIGATION) fadeApplied = false
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "inputRetriesTotal=$inputRetries " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
