package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.LinkedHashMap

internal data class OpusAccessUnit(val bytes: ByteArray, val rtpTimestamp: Int)
internal data class OpusCodecOutput(val bytes: ByteArray, val presentationTimeUs: Long)

/** Retains an unconsumed PCM frame across temporary codec backpressure, with bounded speech age. */
internal class OpusEncodingPump(
    private val input: (ByteArray, Long) -> Boolean,
    private val drain: () -> List<OpusCodecOutput>,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private data class Frame(val pcm: ByteArray, val timestamp: Int, val pts: Long, val arrivedNs: Long)
    private val pending = ArrayDeque<Frame>()
    private val inFlight = LinkedHashMap<Long, Frame>()
    private var nextPts = 0L
    private var codecPtsOffset: Long? = null
    private var firstSubmittedPts: Long? = null
    var droppedFrames = 0L
        private set
    var backpressureCalls = 0L
        private set
    var unmatchedOutputs = 0L
        private set
    val pendingFrames: Int get() = pending.size

    fun encode(pcm: ByteArray, timestamp: Int): List<OpusAccessUnit> {
        val now = nowNs()
        pending.addLast(Frame(pcm.copyOf(), timestamp, nextPts, now))
        nextPts += 20_000
        while (pending.size > MAX_PENDING || pending.peekFirst()?.let { now - it.arrivedNs > MAX_INPUT_AGE_NS } == true) {
            pending.removeFirst()
            droppedFrames++
        }
        val output = ArrayList<OpusAccessUnit>()
        collect(output)
        // A codec may consume an input without ever returning its output. Expire its identity too;
        // otherwise eight missing outputs would permanently stop all future microphone inputs.
        val inFlightIterator = inFlight.entries.iterator()
        while (inFlightIterator.hasNext()) {
            if (now - inFlightIterator.next().value.arrivedNs > MAX_OUTPUT_AGE_NS) {
                inFlightIterator.remove()
                droppedFrames++
            }
        }
        while (pending.isNotEmpty() && inFlight.size < MAX_IN_FLIGHT) {
            val frame = pending.peekFirst() ?: break
            if (!input(frame.pcm, frame.pts)) {
                backpressureCalls++
                break
            }
            pending.removeFirst()
            if (firstSubmittedPts == null) firstSubmittedPts = frame.pts
            inFlight[frame.pts] = frame
            collect(output)
        }
        collect(output)
        return output
    }

    private fun collect(output: MutableList<OpusAccessUnit>) {
        drain().forEach { packet ->
            // MediaCodec preserves the input PTS on encoded access units. Do not assign a delayed
            // output to the newest PCM frame: skipped capture/encoder frames must leave RTP gaps.
            // Some platform encoders report the fixed Opus lookahead in their initial PTS.
            // Learn that offset once from the first non-config output; retain gaps thereafter.
            if (codecPtsOffset == null) {
                val initialDelta = firstSubmittedPts?.let { packet.presentationTimeUs - it }
                codecPtsOffset = when {
                    inFlight.containsKey(packet.presentationTimeUs) -> 0L
                    initialDelta != null && initialDelta in -20_000L..0L -> initialDelta
                    else -> null // Never infer an offset from the newest input after old inputs expired.
                }
            }
            val source = inFlight.remove(packet.presentationTimeUs - (codecPtsOffset ?: 0))
            if (source == null) {
                unmatchedOutputs++
            } else if (nowNs() - source.arrivedNs > MAX_OUTPUT_AGE_NS) {
                droppedFrames++
            } else if (packet.bytes.isNotEmpty()) {
                output.add(OpusAccessUnit(packet.bytes, source.timestamp))
            } else {
                droppedFrames++
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 3 // 60 ms of 20 ms microphone frames
        const val MAX_IN_FLIGHT = 8
        const val MAX_INPUT_AGE_NS = 60_000_000L
        const val MAX_OUTPUT_AGE_NS = 240_000_000L
    }
}
