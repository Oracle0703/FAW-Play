package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MicrophoneConfig

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private var routedDeviceType: Int? = null
    private var pcmSamples = 0L
    private var pcmEnergy = 0L
    private var pcmPeak = 0
    private var pcmSilentFrames = 0L
    private var pcmClippedSamples = 0L

    fun started(routeType: Int?) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}")
    }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        reads++
        readMaxNs = maxOf(readMaxNs, (nowNs() - readStart).coerceAtLeast(0))
        when {
            count > 0 -> capturedBytes += count
            count == 0 -> zeroReads++
            else -> readErrors++
        }
    }

    fun encoded(frameCount: Int, emptyCount: Int) {
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    /** Levels only; no voice samples or encoded data are retained in diagnostics. */
    fun pcm(frame: ByteArray) {
        var peak = 0
        for (i in 0 until frame.size - 1 step 2) {
            val sample = (((frame[i + 1].toInt() and 255) shl 8) or
                (frame[i].toInt() and 255)).toShort().toInt()
            val magnitude = kotlin.math.abs(sample)
            peak = maxOf(peak, magnitude)
            pcmEnergy += sample.toLong() * sample
            pcmSamples++
            if (magnitude >= 32760) pcmClippedSamples++
        }
        pcmPeak = maxOf(pcmPeak, peak)
        if (peak == 0) pcmSilentFrames++
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
    }

    fun sendFailed() { sendErrors++ }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null) {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
            "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
            "readMaxMs=${readMaxNs / 1_000_000} encodedFrames=$encodedFrames " +
            "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors " +
            "sendGapMaxMs=${sendGapMaxNs / 1_000_000} " +
            "inputRms=${if (pcmSamples == 0L) 0 else kotlin.math.sqrt(pcmEnergy.toDouble() / pcmSamples).toInt()} " +
            "inputPeak=$pcmPeak silentFrames=$pcmSilentFrames clippedSamples=$pcmClippedSamples ended=$ended")
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        sendGapMaxNs = 0
        pcmSamples = 0
        pcmEnergy = 0
        pcmPeak = 0
        pcmSilentFrames = 0
        pcmClippedSamples = 0
    }

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = when (config.audioType) {
                "telephony" -> "telephony" to "VOICE_COMMUNICATION"
                "speechrecognition" -> "speechrecognition" to "VOICE_RECOGNITION"
                else -> "other" to "MIC"
            }
            return "type=$type source=$source codec=${config.codec.name} " +
                "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis} " +
                "bitrate=${config.bitrate ?: 0} rtpClock=${config.rtpClockRate} rtpSamples=${config.rtpSamplesPerPacket}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}
