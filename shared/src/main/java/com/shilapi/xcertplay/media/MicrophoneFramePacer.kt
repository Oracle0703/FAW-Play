package com.shilapi.xcertplay.media

import java.util.ArrayDeque

/** A short, bounded PCM buffer between the HAL's capture bursts and microphone RTP. */
internal class MicrophoneFramePacer(
    private val frameBytes: Int,
    private val frameDurationNs: Long,
    private val prefillFrames: Int = 4,
    private val capacityFrames: Int = 6,
) {
    data class Frame(val pcm: ByteArray, val skippedFrames: Int)

    private val frames = ArrayDeque<ByteArray>()
    private var nextSendNs: Long? = null
    private var skippedFrames = 0
    var droppedFrames: Int = 0
        private set
    var underruns: Int = 0
        private set

    init {
        require(frameBytes > 0 && frameDurationNs > 0)
        require(prefillFrames in 1..capacityFrames)
    }

    fun offer(pcm: ByteArray) {
        require(pcm.size == frameBytes)
        if (frames.size == capacityFrames) {
            frames.removeFirst()
            skippedFrames++
            droppedFrames++
        }
        // The capture loop reuses its assembly buffer.
        frames.addLast(pcm.copyOf())
    }

    fun poll(nowNs: Long): Frame? {
        if (nextSendNs == null) {
            if (frames.size < prefillFrames) return null
            nextSendNs = nowNs
        }
        if (nowNs < nextSendNs!!) return null
        if (frames.isEmpty()) {
            underruns++
            nextSendNs = null
            return null
        }
        // Keep the sample clock's phase. Restarting the deadline from every slightly
        // late wakeup slowly lowers the packet rate and eventually drops queued speech.
        // A full-frame stall starts a new phase so it cannot trigger an immediate burst.
        val nextDeadline = nextSendNs!! + frameDurationNs
        nextSendNs = if (nextDeadline > nowNs) nextDeadline else nowNs + frameDurationNs
        return Frame(frames.removeFirst(), skippedFrames).also { skippedFrames = 0 }
    }

    fun pollDelayNs(nowNs: Long): Long {
        val deadline = nextSendNs ?: return MAX_POLL_NS
        return (deadline - nowNs).coerceIn(1L, MAX_POLL_NS)
    }

    private companion object {
        const val MAX_POLL_NS = 4_000_000L
    }
}
