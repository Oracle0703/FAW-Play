package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.microphoneBindAddress
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** 采集 16 kHz 单声道 PCM，按协商格式发送 LPCM、Opus 或 AAC-ELD。 */
internal class MicrophoneUplink(
    private val context: Context,
    private val config: MicrophoneConfig,
    microphoneGainPercent: Int,
) : Closeable {
    private val microphoneGainPercent = MicrophoneGain.sanitize(microphoneGainPercent)
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var audioModeLease: Closeable? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var eldEncoder: AacEldEncoder? = null
    private var thread: Thread? = null

    /** 实际采集速率：优先直接按协商速率打开，由系统做重采样；打不开才回退 16 kHz 并自行变换。 */
    private var captureRate = config.sampleRate

    /** 一帧输出对应的采集字节数；每次 read 恰好取一帧，包的间隔就是帧长。 */
    private val captureFrameBytes: Int
        get() = (captureRate.toLong() * config.samplesPerPacket / config.sampleRate).toInt() * BYTES_PER_SAMPLE

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            startCapture()
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone start failed", error)
            release()
            false
        }
    }

    private fun startCapture() {
        audioModeLease = MicrophoneAudioMode.acquire(context)

        if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder = OpusEncoder(
                config.sampleRate,
                config.bitrate ?: 48_000,
                onFallback = { reason -> Log.w(TAG, "Opus encoder fallback: $reason") },
            )
        }

        if (config.codec == AudioCodecKind.AAC_ELD) {
            require(config.channels == 1)
            eldEncoder = AacEldEncoder.create(config.sampleRate, config.samplesPerPacket, config.bitrate ?: 48_000)
        }

        val nextRecorder = try {
            openMicrophoneRecorder(captureRate, captureFrameBytes * 4)
        } catch (error: Exception) {
            if (captureRate == MICROPHONE_CAPTURE_RATE_HZ) throw error
            Log.w(TAG, "microphone cannot capture at ${captureRate} Hz, falling back to $MICROPHONE_CAPTURE_RATE_HZ Hz", error)
            captureRate = MICROPHONE_CAPTURE_RATE_HZ
            openMicrophoneRecorder(captureRate, captureFrameBytes * 4)
        }
        recorder = nextRecorder

        val nextSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(microphoneBindAddress(config.host), 0))
        }
        socket = nextSocket

        nextRecorder.startRecording()
        thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
            isDaemon = true
            start()
        }
        Log.i(
            TAG,
            "microphone uplink started type=${config.audioType} codec=${config.codec} " +
                "captureRate=$captureRate outputRate=${config.sampleRate} " +
                "channels=${config.channels} gain=${microphoneGainPercent}% " +
                "frameMs=${config.frameMillis} port=${config.port}",
        )
    }

    private fun capture(activeRecorder: AudioRecord, activeSocket: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ByteArray(config.samplesPerPacket * BYTES_PER_SAMPLE)
        val readBuffer = ByteArray(captureFrameBytes)
        val resampler = if (config.sampleRate == captureRate) {
            null
        } else {
            PcmMonoResampler(captureRate, config.sampleRate)
        }
        val counters = MicrophoneCounters()
        var filled = 0
        try {
            while (running.get()) {
                val count = activeRecorder.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) continue

                MicrophoneGain.applyPcm16InPlace(readBuffer, count, microphoneGainPercent)
                val samples = resampler?.convert(readBuffer, 0, count) ?: readBuffer
                val sampleBytes = if (resampler == null) count else samples.size
                var offset = 0
                while (offset < sampleBytes && running.get()) {
                    val copied = minOf(frame.size - filled, sampleBytes - offset)
                    samples.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(activeSocket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            running.set(false)
            try {
                activeRecorder.stop()
            } catch (_: Exception) {
                // The recorder may already be stopped by close().
            }
            release()
        }
    }

    private fun sendFrame(
        activeSocket: DatagramSocket,
        counters: MicrophoneCounters,
        monoFrame: ByteArray,
    ) {
        val bodies = when (config.codec) {
            AudioCodecKind.OPUS -> opusEncoder?.encode(monoFrame).orEmpty()
            AudioCodecKind.AAC_ELD -> eldEncoder?.encode(monoFrame).orEmpty()
            AudioCodecKind.LPCM -> listOf(MicrophonePacketizer.toWirePcm(expandMonoPcm(monoFrame, config.channels)))
            AudioCodecKind.AAC_LC -> error("AAC-LC microphone input is unsupported")
        }
        bodies.forEach { body ->
            val packet = MicrophonePacketizer.sealPacket(
                key = config.key,
                payloadType = config.payloadType,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
            try {
                activeSocket.send(DatagramPacket(packet, packet.size, config.host, config.port))
                if (firstPacketLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "microphone first packet bytes=${packet.size} body=${body.size} " +
                            "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                            "peer=${config.host.hostAddress}:${config.port}",
                    )
                }
            } catch (error: Exception) {
                if (running.get()) throw error
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)

        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentEncoder = opusEncoder
        opusEncoder = null
        try {
            currentEncoder?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentEldEncoder = eldEncoder
        eldEncoder = null
        try {
            currentEldEncoder?.close()
        } catch (_: Exception) {
            // 关闭失败也必须继续释放音频模式。
        }

        val currentAudioModeLease = audioModeLease
        audioModeLease = null
        try {
            currentAudioModeLease?.close()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val BYTES_PER_SAMPLE = 2
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
