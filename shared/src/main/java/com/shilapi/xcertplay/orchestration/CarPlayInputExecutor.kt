package com.shilapi.xcertplay.orchestration

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Serial HID admission with space reserved for releases of every accepted down state. */
internal class CarPlayInputExecutor(private val capacity: Int = 64) : AbstractExecutorService() {
    private class Entry(val channel: String?, val token: Any?, val replaceable: Boolean,
        val action: Runnable? = null, var scroll: Int = 0, val scrollAction: ((Int) -> Unit)? = null)
    private data class State(val token: Any, var mask: Int)
    private val lock = Object()
    private val queue = ArrayDeque<Entry>()
    private val states = mutableMapOf<String, State>()
    @Volatile private var stopped = false
    init { require(capacity >= 8) }
    private val worker = Thread(::pump, "autunn-input").apply { isDaemon = true; start() }

    override fun execute(command: Runnable) = synchronized(lock) {
        admit(Entry(null, null, false, command), reserve())
    }

    /** mask changes are edges. Only reports with an unchanged down mask may replace a move. */
    fun state(channel: String, token: Any, mask: Int, replaceMoves: Boolean = true, isCurrent: () -> Boolean = { true }, makeAction: (Int) -> Runnable): Boolean = synchronized(lock) {
        checkOpen()
        if (!isCurrent()) return@synchronized false
        val previous = states[channel]
        if (previous?.token !== token) {
            queue.removeAll { it.channel == channel }
            states[channel] = State(token, 0)
        }
        val current = states.getValue(channel)
        val old = current.mask
        var accepted = mask
        val releases = old and mask.inv()
        var nextReserve = reserve() - Integer.bitCount(old) + Integer.bitCount(accepted)
        if (!makeRoom(nextReserve) && releases != 0) {
            // A combined up/new-down report may reject the new contact, but must deliver
            // all releases belonging to a down report that we previously accepted.
            accepted = old and mask
            nextReserve = reserve() - Integer.bitCount(old) + Integer.bitCount(accepted)
        }
        val replaceable = replaceMoves && old == accepted
        val tail = queue.peekLast()
        val entry = Entry(channel, token, replaceable, makeAction(accepted))
        if (replaceable && tail?.replaceable == true && tail.channel == channel && tail.token === token) {
            queue.removeLast()
            queue.addLast(entry)
        } else {
            if (!makeRoom(nextReserve)) return@synchronized false
            queue.addLast(entry)
        }
        current.mask = accepted
        lock.notifyAll()
        accepted == mask
    }

    /** Relative scroll is summed, never replaced by the last delta or reordered across an edge. */
    fun scroll(token: Any, delta: Int, isCurrent: () -> Boolean = { true }, send: (Int) -> Unit): Boolean = synchronized(lock) {
        checkOpen()
        if (!isCurrent()) return@synchronized false
        val boundedDelta = delta.coerceIn(-127, 127)
        val tail = queue.peekLast()
        if (tail?.scrollAction != null && tail.token === token && abs(tail.scroll.toLong() + boundedDelta) <= 1024) {
            tail.scroll += boundedDelta
            return@synchronized true
        }
        if (!makeRoom(reserve())) return@synchronized false
        queue.addLast(Entry("scroll", token, false, scroll = boundedDelta, scrollAction = send))
        lock.notifyAll()
        true
    }

    private fun reserve(): Int = states.values.sumOf { Integer.bitCount(it.mask) }
    private fun makeRoom(reserved: Int): Boolean {
        while (queue.size + 1 + reserved > capacity) {
            val stale = queue.firstOrNull { it.replaceable } ?: return false
            queue.remove(stale)
        }
        return true
    }
    private fun admit(entry: Entry, reserved: Int) {
        checkOpen()
        if (!makeRoom(reserved)) throw RejectedExecutionException("CarPlay input queue is full")
        queue.addLast(entry)
        lock.notifyAll()
    }
    private fun checkOpen() { if (stopped) throw RejectedExecutionException("CarPlay input queue is closed") }

    private fun pump() {
        while (true) {
            val entry = synchronized(lock) {
                while (queue.isEmpty() && !stopped) try { lock.wait() }
                    catch (_: InterruptedException) { if (stopped) return }
                if (queue.isEmpty()) return
                queue.removeFirst()
            }
            try {
                val scrollAction = entry.scrollAction
                if (scrollAction == null) entry.action?.run()
                else {
                    var remaining = entry.scroll
                    while (remaining != 0 && !Thread.currentThread().isInterrupted) {
                        val chunk = remaining.coerceIn(-127, 127)
                        scrollAction(chunk)
                        remaining -= chunk
                    }
                }
            } catch (_: RuntimeException) {
                // A failed command cannot prevent a later release from reaching the transport.
            }
        }
    }

    override fun shutdown() = synchronized(lock) { stopped = true; lock.notifyAll() }
    override fun shutdownNow(): MutableList<Runnable> = synchronized(lock) {
        stopped = true
        val discarded = queue.mapNotNull { it.action }.toMutableList()
        queue.clear(); states.clear()
        lock.notifyAll(); worker.interrupt()
        discarded
    }
    override fun isShutdown(): Boolean = stopped
    override fun isTerminated(): Boolean = !worker.isAlive
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val millis = unit.toMillis(timeout)
        if (millis > 0) worker.join(millis)
        return !worker.isAlive
    }
}
