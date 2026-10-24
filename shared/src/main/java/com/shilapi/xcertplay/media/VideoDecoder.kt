package com.shilapi.xcertplay.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long, val senderNs: Long) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
internal class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: ((Boolean) -> Unit) -> Boolean,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    context: Context? = null,
) : Closeable, VideoSurfaceHandoff.Owner {
    private val queue = VideoWorkQueue<VideoJob>(8)
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var requestedSurface: Surface? = surface
    @Volatile private var outputSurface: Surface? = surface
    private class PresentationTarget(val surface: Surface) {
        val first = VideoFirstPresentation()
    }
    @Volatile private var presentationTarget: PresentationTarget? = null
    private var heldFrame: VideoJob.Frame? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var needsKeyFrame = true
    private var pendingSinceNs = 0L
    private var lastPtsUs = 0L
    private var lastConfig: VideoJob.Config? = null
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfig = false
    private var failureReports = 0
    private var lastKeyFrameRequestNs = 0L
    @Volatile private var keyFrameWriteFailed = false
    private var keyFrameWaitStartedNs = System.nanoTime()
    private val outputProgress = VideoOutputProgress()
    private val isMainScreen = streamType == 110 && statsLabel == null
    private var observedTouchNs = TouchLatencyProbe.latestTouchSentNs
    private var captureTouchNs = 0L
    private val traceSessionId = System.nanoTime()
    private val frameTrace = VideoFrameTrace(if (isMainScreen) context else null)
    private val exited = java.util.concurrent.CountDownLatch(1)
    val isRunning: Boolean get() = running
    val hasExited: Boolean get() = !thread.isAlive
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    init { VideoSurfaceHandoff.register(this); thread.start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    fun submit(nalus: ByteArray, arrivalNs: Long, senderNs: Long) {
        if (!running) return
        stats.onReceived(nalus.size, arrivalNs, senderNs)
        queue.frame(VideoJob.Frame(nalus, arrivalNs, senderNs))
    }

    override fun beginRelease(surface: Surface): Boolean {
        if (requestedSurface !== surface && outputSurface !== surface) return false
        close()
        return true
    }

    override fun awaitRelease(deadlineNs: Long): Boolean {
        if (Thread.currentThread() === thread) return false
        return exited.await((deadlineNs - System.nanoTime()).coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)
    }

    fun setSurface(surface: Surface?) {
        if (requestedSurface !== surface) presentationTarget = null
        requestedSurface = surface
    }

    fun hasRenderedSurface(surface: Surface): Boolean {
        val target = presentationTarget ?: return false
        return running && requestedSurface === surface && outputSurface === surface &&
            target.surface === surface && target.first.confirmed
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    private fun run() {
        PlaybackResourceSampler.liveDecoderThreads.incrementAndGet()
        try {
            while (running) {
                try {
                    if (keyFrameWriteFailed) {
                        keyFrameWriteFailed = false
                        outputProgress.probeFailed()
                    }
                    if (outputSurface !== requestedSurface) changeSurface(requestedSurface)
                    // A detach/reattach can occur before the worker observes the intermediate null.
                    val activeCodec = decoder
                    val surface = outputSurface
                    if (presentationTarget == null && activeCodec != null && surface != null && surface === requestedSurface)
                        watchPresentation(activeCodec, surface)
                    if (isMainScreen) {
                        val touchNs = TouchLatencyProbe.latestTouchSentNs
                        if (touchNs != observedTouchNs) {
                            observedTouchNs = touchNs
                            outputProgress.beginEpisode(System.nanoTime())
                            // DOWN/UP and closely spaced move reports belong to the same capture.
                            if (touchNs - captureTouchNs > 300_000_000L &&
                                frameTrace.startCapture(traceSessionId, touchNs)) captureTouchNs = touchNs
                        }
                    }
                    if (needsKeyFrame && (lastConfig != null || awaitingConfig) && outputSurface != null) {
                        requestKeyFrameIfDue()
                    }
                    // Keep taking output even when the sender is idle or input buffers are busy.
                    pump?.tick()
                    presentationTarget?.let { target ->
                        if (running && target.surface === requestedSurface &&
                            target.first.useSubmittedFallback(System.nanoTime()))
                            reportFirstPresentation(target, "submitted-output compatibility fallback")
                    }
                    if (!needsKeyFrame && outputSurface != null && outputProgress.shouldProbe(System.nanoTime()) &&
                        requestKeyFrameIfDue()) {
                        outputProgress.probeScheduled()
                        report("output pending without progress; keyframe probe queued ageMs=${outputProgress.ageNanos(System.nanoTime()) / 1_000_000}")
                    }
                    stats.logIfDue()?.let(report)
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        Thread.sleep(5)
                        continue
                    }
                    when (val job = heldFrame ?: queue.poll(5)) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            stats.onQueueAge(System.nanoTime() - job.receivedNs)
                            if (lastConfig == null) {
                                observeMissingConfig()
                            } else {
                                // Preserve the initial random access picture until a Surface exists.
                                heldFrame = if (outputSurface == null) job else null
                                if (outputSurface != null) feed(job)
                            }
                        }
                        null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) {
                        Log.e(TAG, "video decoder failed; restarting at random access", error)
                        reportFailure("stage=decode", error)
                        recover("video decoder failed: ${error.javaClass.simpleName}")
                    }
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=decode", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame = null
            queue.close()
            frameTrace.close()
            try { releaseDecoder() } finally {
                running = false
                PlaybackResourceSampler.liveDecoderThreads.decrementAndGet()
                VideoSurfaceHandoff.unregister(this)
                exited.countDown()
            }
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
        awaitingConfig = false
        duplicateConfigLogged = false
        releaseDecoder()
        needsKeyFrame = true
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
        require(csd.isNotEmpty() && (codec != VideoCodec.H264 || csd.size == 2)) {
            "Missing or invalid video parameter sets"
        }
        frameTrace.onConfig(codec, csd)
        val parameters = VideoParameters.parse(codec, csd.first())
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val lowLatencyName = if (codec == VideoCodec.H265 && preferSoftwareHevcDecoder) null
            else lowLatencyDecoderName(mime, parameters)
        val attempts = listOfNotNull(lowLatencyName?.let { DecoderAttempt(it, tuned = true) }) + listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, parameters, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        if (next != null) {
            val active = next
            syncGate = VideoSyncGate(codec)
            pump = VideoDecodePump(object : VideoDecodePump.Port {
                override fun drain() = drainOutput(active)
                override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                    val index = active.dequeueInputBuffer(0)
                    if (index < 0) return false
                    val input = checkNotNull(active.getInputBuffer(index)) { "Decoder input buffer unavailable" }
                    input.clear()
                    check(bytes.size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                    input.put(bytes)
                    active.queueInputBuffer(index, 0, bytes.size, presentationTimeUs, 0)
                    val now = System.nanoTime()
                    outputProgress.onInput(presentationTimeUs, now)
                    stats.onCodecInput(presentationTimeUs, now)
                    frameTrace.onInput(presentationTimeUs, now)
                    stats.onInputWait(now - pendingSinceNs)
                    return true
                }
            })
        }
        stats.onRebuild(System.nanoTime() - rebuildStarted)
        if (next != null) watchPresentation(next, surface)
        outputProgress.reset()
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime coded=${parameters.width}x${parameters.height} negotiated=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private fun watchPresentation(codec: MediaCodec, surface: Surface) {
        val target = PresentationTarget(surface)
        presentationTarget = target
        runCatching {
            codec.setOnFrameRenderedListener({ source, ptsUs, nanoTime ->
                if (running && decoder === source && presentationTarget === target &&
                    requestedSurface === surface && outputSurface === surface) {
                    stats.onPresented(nanoTime, ptsUs)
                    frameTrace.onPresent(ptsUs, nanoTime)
                    if (target.first.presented(ptsUs)) reportFirstPresentation(target, "frame-rendered callback")
                }
            }, PlaybackResourceSampler.handler)
        }.onFailure { report("frame rendered callback unavailable ${it.javaClass.simpleName}") }
    }

    private fun reportFirstPresentation(target: PresentationTarget, source: String) {
        if (!running || presentationTarget !== target || requestedSurface !== target.surface ||
            outputSurface !== target.surface) return
        report("first frame presentation confirmed source=$source")
        // Preserve the session's existing per-stream event contract.
        report("first frame rendered")
        Log.i(TAG, "video decoder first presentation source=$source")
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, parameters: VideoParameters, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
            if (parameters.colorStandard != -1) setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
            if (parameters.colorRange != -1) setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
            if (parameters.colorTransfer != -1) setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        parameters: VideoParameters,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, parameters, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            if (lowLatency) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            report("decoder started name=${codec.name} lowLatency=$lowLatency tuned=${attempt.tuned}")
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

    private fun lowLatencyDecoderName(mime: String, parameters: VideoParameters): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isHardwareAccelerated && !info.isAlias && mime in info.supportedTypes && runCatching {
                    info.getCapabilitiesForType(mime).let { capabilities ->
                        capabilities.isFeatureSupported("low-latency") &&
                            capabilities.videoCapabilities?.isSizeSupported(parameters.width, parameters.height) == true
                    }
                }.getOrDefault(false)
            }?.name
        }.getOrNull()
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
        if (surface == null) {
            releaseDecoder()
            outputSurface = null
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                outputSurface = surface
                watchPresentation(codec, surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        outputSurface = surface
        lastConfig?.let(::configureDecoder)
    }

    private fun observeMissingConfig() {
        if (!awaitingConfig) {
            awaitingConfig = true
            Log.i(TAG, "video frames without codec config; requesting keyframe")
            report("frames without codec config; requesting keyframe")
        }
        needsKeyFrame = true
    }

    private fun feed(frame: VideoJob.Frame) {
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (decoder == null) configureDecoder(config)
        val activePump = pump ?: return
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        require(annexB.isNotEmpty()) { "Invalid video access unit" }
        if (syncGate?.accept(annexB) != true) {
            // Skipping RASL after CRA preserves sync; it does not need another keyframe.
            if (syncGate?.waitingForRandomAccess == true) needsKeyFrame = true
            return
        }
        if (needsKeyFrame) stats.onKeyFrameWait(System.nanoTime() - keyFrameWaitStartedNs)
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first random access input bytes=${annexB.size}",
            )
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedNs / 1_000)
        frameTrace.onFrame(annexB, frame.receivedNs, frame.senderNs, lastPtsUs)
        activePump.submit(annexB, lastPtsUs)
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        needsKeyFrame = true
        keyFrameWaitStartedNs = System.nanoTime()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue(): Boolean {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return false
        if (!requestKeyFrame { sent -> if (!sent) keyFrameWriteFailed = true }) return false
        lastKeyFrameRequestNs = now
        return true
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    val target = presentationTarget
                    if (render && target != null && target.surface === outputSurface && target.surface === requestedSurface)
                        target.first.submitted(info.presentationTimeUs, System.nanoTime())
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        outputProgress.onOutput(info.presentationTimeUs, System.nanoTime())
                        stats.onCodecOutput(info.presentationTimeUs)
                        frameTrace.onOutput(info.presentationTimeUs, System.nanoTime())
                        stats.onRendered()
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
        presentationTarget = null
        val codec = decoder
        decoder = null
        pump = null
        syncGate = null
        stats.onDecoderReset()
        outputProgress.reset()
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
        const val INPUT_STALL_NS = 2_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
