package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

internal data class AudioBacklogPolicy(val queuedMillis: Int, val maxAgeMillis: Int) {
    companion object {
        fun forStream(format: AudioFormat, advanced: Boolean, mediaMillis: Int): AudioBacklogPolicy {
            val channel = AudioChannelMapper.map(format.audioType, format.payloadType,
                if (advanced) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE).channel
            return when {
                // A speech stream can wait 350ms for ownership metadata before its track exists.
                format.audioType.equals("speechrecognition", true) -> AudioBacklogPolicy(600, 900)
                channel == AudioChannel.PHONE -> AudioBacklogPolicy(160, 240)
                channel == AudioChannel.MEDIA -> (MediaAudioBuffer.sanitize(mediaMillis) + 500)
                    .coerceIn(800, 1500).let { AudioBacklogPolicy(it, it + 500) }
                else -> AudioBacklogPolicy(800, 1500) // preserve complete navigation bursts
            }
        }
    }
}

/** Encoded audio backlog is bounded by playable time and age, as well as packet/byte safety caps. */
internal class AudioBacklog<T>(
    val policy: AudioBacklogPolicy,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private data class Item<T>(val value: T, val durationNs: Long, val bytes: Int, val arrivedNs: Long)
    private val lock = Object()
    private val queue = ArrayDeque<Item<T>>()
    private var durationNs = 0L
    private var bytes = 0
    private var discarded = 0L
    val size: Int get() = synchronized(lock) { queue.size }
    fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
    fun isNotEmpty(): Boolean = !isEmpty()
    val queuedMillis: Long get() = synchronized(lock) { durationNs / 1_000_000 }
    val oldestAgeMillis: Long get() = synchronized(lock) {
        queue.peekFirst()?.let { ((nowNs() - it.arrivedNs) / 1_000_000).coerceAtLeast(0) } ?: 0
    }

    fun offer(value: T, packetDurationNs: Long, packetBytes: Int): Boolean = synchronized(lock) {
        val now = nowNs()
        expire(now)
        val duration = packetDurationNs.coerceAtLeast(1)
        if (duration > policy.queuedMillis * 1_000_000L || packetBytes > MAX_BYTES) {
            discarded++
            return@synchronized false
        }
        while (queue.isNotEmpty() && (queue.size >= MAX_PACKETS || bytes + packetBytes > MAX_BYTES ||
                durationNs + duration > policy.queuedMillis * 1_000_000L)) discardFirst()
        queue.addLast(Item(value, duration, packetBytes, now))
        durationNs += duration
        bytes += packetBytes
        lock.notifyAll()
        true
    }

    fun poll(timeout: Long, unit: TimeUnit): T? = synchronized(lock) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            expire(nowNs())
            val item = queue.pollFirst()
            if (item != null) {
                durationNs -= item.durationNs
                bytes -= item.bytes
                return@synchronized item.value
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized null
            lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    fun takeDiscarded(): Long = synchronized(lock) { discarded.also { discarded = 0 } }

    private fun expire(now: Long) {
        while (queue.peekFirst()?.let { now - it.arrivedNs >= policy.maxAgeMillis * 1_000_000L } == true)
            discardFirst()
    }

    private fun discardFirst() {
        val old = queue.removeFirst()
        durationNs -= old.durationNs
        bytes -= old.bytes
        discarded++
    }

    private companion object {
        const val MAX_PACKETS = 256
        const val MAX_BYTES = 1024 * 1024
    }
}

/** Durations describe independently packetized audio, never the compressed-video reference queue. */
internal object AudioPacketDuration {
    fun nanos(format: AudioFormat, rtp: ByteArray): Long {
        val samples = when (format.codec) {
            AudioCodecKind.AAC_LC -> 1024L
            AudioCodecKind.LPCM -> ((rtp.size - 12).coerceAtLeast(0) / (format.channels.coerceIn(1, 2) * 2)).toLong()
            AudioCodecKind.OPUS -> {
                if (rtp.size <= 12) return 20_000_000L
                val toc = rtp[12].toInt() and 0xff
                val config = toc ushr 3
                val frameUs = when {
                    config >= 16 -> 2500L shl (config and 3)
                    config >= 12 -> 10_000L shl (config and 1)
                    (config and 3) == 3 -> 60_000L
                    else -> 10_000L shl (config and 3)
                }
                val frames = when (toc and 3) {
                    0 -> 1
                    1, 2 -> 2
                    else -> if (rtp.size > 13) rtp[13].toInt() and 0x3f else 1
                }
                return (frameUs * frames.coerceAtLeast(1)).coerceAtMost(120_000L) * 1000
            }
        }
        return (samples.coerceAtLeast(1) * 1_000_000_000L / format.sampleRate.coerceAtLeast(1))
    }
}
