package com.shilapi.xcertplay.media

/** Reorders only across a sequence gap; an in-order packet never waits for a prefill. */
internal class AudioPacketOrder<T>(
    private val gapWaitNs: Long,
    private val capacity: Int = 64,
) {
    private data class Pending<T>(val value: T, val arrivedNs: Long)
    private val pending = HashMap<Int, Pending<T>>()
    private var expected: Int? = null
    private var sourceSsrc: Int? = null
    private val retiredSources = java.util.ArrayDeque<Int>()
    var sourceChanges = 0L
        private set
    var retiredSourcePackets = 0L
        private set
    var duplicatesOrLate = 0L
        private set
    var missing = 0L
        private set
    var reordered = 0L
        private set

    init { require(gapWaitNs > 0 && capacity in 1..1024) }

    fun offer(sequence: Int, value: T, nowNs: Long, ssrc: Int? = null): List<T> {
        if (ssrc != null && sourceSsrc != ssrc) {
            if (retiredSources.contains(ssrc)) {
                retiredSourcePackets++
                return flush(nowNs)
            }
            sourceSsrc?.let {
                retiredSources.addLast(it)
                while (retiredSources.size > 4) retiredSources.removeFirst()
                sourceChanges++
            }
            sourceSsrc = ssrc
            expected = null
            pending.clear()
        }
        val seq = sequence and 0xffff
        val next = expected ?: seq.also { expected = it }
        if (distance(seq, next) < 0 || pending.containsKey(seq)) {
            duplicatesOrLate++
            return flush(nowNs)
        }
        if (seq != next) reordered++
        pending[seq] = Pending(value, nowNs)
        return flush(nowNs, pending.size >= capacity)
    }

    fun flush(nowNs: Long, forceGap: Boolean = false): List<T> {
        val ready = ArrayList<T>()
        var force = forceGap
        while (pending.isNotEmpty()) {
            val next = expected ?: break
            pending.remove(next)?.let {
                ready.add(it.value)
                expected = (next + 1) and 0xffff
            } ?: run {
                val oldest = pending.values.minOf { it.arrivedNs }
                if (!force && nowNs - oldest < gapWaitNs) return ready
                val nearest = pending.keys.minBy { distance(it, next) }
                missing += distance(nearest, next).toLong()
                expected = nearest
                force = false
            }
        }
        return ready
    }

    /** No more datagrams are expected: retain every authenticated packet across any final gaps. */
    fun drain(nowNs: Long): List<T> {
        val ready = ArrayList<T>()
        while (pending.isNotEmpty()) ready.addAll(flush(nowNs, forceGap = true))
        return ready
    }

    /** Zero means a blocking receive; otherwise wake when the earliest gap expires. */
    fun receiveTimeoutMillis(nowNs: Long): Int {
        if (pending.isEmpty()) return 0
        val remaining = gapWaitNs - (nowNs - pending.values.minOf { it.arrivedNs })
        return ((remaining.coerceAtLeast(0) + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
    }

    private fun distance(sequence: Int, origin: Int): Int =
        ((sequence - origin + 0x8000) and 0xffff) - 0x8000
}
