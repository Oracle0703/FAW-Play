package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private val admission = Any()
    private val sessions = mutableSetOf<SessionLogFile>()
    private val closing = mutableSetOf<SessionLogFile>()
    private val activeFiles = mutableMapOf<String, SessionLogFile>() // writer-thread only
    private var barriers = 0
    // Only accessed by the single writer; preserve event time without formatting on producers.
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writer = BoundedDiagnosticWriter<() -> Unit> { it() }

    fun open(file: File, header: String): SessionLogFile? = synchronized(admission) {
        if (sessions.size >= 8) return@synchronized null
        val target = SessionLogFile(file)
        sessions.add(target)
        check(writer.enqueueControl {
            val started = System.nanoTime()
            val key = file.absolutePath
            activeFiles.put(key, target)?.close()
            target.reset(header)
            Log.i("DiPlay-Diagnostics", "log open/rotate elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        })
        target
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        synchronized(admission) {
            if (target in closing) return
            writer.enqueue { target.append("${timestampFormat.format(Date(nowMillis))}  $safe") }
        }
    }

    fun close(target: SessionLogFile?, finalMessage: String? = null) {
        if (target == null) return
        synchronized(admission) {
            if (target !in sessions || !closing.add(target)) return
            val safe = finalMessage?.let(DiagnosticRedactor::redact)
            check(writer.enqueueControl {
                try {
                    safe?.let(target::append)
                    target.close()
                    if (activeFiles[target.file.absolutePath] === target) activeFiles.remove(target.file.absolutePath)
                } finally {
                    synchronized(admission) { sessions.remove(target); closing.remove(target) }
                }
            })
        }
    }

    /** A FIFO barrier includes preceding entries without waiting for future producers. */
    fun flush(timeoutMillis: Long): Boolean {
        val done = CountDownLatch(1)
        synchronized(admission) {
            if (barriers >= 8) return false
            barriers++
            check(writer.enqueueControl {
                synchronized(admission) { barriers-- }
                done.countDown()
            })
        }
        return done.await(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    fun summary(): String = "diagnosticWriter pending=${writer.pendingCount} dropped=${writer.droppedCount} failures=${writer.failedCount} ioFailures=${SessionLogFile.writeFailures.get()}"

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
