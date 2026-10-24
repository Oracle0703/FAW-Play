package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
internal class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val phoneChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val speechAudioRouting: SpeechAudioRoutingState,
    private val onChannelChanged: (AudioChannel) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = AudioBacklog<AudioPacket>(AudioBacklogPolicy.forStream(format,
        advancedAudioChannelMapping, mediaBufferMillis))
    @Volatile private var running = true
    @Volatile private var started = false
    @Volatile private var drainDeadlineNs = 0L
    @Volatile private var immediatelyStopped = false
    private var decoderEndQueued = false
    private var decoderOutputEnded = false
    private var drainOutcome = "none"
    private var codec: MediaCodec? = null
    @Volatile private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var minimumStartBytes = 0
    private var trackCapacityBytes = 0
    private var adaptiveReserve: AdaptiveAudioReserve? = null
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
    @Volatile var hasSustainedOutput = false
        private set
    private var writtenFramesThisWindow = 0L
    private var callPcmEnergy = 0L
    private var callPcmSamples = 0L
    private var callPcmPeak = 0
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private var bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }
    private val streamStartedAt = android.os.SystemClock.uptimeMillis()
    val isRunning: Boolean get() = running && !isFinishing
    val isFinishing: Boolean get() = drainDeadlineNs != 0L && running
    val hasExited: Boolean get() = !thread.isAlive

    @Synchronized
    fun start() {
        if (started || !running) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!running || isFinishing) return
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample), AudioPacketDuration.nanos(format, rtp), rtp.size)) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio packet exceeded backlog budget")
                report("Audio: packet over budget audioType=${format.audioType}")
            }
        }
    }

    @Synchronized
    override fun close() {
        immediatelyStopped = true
        running = false
        // AudioTrack's blocking write is not required to observe Thread.interrupt(). Pausing and
        // flushing stops the audible buffer now; the worker owns final native release.
        track?.let { current ->
            runCatching { current.pause() }
            runCatching { current.flush() }
        }
        thread.interrupt()
    }

    @Synchronized
    fun finish() {
        if (!running || drainDeadlineNs != 0L) return
        if (mappedSelection().channel != AudioChannel.NAVIGATION) {
            close()
            return
        }
        // Do not join or interrupt: the worker owns the codec and completes queued sound.
        drainDeadlineNs = System.nanoTime() + NAVIGATION_DRAIN_TIMEOUT_NS
    }

    private fun canRender(): Boolean = running &&
        (drainDeadlineNs == 0L || System.nanoTime() < drainDeadlineNs)

    fun awaitClosed(timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === thread) return false
        thread.join(timeoutMillis)
        return !thread.isAlive
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
            if (format.codec != AudioCodecKind.LPCM && codec == null) {
                // Leave the renderer replaceable. A vendor codec can be temporarily
                // unavailable while the audio service is restarting; keeping this dead
                // instance in the sink would drop every later packet forever.
                running = false
                return
            }
            while (running && format.audioType == "speechrecognition" &&
                speechAudioRouting.route(streamStartedAt) == SpeechAudioRoute.WAIT) Thread.sleep(10)
            if (!running) return
            createTrack()
            if (track == null) {
                running = false
                return
            }
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                if (!canRender()) {
                    drainOutcome = "timeout"
                    break
                }
                refreshSpeechRoute()
                if (track == null) {
                    // A speech route switch can invalidate the native track. Let the
                    // next RTP packet replace this renderer instead of spinning silently.
                    running = false
                    break
                }
                // An incoming encoded burst cannot repair an underrun until it is decoded.
                // Pause/refill before writing it, not only after the input queue becomes empty.
                maintainPlaybackBuffer()
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                if (isFinishing && finishPlaybackIfReady()) break
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                running = false
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) {
                running = false
                reportFailure(error)
            }
            throw error
        } finally {
            running = false
            if (drainDeadlineNs != 0L) runCatching {
                report("Audio: drain audioType=${format.audioType} codec=${format.codec} " +
                    "outcome=${if (immediatelyStopped) "canceled" else drainOutcome} " +
                    "elapsedMs=${(System.nanoTime() - drainDeadlineNs + NAVIGATION_DRAIN_TIMEOUT_NS) / 1_000_000L} " +
                    "pendingPackets=${queue.size} decoderEnded=$decoderOutputEnded")
            }
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
        var plan = if (selection.channel == AudioChannel.NAVIGATION) {
            NavigationAudioBuffer.plan(format.sampleRate, format.channels, minBuffer)
        } else {
            MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
                format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        }
        bytesPerSecond = format.sampleRate * frameBytes
        adaptiveReserve = AdaptiveAudioReserve.forChannel(selection.channel, mediaBufferMillis)
        // Allocate the bounded recovery headroom once; raising a reserve must not recreate a live track.
        adaptiveReserve?.let { reserve ->
            val headroomMs = if (selection.channel == AudioChannel.MEDIA) 200 else 80
            plan = plan.copy(trackBufferBytes = maxOf(plan.trackBufferBytes,
                ((reserve.maximumMillis + headroomMs) * bytesPerSecond.toLong() / 1000).toInt()))
        }
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
        trackCapacityBytes = capacityBytes
        minimumStartBytes = plan.startBytes
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
        if (format.audioType == "speechrecognition")
            report("Audio: speech output channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "reason=${if (selection.channel == AudioChannel.MEDIA) "ongoing-music" else "voice-or-unknown"}")
        onChannelChanged(selection.channel)
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        // Call audio can share the legacy-stream path, which is what makes its level follow the
        // head unit's volume keys instead of sitting outside the volume model.
        AudioChannel.PHONE -> phoneChannel
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
        if (format.audioType == "speechrecognition") {
            val route = speechAudioRouting.route(streamStartedAt)
            if (route == SpeechAudioRoute.MEDIA || (route == SpeechAudioRoute.WAIT && mappedChannel == AudioChannel.MEDIA))
                return AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        }
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

    /** AudioAttributes are immutable: replace only the output track, retaining decoder and RTP. */
    private fun refreshSpeechRoute() {
        if (format.audioType != "speechrecognition" || track == null || mappedSelection().channel == mappedChannel) return
        val previous = mappedChannel
        logStatsIfDue(force = true)
        releaseTrack()
        playbackStarted = false
        prebufferBytes = 0
        bufferProgress = AudioBufferProgress(frameBytes)
        totalWrittenFrames = 0
        writtenFramesThisWindow = 0
        lastPlaybackHeadFrames = null
        statsLastUnderruns = 0
        statsWindowStartNs = 0
        lastPcmWriteNs = 0
        underrunsAtPlaybackStart = 0
        createTrack()
        requestAudioFocus()
        report("Audio: speech output changed previous=$previous channel=$mappedChannel")
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
            running = ::canRender,
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
        if (index < 0 || !canRender()) return
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
        while (canRender()) {
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderOutputEnded = true
                        return
                    }
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
        while (written < length && canRender()) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            // Bound graceful completion even if a vendor track is not consuming its buffer.
            val mode = if (isFinishing) AudioTrack.WRITE_NON_BLOCKING else AudioTrack.WRITE_BLOCKING
            val count = track.write(data, offset + written, writeLength, mode)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                // These errors invalidate the native track. Stop this renderer so the
                // next packet can create a fresh track after a transient route/HAL reset.
                running = false
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                if (isFinishing) {
                    Thread.sleep(AUDIO_POLL_MILLIS)
                    continue
                }
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            if (totalWrittenFrames + count / frameBytes >= format.sampleRate) hasSustainedOutput = true
            if (mappedChannel == AudioChannel.PHONE) {
                // Summarize accepted PCM only; retain no voice samples or recording.
                var index = offset + written
                val end = index + count
                while (index + 1 < end) {
                    val sample = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8))
                    callPcmEnergy += sample.toLong() * sample
                    callPcmSamples++
                    callPcmPeak = maxOf(callPcmPeak, kotlin.math.abs(sample))
                    index += 2
                }
            }
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
        val nowNs = System.nanoTime()
        if (playbackStarted && adaptiveReserve?.stable(nowNs) == true) updateReserveThreshold("stable")
        val recoverable = mappedChannel == AudioChannel.MEDIA || mappedChannel == AudioChannel.NAVIGATION
        if (bufferProgress.shouldRebuffer(recoverable && drainDeadlineNs == 0L, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            if (adaptiveReserve?.underrun(nowNs) == true) updateReserveThreshold("underrun")
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a blocking
            // write cannot fill the paused track before play() is called.
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
            runCatching { report("Audio: rebuffer channel=$mappedChannel codec=${format.codec} " +
                "retainedMs=${prebufferBytes * 1000L / bytesPerSecond.coerceAtLeast(1)} " +
                "targetMs=${startThresholdBytes * 1000L / bytesPerSecond.coerceAtLeast(1)} pendingPackets=${queue.size}") }
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    private fun updateReserveThreshold(reason: String) {
        val reserve = adaptiveReserve ?: return
        val desired = maxOf(minimumStartBytes,
            (bytesPerSecond.toLong() * reserve.targetMillis / 1000).toInt())
        startThresholdBytes = MediaAudioBuffer.startBytesFor(desired, trackCapacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        runCatching { report("Audio: reserve channel=$mappedChannel reason=$reason " +
            "targetMs=${startThresholdBytes * 1000L / bytesPerSecond.coerceAtLeast(1)} " +
            "minimumMs=${reserve.minimumMillis} maximumMs=${reserve.maximumMillis}") }
    }

    /** Runs only on the worker, after all submitted packets have been handled. */
    private fun finishPlaybackIfReady(): Boolean {
        if (!canRender() || queue.isNotEmpty()) return false
        val codec = codec
        if (codec != null && !decoderEndQueued) {
            val index = codec.dequeueInputBuffer(0)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                decoderEndQueued = true
            }
        }
        val track = track ?: return true
        // A final short phrase must play even when it never reaches the usual start level.
        if (!playbackStarted && prebufferBytes > 0) startPlayback(track)
        if ((codec == null || decoderOutputEnded) &&
            bufferProgress.queuedBytes(track.playbackHeadPosition) == 0L) {
            drainOutcome = "complete"
            return true
        }
        return false
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
            previous?.takeUnless { immediatelyStopped }?.let { (current - it) and 0xffff_ffffL }
        }
        // pause/flush may reset the head to zero. That is not a huge audio backlog or wrap.
        val queuedFrames = if (immediatelyStopped) null else currentTrack?.let {
            bufferProgress.queuedBytes(it.playbackHeadPosition) / frameBytes
        }
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
        if (mappedChannel == AudioChannel.PHONE) {
            currentTrack?.let { runCatching { audioFocusCoordinator.reportPhoneOutputState(it) } }
            runCatching {
                val device = currentTrack?.routedDevice
                // Bus labels identify the fixed vehicle output; never export another device's address.
                val bus = device?.takeIf { it.type == AudioDeviceInfo.TYPE_BUS }?.address
                    ?.takeIf { CALL_BUS_ADDRESS.matches(it) } ?: "unknown"
                val rms = if (callPcmSamples == 0L) 0 else kotlin.math.sqrt(callPcmEnergy.toDouble() / callPcmSamples).toInt()
                report("Audio: call output bus=$bus pcmRms=$rms pcmPeak=$callPcmPeak pcmSamples=$callPcmSamples ended=$force")
            }
            callPcmEnergy = 0L
            callPcmSamples = 0L
            callPcmPeak = 0
        }
        runCatching { report("Audio: backlog audioType=${format.audioType} " +
            "queuedMs=${queue.queuedMillis} oldestAgeMs=${queue.oldestAgeMillis} " +
            "budgetMs=${queue.policy.queuedMillis} ageLimitMs=${queue.policy.maxAgeMillis} " +
            "expiredOrOverflow=${queue.takeDiscarded()} ended=$force") }
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
        releaseTrack()
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
    }

    private fun releaseTrack() {
        abandonAudioFocus()
        val previous = track
        track = null
        mappedChannel = null
        trackAttributes = null
        if (previous != null) {
            runCatching { previous.pause() }
            runCatching { previous.flush() }
            runCatching { previous.release() }
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
        const val NAVIGATION_DRAIN_TIMEOUT_NS = 1_000_000_000L
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        val CALL_BUS_ADDRESS = Regex("(?i)BUS[0-9]{1,2}(?:_[A-Z0-9_]+)?")
    }
}
