package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun setWirelessDiagnosticSource(source: () -> String) {}
    fun onCarPlaySessionActive(session: Any) {}
    fun onCarPlaySessionEnded(session: Any) {}
    fun onPlaybackContext(session: Any, playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?) {}
    fun onSiriRequested(session: Any) {}
    fun onResourceModes(session: Any, state: AirPlayResourceModes.State) {}
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** Capture the current decoder under the lifecycle lock; submit with backpressure outside it. */
    fun prepareVideoFrame(type: Int): ((ByteArray) -> Unit)? = null
    fun prepareVideoFrame(type: Int, arrivalNanos: Long, senderNanos: Long): ((ByteArray) -> Unit)? = prepareVideoFrame(type)
    fun setVideoRecoveryHandler(type: Int, handler: () -> Boolean) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    /** No more packets for this stream. The sink may finish a short navigation tail asynchronously. */
    fun onAudioEnded(id: AudioStreamId) = onAudioStopped(id)
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    override fun setWirelessDiagnosticSource(source: () -> String) { sink.setWirelessDiagnosticSource(source) }
    private var contextSession: AirPlaySession? = null
    @Synchronized
    override fun onSessionActive(session: AirPlaySession) {
        val replacement = contextSession != null && contextSession !== session
        if (replacement) {
            val old = pendingMicrophone.keys.filter { it.session !== session }
            old.forEach { key ->
                sink.onMicrophoneStopped(AudioStreamId(key.type, key.audioType))
                pendingMicrophone.remove(key)
            }
        }
        contextSession = session
        sink.onCarPlaySessionActive(session)
        sink.onResourceModes(session, session.resourceModes.state)
        // A replacement's stream may have started before its RECORD.
        if (replacement) pendingMicrophone.filterKeys { it.session === session && (audioMeta[it]?.originNs ?: 0L) != 0L }
            .forEach { (key, config) -> sink.onMicrophoneStarted(AudioStreamId(key.type, key.audioType), config) }
    }
    @Synchronized
    override fun onPlaybackContext(session: AirPlaySession, playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?) {
        if (contextSession === session) sink.onPlaybackContext(session, playback, phone, speech)
    }
    @Synchronized
    override fun onSiriRequested(session: AirPlaySession) {
        if (contextSession === session) sink.onSiriRequested(session)
    }

    @Synchronized
    override fun onResourceModes(session: AirPlaySession, state: AirPlayResourceModes.State) {
        if (contextSession === session) sink.onResourceModes(session, state)
    }

    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (AirPlaySession, BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    // Video ownership must not wait behind a microphone/HAL start or stop on the audio monitor.
    private val screenOwnershipLock = Any()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    // Keep ownership after the receiver stops so disconnect/replacement can still cancel a tail.
    private val endedAudio = mutableSetOf<StreamKey>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    @Volatile private var iapTunnelHandler: ((AirPlaySession, BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((AirPlaySession, BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    @Synchronized
    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        if (session.isClosed()) return null
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, { session.logDebug("screenType=$type $it") }, session.fastPeerDetection)
        synchronized(screenOwnershipLock) {
            // Revoke the previous producer before installing this stream's decoder callbacks.
            streams.keys.filter { it.type == type }.forEach { previous -> streams.remove(previous)?.close() }
        }
        session.videoSourceStarted(type, screen)
        sink.setVideoDiagnosticHandler(type) {
            // Decoder callbacks may arrive after a stream was replaced or its session retired.
            if (it == "first frame rendered") synchronized(screenOwnershipLock) {
                if (streams[streamKey] === screen && !session.isClosed()) session.videoFrameRendered(type, screen)
            }
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (streams[streamKey] === screen) {
                // The cluster asks for a keyframe of its own screen; the plain command is the main screen's.
                val command = if (type == STREAM_TYPE_ALT_SCREEN) {
                    mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID))
                } else {
                    mainScreenKeyFrameCommand()
                }
                val sent = session.sendCommand(command)
                session.logDebug("Video recovery: requested keyframe sent=$sent")
                sent
            } else false
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                private var videoCodec = VideoCodec.H264
                private var primaryPictureReceived = false
                override fun onCodec(codec: VideoCodec): Unit = synchronized(screenOwnershipLock) {
                    if (streams[streamKey] === screen && !session.isClosed()) {
                        videoCodec = codec
                        sink.onVideoCodec(type, codec)
                    }
                }
                override fun onConfig(codecData: ByteArray): Unit = synchronized(screenOwnershipLock) {
                    if (streams[streamKey] === screen && !session.isClosed()) sink.onVideoConfig(type, codecData)
                }
                override fun onFrame(naluBytes: ByteArray) = onFrame(naluBytes, 0L, System.nanoTime())
                override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
                    val submit = synchronized(screenOwnershipLock) {
                        if (streams[streamKey] !== screen || session.isClosed()) return
                        if (type == STREAM_TYPE_ALT_SCREEN) {
                            val progress = session.clusterContentProgress()
                            if (progress?.requestWritten == true && progress.shown && !progress.validVideoReceived &&
                                ScreenCodec.hasPictureAccessUnit(videoCodec, naluBytes)) session.videoFrameReceived(type, screen)
                        } else if (type == STREAM_TYPE_MAIN_SCREEN && !primaryPictureReceived &&
                            ScreenCodec.hasPictureAccessUnit(videoCodec, naluBytes)) {
                            primaryPictureReceived = true
                            session.videoFrameReceived(type, screen)
                        }
                        session.mediaProgress()
                        sink.prepareVideoFrame(type, arrivalNanos, senderNanos) ?: run {
                            sink.onVideoFrame(type, naluBytes)
                            return
                        }
                    }
                    submit(naluBytes)
                }
                override fun onClosed(cause: Throwable?) {
                    val expectedMainEnd = type == STREAM_TYPE_MAIN_SCREEN && cause == null &&
                        session.mainScreenEndExpected(screen)
                    if (type == STREAM_TYPE_ALT_SCREEN || expectedMainEnd) {
                        synchronized(screenOwnershipLock) {
                            // A replaced stream may still have an EOF callback in flight.
                            if (!streams.remove(streamKey, screen)) return
                            val ended = "screen stream ended type=$type error=${cause?.javaClass?.simpleName ?: "none"} reason=${cause?.message ?: "peer EOF"}"
                            Log.w(TAG, ended)
                            session.logDebug(ended)
                            if (expectedMainEnd) {
                                session.mainScreenSourceEnded(screen)
                                session.logDebug("Main screen handed back to vehicle; keeping CarPlay session")
                            }
                            screen.close()
                            if (streams.keys.none { it.type == type && isScreenStreamType(it.type) }) {
                                sink.onScreenStreamActive(type, false)
                            }
                        }
                        return
                    }
                    // Claim this stream without waiting for a busy sink. Keep its cleanup
                    // handle in the map so session teardown still stops the main decoder.
                    if (!streams.replace(streamKey, screen, Closeable { screen.close() })) return
                    val ended = "screen stream ended type=$type error=${cause?.javaClass?.simpleName ?: "none"} reason=${cause?.message ?: "peer EOF"}"
                    Log.w(TAG, ended)
                    session.logDebug(ended)
                    // Notify disconnection before touching the potentially busy media sink.
                    session.close()
                }
            },
        )
        synchronized(screenOwnershipLock) {
            streams[streamKey] = screen
            sink.onScreenStreamActive(type, true)
        }
        return port
    }

    @Synchronized
    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        if (session.isClosed()) return null
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        endedAudio.removeAll { it.type == type && it.audioType == audioType }
        streams.keys.filter { it.type == type && it.audioType == audioType }.forEach { previous ->
            streams.remove(previous)?.close()
            audioMeta.remove(previous)
            audioCaptures.remove(previous)?.close()
            if (pendingMicrophone.remove(previous) != null) sink.onMicrophoneStopped(streamId)
        }
        sink.onAudioStopped(streamId)

        val key = outputKey(session, stream) ?: return null
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (microphone != null) pendingMicrophone[streamKey] = microphone

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val audio = AudioStream(key, type, session::logDebug, format.audioType)
        // Publish ownership before listen can deliver its first packet. Setup, callbacks and
        // teardown share this monitor so a late callback cannot restart a closed microphone.
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        val (dataPort, controlPort) = try { audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int): Unit = synchronized(this@CarPlayMediaEngine) {
                    if (streams[streamKey] !== audio) return@synchronized
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    // Graceful TEARDOWN may precede the receiver's first callback. Its final
                    // accepted audio can drain, but it must not restart a stopped microphone.
                    pendingMicrophone[streamKey]?.let { sink.onMicrophoneStarted(streamId, it) }
                }

                override fun onRtp(rtp: ByteArray, sample: Int) =
                    synchronized(this@CarPlayMediaEngine) {
                        if (streams[streamKey] === audio && !session.isClosed()) {
                            session.mediaProgress()
                            sink.onAudioRtp(streamId, format, rtp, sample)
                        }
                    }

                override fun onEnded(): Unit = synchronized(this@CarPlayMediaEngine) {
                    if (streams[streamKey] !== audio || session.isClosed()) return@synchronized
                    streams.remove(streamKey, audio)
                    audioMeta.remove(streamKey)
                    audioCaptures.remove(streamKey)?.close()
                    val hasSuccessor = streams.keys.any { it.type == type && it.audioType == audioType }
                    if (!hasSuccessor) {
                        endedAudio.add(streamKey)
                        sink.onAudioEnded(streamId)
                    }
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    val current = synchronized(this@CarPlayMediaEngine) { streams[streamKey] === audio }
                    // Capture owns its close race and bounded writer. No disk I/O under the engine lock.
                    if (current) capture?.record(wire, rtp, sample, error)
                }
            },
        ) } catch (error: Exception) {
            streams.remove(streamKey, audio)
            audio.close()
            audioMeta.remove(streamKey)
            pendingMicrophone.remove(streamKey)
            audioCaptures.remove(streamKey)?.close()
            throw error
        }
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    @Synchronized
    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (session.isClosed()) return null
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (session.videoInCar) videoDataStream(session, uuid, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && (stream["controlType"] as? Number)?.toInt() == 1) {
            val streamId = session.allocateDataStreamId()
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to streamId)
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VideoSettingsChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY)) { session.logDebug(it) }
        val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
        videoSettingsChannels.put(session, channel)?.close()
        session.logTrace("video settings stream listening port=$port")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to VIDEO_SETTINGS_STREAM_ID, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(session, pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.values.toList()
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)
    }

    @Synchronized
    override fun onTeardown(session: AirPlaySession, type: Int) {
        teardownStream(session, type, drainAudio = true)
    }

    @Synchronized
    override fun onTeardown(session: AirPlaySession, types: List<Int>?) {
        if (types == null) stopEndedAudio(session)
        val ending = types ?: (session.activeStreams + streams.keys.filter { it.session === session }.map { it.type }).toList()
        ending.forEach { teardownStream(session, it, drainAudio = types != null) }
    }

    private fun teardownStream(session: AirPlaySession, type: Int, drainAudio: Boolean) {
        if (isScreenStreamType(type)) {
            synchronized(screenOwnershipLock) {
                streams.remove(StreamKey(session, type))?.close()
                if (streams.keys.none { it.type == type && isScreenStreamType(it.type) }) {
                    sink.onScreenStreamActive(type, false)
                }
            }
            return
        }
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        tornDown.forEach { key ->
            val streamId = AudioStreamId(key.type, key.audioType)
            val current = streams[key]
            if (drainAudio && !session.isClosed() && current is AudioStream && audioMeta.containsKey(key)) {
                val hasSuccessor = streams.keys.any { it != key && it.type == key.type && it.audioType == key.audioType }
                if (pendingMicrophone.remove(key) != null && !hasSuccessor) sink.onMicrophoneStopped(streamId)
                // Keep ownership until the RX thread drains its bounded sorting queue and
                // reports onEnded. Never join that thread while holding this monitor.
                current.finish()
                return@forEach
            }
            // Invalidate and stop receive first; callbacks already inside this monitor have
            // completed, and callbacks waiting for it will see that they no longer own the ID.
            streams.remove(key)?.close()
            val hasSuccessor = streams.keys.any { it.type == key.type && it.audioType == key.audioType }
            if (pendingMicrophone.remove(key) != null && !hasSuccessor) sink.onMicrophoneStopped(streamId)
            val meta = audioMeta.remove(key)
            audioCaptures.remove(key)?.close()
            if (!hasSuccessor) {
                if (drainAudio && !session.isClosed() && meta != null) {
                    endedAudio.add(key)
                    sink.onAudioEnded(streamId)
                } else sink.onAudioStopped(streamId)
            }
        }
    }

    @Synchronized
    override fun onSessionClosed(session: AirPlaySession) {
        stopEndedAudio(session)
        if (contextSession === session) contextSession = null
        sink.onCarPlaySessionEnded(session)
        clearPendingIapTunnel(session)
        videoSettingsChannels.remove(session)?.close()
        val sessionStreams = streams.keys.filter { it.session === session }
        synchronized(screenOwnershipLock) {
            sessionStreams.filter { isScreenStreamType(it.type) }.forEach { key ->
                streams.remove(key)?.close()
                if (streams.keys.none { it.type == key.type && isScreenStreamType(it.type) }) {
                    sink.onScreenStreamActive(key.type, false)
                }
            }
        }
        sessionStreams.filterNot { isScreenStreamType(it.type) }.forEach { key ->
            streams.remove(key)?.close()
            val id = AudioStreamId(key.type, key.audioType)
            val hasSuccessor = streams.keys.any { it.type == key.type && it.audioType == key.audioType }
            if (pendingMicrophone.remove(key) != null && !hasSuccessor) sink.onMicrophoneStopped(id)
            if (audioMeta.remove(key) != null && !hasSuccessor) sink.onAudioStopped(id)
            audioCaptures.remove(key)?.close()
        }
    }

    /** Caller holds the engine lifecycle monitor; no wait for playout occurs here. */
    private fun stopEndedAudio(session: AirPlaySession) {
        endedAudio.filter { it.session === session }.forEach { key ->
            endedAudio.remove(key)
            val hasSuccessor = streams.keys.any { it.type == key.type && it.audioType == key.audioType } ||
                endedAudio.any { it.type == key.type && it.audioType == key.audioType }
            if (!hasSuccessor) sink.onAudioStopped(AudioStreamId(key.type, key.audioType))
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (format.audioType != "telephony" && format.audioType != "speechrecognition") return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        val microphoneRtpClock = if (format.codec == AudioCodecKind.OPUS) {
            when {
                formatBits and OPUS_48K != 0L -> 48_000
                formatBits and OPUS_24K != 0L -> 24_000
                else -> 16_000
            }
        } else {
            format.sampleRate
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            rtpClockRate = microphoneRtpClock,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val VIDEO_SETTINGS_STREAM_ID = 2L
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}

/** An empty parameter dictionary targets the primary screen. */
internal fun mainScreenKeyFrameCommand(): Map<String, Any?> =
    linkedMapOf("type" to "forceKeyFrame", "params" to emptyMap<String, Any?>())
