package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One shared diagnostics thread, with one cancellable sample per active sink. */
internal class PlaybackResourceSampler(
    private val context: Context?,
    private val decoders: () -> Int,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private var cpu = Process.getElapsedCpuTime()
    private var sampledAt = SystemClock.elapsedRealtime()
    @Volatile private var closed = false
    private val sample = object : Runnable {
        override fun run() {
            if (closed) return
            if (active()) runCatching {
                val now = SystemClock.elapsedRealtime()
                val nextCpu = Process.getElapsedCpuTime()
                val elapsed = (now - sampledAt).coerceAtLeast(1)
                val runtime = Runtime.getRuntime()
                val status = File("/proc/self/status").useLines { lines ->
                    lines.filter { it.startsWith("VmRSS:") || it.startsWith("Threads:") }.take(2).joinToString(" ")
                }
                val thermal = if (Build.VERSION.SDK_INT >= 29)
                    context?.getSystemService(PowerManager::class.java)?.currentThermalStatus?.toString() ?: "unavailable"
                    else "unavailable_api28"
                report("Playback resources cpuOneCorePct=${(nextCpu - cpu) * 100 / elapsed} " +
                    "javaBytes=${runtime.totalMemory() - runtime.freeMemory()} nativeBytes=${Debug.getNativeHeapAllocatedSize()} " +
                    "decoderSlots=${decoders()} liveDecoderThreads=${liveDecoderThreads.get()} thermal=$thermal $status")
                cpu = nextCpu
                sampledAt = now
            }
            if (!closed) handler.postDelayed(this, 30_000)
        }
    }
    init { handler.postDelayed(sample, 30_000) }
    fun close() { closed = true; handler.removeCallbacks(sample) }
    companion object {
        val handler: Handler by lazy { Handler(HandlerThread("autunn-playback-diagnostics").apply { start() }.looper) }
        val liveDecoderThreads = AtomicInteger()
    }
}
