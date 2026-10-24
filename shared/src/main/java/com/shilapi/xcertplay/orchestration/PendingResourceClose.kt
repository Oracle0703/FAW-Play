package com.shilapi.xcertplay.orchestration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A timed-out platform close stays owned here; retries never overlap that same close. */
class PendingResourceClose(private val onComplete: (String, Result) -> Unit = { _, _ -> }) {
    data class Result(val released: Boolean, val failure: String? = null)
    private class Operation(val task: FutureTask<Unit>, val thread: Thread)
    private val pending = ConcurrentHashMap<String, Operation>()

    fun run(name: String, timeoutMillis: Long, close: () -> Unit): Result {
        val operation = pending.computeIfAbsent(name) {
            val task = object : FutureTask<Unit>({ close(); Unit }) {
                override fun done() {
                    val result = try { get(); Result(true) }
                    catch (error: Exception) { Result(false, "$name: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}") }
                    // Observe actual completion; cancellation of a waiter never reports release.
                    runCatching { onComplete(name, result) }
                }
            }
            val thread = Thread(task, "autunn-close-$name").apply { isDaemon = true }
            Operation(task, thread).also { thread.start() }
        }
        return try {
            operation.task.get(timeoutMillis, TimeUnit.MILLISECONDS)
            pending.remove(name, operation)
            Result(true)
        } catch (_: TimeoutException) {
            // Request cancellation, but Future.cancel would falsely make an unresponsive
            // native/Binder close look finished. Keep its actual completion and handle.
            operation.thread.interrupt()
            Result(false, "$name: cancellation pending")
        } catch (_: InterruptedException) {
            operation.thread.interrupt()
            Thread.currentThread().interrupt()
            Result(false, "$name: waiter interrupted")
        } catch (error: Exception) {
            pending.remove(name, operation)
            Result(false, "$name: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
        }
    }

    fun interruptPending() { pending.values.forEach { it.thread.interrupt() } }
    fun pendingNames(): List<String> = pending.entries.filter { !it.value.task.isDone }.map { it.key }
}
