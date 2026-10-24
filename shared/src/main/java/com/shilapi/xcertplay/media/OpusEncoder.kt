package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 */
internal class OpusEncoder(bitrate: Int, private val report: (String) -> Unit = {}) : Closeable {
    private val codec: MediaCodec? = createPlatformEncoder(bitrate)
    private val software = if (codec == null) NativeOpusEncoder(bitrate) else null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var closed = false
    private var outputPackets = 0
    private val pump = OpusEncodingPump(::queueInput, ::drain)

    init {
        if (software?.isOpen == true) Log.i(TAG, "Opus microphone software encoder started bitrate=$bitrate")
    }

    val available: Boolean get() = !closed && (codec != null || software?.isOpen == true)

    private fun createPlatformEncoder(bitrate: Int): MediaCodec? {
        var encoder: MediaCodec? = null
        try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        return encoder.also {
            it.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            it.start()
            Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
        }
        } catch (error: Exception) {
            try { encoder?.release() } catch (_: Exception) { /* Best effort. */ }
            Log.w(TAG, "Opus microphone encoder unavailable", error)
            return null
        }
    }

    /** Encoded outputs carry the source RTP time even when the platform delays an input. */
    @Synchronized
    fun encode(pcm: ByteArray, rtpTimestamp: Int): List<OpusAccessUnit> {
        if (closed) return emptyList()
        software?.let { return listOfNotNull(it.encode(pcm)?.let { bytes -> OpusAccessUnit(bytes, rtpTimestamp) }) }
        if (codec == null) return emptyList()
        return try { pump.encode(pcm, rtpTimestamp) }
        catch (error: Exception) {
            Log.w(TAG, "Opus microphone encoding failed", error)
            runCatching { report("Microphone: encoder failure stage=platform error=${error.javaClass.simpleName}") }
            emptyList()
        }
    }

    private fun queueInput(pcm: ByteArray, pts: Long): Boolean {
        val codec = codec ?: return false
        // Output is drained before this zero-wait dequeue. A busy codec leaves this exact PCM
        // frame pending for a later capture tick instead of dropping it or stalling AudioRecord.
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return false
        val input = codec.getInputBuffer(inputIndex)
        if (input == null || pcm.size > input.capacity()) {
            codec.queueInputBuffer(inputIndex, 0, 0, pts, 0)
            throw IllegalStateException("Opus encoder input buffer unavailable or too small")
        }
        input.clear()
        input.put(pcm)
        codec.queueInputBuffer(inputIndex, 0, pcm.size, pts, 0)
        return true
    }

    private fun drain(): List<OpusCodecOutput> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<OpusCodecOutput>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(OpusCodecOutput(bytes, bufferInfo.presentationTimeUs))
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        software?.close()
        runCatching { report("Microphone: encoder backlog pending=${pump.pendingFrames} " +
            "backpressure=${pump.backpressureCalls} dropped=${pump.droppedFrames} unmatched=${pump.unmatchedOutputs}") }
        val codec = codec ?: return
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

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
    }
}
