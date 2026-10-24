package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in diagnostic capture. A bounded writer queue keeps disk I/O off the audio receive thread.
 *
 * Version 1 format is unchanged: "XCAUDIO1", version, stream type, wall-clock start milliseconds;
 * then records containing wire length, RTP length (-1 if absent), sample (-1 if absent), monotonic
 * nanoseconds, error length, wire bytes, RTP bytes and UTF-8 error bytes (all integers big-endian).
 */
class AudioPacketCapture(
    private val directory: File,
    private val streamType: Int,
    private val maxPackets: Int = 512,
    private val maxBytes: Long = 16L * 1024L * 1024L,
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it, false) },
) : Closeable {
    private data class Record(val wire: ByteArray, val rtp: ByteArray?, val sample: Int?,
        val error: ByteArray, val timestampNs: Long) {
        val size: Long get() = RECORD_HEADER_BYTES + wire.size.toLong() + (rtp?.size ?: 0) + error.size
    }
    private val lock = Object()
    private val pending = ArrayDeque<Record>()
    private val complete = CountDownLatch(1)
    private var accepting = maxPackets > 0
    private var queuedBytes = 0L
    private var acceptedPackets = 0
    private var reservedBytes = (MAGIC.size + HEADER_BYTES).toLong()
    private var droppedPackets = 0L
    private val startedMillis = System.currentTimeMillis()
    val file = File(directory, "audio-$startedMillis-type-$streamType.xcap")

    init {
        require(maxPackets >= 0 && maxBytes >= MAGIC.size + HEADER_BYTES)
        Thread(::writeCapture, "carplay-audio-capture-$streamType").apply { isDaemon = true; start() }
    }

    fun record(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
        synchronized(lock) {
            if (!accepting) return
            val errorBytes = error?.message?.take(MAX_ERROR_CHARS)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val size = RECORD_HEADER_BYTES + wire.size.toLong() + (rtp?.size ?: 0) + errorBytes.size
            if (reservedBytes + size > maxBytes) {
                accepting = false
                lock.notifyAll()
                return
            }
            if (pending.size >= MAX_QUEUED_PACKETS || queuedBytes + size > MAX_QUEUED_BYTES) {
                droppedPackets++ // Discard diagnostic data, never block live audio on a slow disk.
                return
            }
            val record = Record(wire.copyOf(), rtp?.copyOf(), sample, errorBytes, System.nanoTime())
            pending.addLast(record)
            queuedBytes += size
            reservedBytes += size
            acceptedPackets++
            if (acceptedPackets >= maxPackets) accepting = false
            lock.notifyAll()
        }
    }

    /** Signals the writer to drain and close; callers never wait for disk flush or close. */
    override fun close() = synchronized(lock) { accepting = false; lock.notifyAll() }

    internal fun awaitClosed(timeoutMillis: Long): Boolean = complete.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun writeCapture() {
        var output: DataOutputStream? = null
        var writtenPackets = 0
        var writtenBytes = 0L
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Audio capture directory unavailable" }
            output = DataOutputStream(BufferedOutputStream(openOutput(file)))
            output.write(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(streamType)
            output.writeLong(startedMillis)
            writtenBytes = (MAGIC.size + HEADER_BYTES).toLong()
            while (true) {
                val record = synchronized(lock) {
                    while (pending.isEmpty() && accepting) lock.wait()
                    pending.pollFirst()?.also { queuedBytes -= it.size }
                } ?: break
                output.writeInt(record.wire.size)
                output.writeInt(record.rtp?.size ?: -1)
                output.writeInt(record.sample ?: -1)
                output.writeLong(record.timestampNs)
                output.writeInt(record.error.size)
                output.write(record.wire)
                record.rtp?.let(output::write)
                output.write(record.error)
                writtenPackets++
                writtenBytes += record.size
                if (writtenPackets % FLUSH_INTERVAL_PACKETS == 0) output.flush()
            }
        } catch (error: Exception) {
            Log.w(TAG, "audio capture write failed type=$streamType", error)
        } finally {
            synchronized(lock) { accepting = false; pending.clear(); queuedBytes = 0 }
            runCatching { output?.close() }.onFailure { Log.w(TAG, "audio capture close failed type=$streamType", it) }
            Log.i(TAG, "audio capture stopped type=$streamType packets=$writtenPackets bytes=$writtenBytes " +
                "diagnosticDrops=$droppedPackets file=${file.absolutePath}")
            complete.countDown()
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val VERSION = 1
        const val HEADER_BYTES = 16
        const val RECORD_HEADER_BYTES = 24L
        const val FLUSH_INTERVAL_PACKETS = 16
        const val MAX_QUEUED_PACKETS = 64
        const val MAX_QUEUED_BYTES = 1024L * 1024L
        const val MAX_ERROR_CHARS = 1024
        val MAGIC = "XCAUDIO1".toByteArray(Charsets.US_ASCII)
    }
}
