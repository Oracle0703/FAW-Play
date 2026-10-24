package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Apply TCP backpressure to compressed pictures, never discard a reference on queue overflow. */
internal class VideoWorkQueue<T>(capacity: Int, private val onDiscard: (T) -> Unit = {}) : Closeable {
    private data class Entry<T>(val value: T, val frame: Boolean)
    private val entries = LinkedBlockingQueue<Entry<T>>()
    private val slots = Semaphore(capacity)
    @Volatile private var closed = false
    val size: Int get() = entries.size

    fun frame(value: T): Boolean {
        while (!closed) {
            if (!slots.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
            val enqueued = synchronized(this) {
                if (closed) { slots.release(); false }
                else { entries.offer(Entry(value, true)); true }
            }
            return enqueued
        }
        return false
    }

    @Synchronized
    fun control(value: T) {
        if (!closed) entries.offer(Entry(value, false))
    }

    fun poll(timeoutMs: Long): T? {
        val entry = entries.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (entry.frame) slots.release()
        return entry.value
    }

    @Synchronized
    override fun close() {
        closed = true
        while (true) {
            val entry = entries.poll() ?: break
            if (entry.frame) slots.release()
            onDiscard(entry.value)
        }
    }
}

/** A tick must run even when the sender is idle. An unavailable input buffer is not a lost frame. */
internal class VideoDecodePump(private val port: Port) {
    interface Port {
        fun drain()
        fun queue(bytes: ByteArray, size: Int, presentationTimeUs: Long): Boolean
    }
    private data class Input(
        val bytes: ByteArray,
        val size: Int,
        val ptsUs: Long,
        val release: () -> Unit,
    )
    private var pending: Input? = null
    val hasPending: Boolean get() = pending != null

    fun submit(bytes: ByteArray, size: Int, presentationTimeUs: Long, release: () -> Unit = {}) {
        check(pending == null)
        require(size in 0..bytes.size)
        pending = Input(bytes, size, presentationTimeUs, release)
    }

    fun tick() {
        port.drain()
        pending?.let {
            if (port.queue(it.bytes, it.size, it.ptsUs)) {
                pending = null
                it.release()
            }
        }
        port.drain()
    }

    fun clear() {
        pending?.release()
        pending = null
    }
}

/** Every new decoder needs random access. CRA's RASL pictures may refer to the discarded GOP. */
class VideoSyncGate(private val codec: VideoCodec) {
    private var waiting = true
    private var skipRasl = false
    val waitingForRandomAccess: Boolean get() = waiting

    fun accept(annexB: ByteArray, length: Int = annexB.size): Boolean {
        // Only the NAL headers decide; copying every unit would duplicate the whole picture.
        val headers = MediaCodecSupport.annexBNalHeaders(annexB, length)
        require(headers.isNotEmpty()) { "Empty or malformed video access unit" }
        return acceptTypes(nalUnitTypes(codec, headers))
    }

    /** Same decision as [accept], for callers that already parsed the NAL unit types. */
    fun acceptTypes(types: List<Int>): Boolean {
        val randomAccess = isRandomAccess(codec, types)
        if (waiting) {
            if (!randomAccess) return false
            waiting = false
            skipRasl = codec == VideoCodec.H265
        }
        if (skipRasl) {
            if (types.any { it == 8 || it == 9 }) return false
            if (types.any { it in 0..5 || it in 10..15 }) skipRasl = false
        }
        return true
    }

    companion object {
        /** NAL unit types from NAL units (or at least their two-byte headers). */
        fun nalUnitTypes(codec: VideoCodec, units: List<ByteArray>): List<Int> = units.map {
            require(it.isNotEmpty() && it[0].toInt() and 0x80 == 0) { "Invalid NAL header" }
            if (codec == VideoCodec.H265) {
                require(it.size >= 2 && it[1].toInt() and 7 != 0) { "Invalid HEVC NAL header" }
                (it[0].toInt() ushr 1) and 0x3f
            } else it[0].toInt() and 0x1f
        }

        fun isRandomAccess(codec: VideoCodec, types: List<Int>): Boolean =
            types.any { if (codec == VideoCodec.H265) it in 16..21 else it == 5 }
    }
}
