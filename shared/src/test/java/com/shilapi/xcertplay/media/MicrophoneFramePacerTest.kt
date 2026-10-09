package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.junit.Assert.*
import org.junit.Test

class MicrophoneFramePacerTest {
    private val interval = 20_000_000L
    private fun frame(id: Int) = byteArrayOf(id.toByte(), 0)

    @Test fun captureBurstsLeaveAtTwentyMillisecondIntervals() {
        val pacer = MicrophoneFramePacer(2, interval)
        val sent = mutableListOf<Pair<Long, Int>>()
        for (millis in 0L..140L) {
            // A HAL that exposes four frames together every 80 ms.
            if (millis % 80L == 0L) repeat(4) { pacer.offer(frame((millis / 20).toInt() + it)) }
            pacer.poll(millis * 1_000_000)?.let { sent.add(millis to it.pcm[0].toInt()) }
        }
        assertEquals((0..7).toList(), sent.map { it.second })
        assertEquals((0L..140L step 20).toList(), sent.map { it.first })
        assertEquals(0, pacer.underruns)
        assertEquals(0, pacer.droppedFrames)
    }

    @Test fun schedulingStallCannotProduceCatchupBurst() {
        val pacer = MicrophoneFramePacer(2, interval)
        repeat(6) { pacer.offer(frame(it)) }
        assertNotNull(pacer.poll(0))
        assertNotNull(pacer.poll(83_000_000))
        assertNull(pacer.poll(83_000_000))
        assertNull(pacer.poll(102_999_999))
        assertNotNull(pacer.poll(103_000_000))
    }

    @Test fun startupAndUnderrunWaitForBoundedPrefill() {
        val pacer = MicrophoneFramePacer(2, interval)
        repeat(3) { pacer.offer(frame(it)) }
        assertNull(pacer.poll(0))
        pacer.offer(frame(3))
        repeat(4) { assertNotNull(pacer.poll(it * interval)) }
        assertNull(pacer.poll(4 * interval))
        assertEquals(1, pacer.underruns)
        repeat(3) { pacer.offer(frame(it + 4)) }
        assertNull(pacer.poll(5 * interval))
        pacer.offer(frame(7))
        assertEquals(4, pacer.poll(5 * interval)!!.pcm[0].toInt())
        assertNull(pacer.poll(5 * interval))
    }

    @Test fun overflowKeepsRecentAudioAndPreservesRtpSampleTime() {
        val pacer = MicrophoneFramePacer(2, interval)
        val mutable = frame(0)
        pacer.offer(mutable)
        mutable[0] = 99
        repeat(7) { pacer.offer(frame(it + 1)) }
        val ready = pacer.poll(0)!!
        assertEquals(2, ready.pcm[0].toInt())
        assertEquals(2, ready.skippedFrames)
        assertEquals(2, pacer.droppedFrames)
        val counters = MicrophoneCounters()
        counters.timestamp += ready.skippedFrames * 960
        val packet = MicrophonePacketizer.sealPacket(ByteArray(32), 100, counters, ready.pcm, 960)
        val timestamp = (4..7).fold(0) { value, i -> (value shl 8) or (packet[i].toInt() and 255) }
        assertEquals(1920, timestamp)
        assertEquals(1, counters.sequence)
        assertEquals(2880, counters.timestamp)
        assertEquals(1L, counters.nonce)
        assertEquals(0, pacer.poll(interval)!!.skippedFrames)
        val copyCheck = MicrophoneFramePacer(2, interval, prefillFrames = 1)
        copyCheck.offer(mutable)
        mutable[0] = 42
        assertEquals(99, copyCheck.poll(0)!!.pcm[0].toInt())
    }

    @Test fun wrongFrameLengthIsRejectedAndPollingDelayIsBounded() {
        val pacer = MicrophoneFramePacer(2, interval)
        try { pacer.offer(ByteArray(3)); fail("unaligned PCM accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(4_000_000L, pacer.pollDelayNs(0))
        repeat(4) { pacer.offer(frame(it)) }
        assertNotNull(pacer.poll(0))
        assertEquals(1_000_000L, pacer.pollDelayNs(19_000_000))
        assertEquals(1L, pacer.pollDelayNs(20_000_000))
    }

    @Test fun smallSchedulerDelaysDoNotAccumulateIntoLostSpeechFrames() {
        val pacer = MicrophoneFramePacer(2, interval)
        var now = 0L
        var produced = 0
        var sent = 0
        while (now < 10_000_000_000L) {
            while (produced * interval <= now) pacer.offer(frame(produced++))
            if (pacer.poll(now) != null) sent++
            // Wakes slightly later than the nominal four-millisecond poll.
            now += 4_100_000L
        }
        assertTrue("packet cadence drifted", sent >= 496)
        assertEquals(0, pacer.droppedFrames)
        assertEquals(0, pacer.underruns)
    }
}
