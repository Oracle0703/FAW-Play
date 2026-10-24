package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded CSeq correlation for display/recovery commands. A write is never reported as an ACK. */
internal class AirPlayCommandTracker(
    private val nowNanos: () -> Long = System::nanoTime,
    private val schedule: (Long, Runnable) -> Closeable = { delay, task ->
        val future = scheduler.schedule(task, delay, TimeUnit.NANOSECONDS)
        Closeable { future.cancel(false) }
    },
    private val report: (String) -> Unit,
) {
    private data class Pending(val type: String, val screen: String, val startedNs: Long, var written: Boolean = false)
    private val lock = Any()
    private val pending = linkedMapOf<Int, Pending>()
    private var timer: Closeable? = null
    private var generation = Any()
    private var closed = false

    fun queued(cseq: Int, command: Map<String, Any?>, nowNs: Long = nowNanos()) {
        val type = command["type"] as? String ?: return
        if (type !in TRACKED) return
        val uuid = (command["params"] as? Map<*, *>)?.get("uuid")
        val screen = when (uuid) {
            AirPlayInfoPlist.ALT_UUID -> "alternate"
            null, AirPlayInfoPlist.MAIN_UUID -> "main"
            else -> "unknown"
        }
        val lines = synchronized(lock) {
            if (closed) return
            val lines = mutableListOf<String>()
            expireLocked(nowNs, lines)
            if (pending.size >= MAX_PENDING) finishLocked(pending.keys.first(), "evicted", nowNs, lines)
            pending[cseq] = Pending(type, screen, nowNs)
            armLocked(nowNs)
            lines
        }
        publish(lines)
    }

    fun written(cseq: Int) {
        val line = synchronized(lock) {
            val entry = pending[cseq] ?: return
            entry.written = true
            "command cseq=$cseq type=${entry.type} screen=${entry.screen} written=true response=pending"
        }
        report(line)
    }

    fun response(message: RtspMessage.Request, nowNs: Long = nowNanos()) {
        val cseq = message.headers["cseq"]?.toIntOrNull() ?: return
        val status = message.path.toIntOrNull() ?: return
        val result = if (message.body.isEmpty()) null else runCatching {
            (BplistCodec.decode(message.body) as? Map<*, *>)?.get("status") as? Number
        }.getOrNull()?.toLong()
        val outcome = if (status in 200..299 && (result == null || result == 0L)) "acknowledged" else "rejected"
        val lines = synchronized(lock) {
            if (closed) return
            val lines = mutableListOf<String>()
            // A delayed watchdog cannot make an acknowledgement extend its absolute lifetime.
            expireLocked(nowNs, lines)
            finishLocked(cseq, "$outcome status=$status result=$result", nowNs, lines)
            armLocked(nowNs)
            lines
        }
        publish(lines)
    }

    fun failed(cseq: Int) {
        val lines = synchronized(lock) {
            if (closed) return
            val nowNs = nowNanos()
            val lines = mutableListOf<String>()
            finishLocked(cseq, "write-failed", nowNs, lines)
            expireLocked(nowNs, lines)
            armLocked(nowNs)
            lines
        }
        publish(lines)
    }

    fun reset(outcome: String) {
        val lines = synchronized(lock) { resetLocked(outcome, nowNanos()) }
        publish(lines)
    }

    fun close() {
        val lines = synchronized(lock) {
            if (closed) return
            closed = true
            resetLocked("session-closed", nowNanos())
        }
        publish(lines)
    }

    private fun resetLocked(outcome: String, nowNs: Long): List<String> {
        val lines = mutableListOf<String>()
        pending.keys.toList().forEach { finishLocked(it, outcome, nowNs, lines) }
        cancelTimerLocked()
        return lines
    }

    private fun expireLocked(nowNs: Long, lines: MutableList<String>) {
        pending.filterValues { nowNs - it.startedNs >= RESPONSE_TIMEOUT_NS }.keys.toList()
            .forEach { finishLocked(it, "response-timeout", nowNs, lines) }
    }

    private fun finishLocked(cseq: Int, outcome: String, nowNs: Long, lines: MutableList<String>) {
        val entry = pending.remove(cseq) ?: return
        lines += "command cseq=$cseq type=${entry.type} screen=${entry.screen} written=${entry.written} " +
            "response=$outcome elapsedMs=${(nowNs - entry.startedNs).coerceAtLeast(0) / 1_000_000} effect=unmeasured"
    }

    /** One timer per tracker. It only expires tracker facts; it never touches a peer or session. */
    private fun armLocked(nowNs: Long) {
        cancelTimerLocked()
        if (closed) return
        val first = pending.values.minOfOrNull { it.startedNs } ?: return
        val expected = generation
        timer = schedule((RESPONSE_TIMEOUT_NS - (nowNs - first)).coerceAtLeast(0), Runnable {
            val lines = synchronized(lock) {
                if (closed || generation !== expected) return@Runnable
                timer = null
                val currentNs = nowNanos()
                val lines = mutableListOf<String>()
                expireLocked(currentNs, lines)
                // Early scheduler wakeups keep the original deadline, never a fresh 10 seconds.
                armLocked(currentNs)
                lines
            }
            publish(lines)
        })
    }

    private fun cancelTimerLocked() {
        generation = Any()
        timer?.close()
        timer = null
    }

    /** Logging is deliberately outside the state lock, including the watchdog path. */
    private fun publish(lines: List<String>) { lines.forEach(report) }

    private companion object {
        const val MAX_PENDING = 64
        const val RESPONSE_TIMEOUT_NS = 10_000_000_000L
        val TRACKED = setOf("forceKeyFrame", "showUI", "stopUI", "updateViewArea", "setVideoPlaybackAllowed")
        val scheduler = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "airplay-command-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
