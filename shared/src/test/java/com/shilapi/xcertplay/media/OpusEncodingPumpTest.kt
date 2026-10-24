package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class OpusEncodingPumpTest {
    @Test fun busyInputRetainsSamePcmAndTimestampUntilOutputIsDrained() {
        var busy = true
        val queued = mutableListOf<Pair<Int, Long>>()
        val ready = ArrayList<OpusCodecOutput>()
        val pump = OpusEncodingPump(input = { pcm, pts ->
            if (busy) false else { queued += (pcm[0].toInt() to pts); ready += OpusCodecOutput(pcm, pts); true }
        }, drain = { ready.toList().also { ready.clear() } }, nowNs = { 0 })
        val pcm = byteArrayOf(1)
        assertTrue(pump.encode(pcm, 320).isEmpty())
        pcm[0] = 99 // The caller reuses its AudioRecord buffer.
        busy = false
        val result = pump.encode(byteArrayOf(2), 640)
        assertEquals(listOf(1 to 0L, 2 to 20000L), queued)
        assertEquals(listOf(320, 640), result.map { it.rtpTimestamp })
        assertEquals(0, pump.pendingFrames)
    }

    @Test fun skippedExpiredInputsLeaveAGapInWireTime() {
        var busy = true
        var now = 0L
        val ready = ArrayList<OpusCodecOutput>()
        val pump = OpusEncodingPump({ pcm, pts ->
            if (busy) false else { ready += OpusCodecOutput(pcm, pts); true }
        }, { ready.toList().also { ready.clear() } }, { now })
        pump.encode(byteArrayOf(1), 0)
        now = 80_000_000
        busy = false
        val result = pump.encode(byteArrayOf(2), 1280)
        assertEquals(listOf(1280), result.map { it.rtpTimestamp })
        assertEquals(1L, pump.droppedFrames)
    }

    @Test fun encoderDelayAndRtpWrapPreserveTheOriginalSourceFrame() {
        val ready = ArrayList<OpusCodecOutput>()
        val pump = OpusEncodingPump({ pcm, pts -> ready += OpusCodecOutput(pcm, pts - 6500); true },
            { ready.toList().also { ready.clear() } }, { 0 })
        assertEquals(Int.MAX_VALUE - 100, pump.encode(byteArrayOf(1), Int.MAX_VALUE - 100).single().rtpTimestamp)
        assertEquals(Int.MIN_VALUE + 219, pump.encode(byteArrayOf(2), Int.MIN_VALUE + 219).single().rtpTimestamp)
    }

    @Test fun stalledCodecHasBoundedPendingAndInFlightMemory() {
        val pump = OpusEncodingPump(input = { _, _ -> true }, drain = { emptyList() }, nowNs = { 0 })
        repeat(1000) { assertTrue(pump.encode(ByteArray(1920), it * 320).isEmpty()) }
        assertEquals(3, pump.pendingFrames)
        assertEquals(989L, pump.droppedFrames) // eight accepted by codec, three pending
    }

    @Test fun missingOutputsExpireWithoutTrappingOrRetimingFutureSpeech() {
        var now = 0L
        val submitted = mutableListOf<Long>()
        val ready = ArrayList<OpusCodecOutput>()
        val pump = OpusEncodingPump({ _, pts -> submitted += pts; true },
            { ready.toList().also { ready.clear() } }, { now })
        repeat(8) { pump.encode(byteArrayOf(it.toByte()), it * 320) }
        assertEquals(8, submitted.size)
        now = 300_000_000
        pump.encode(byteArrayOf(8), 4800)
        assertEquals("Expired inputs cannot permanently stop the encoder", 9, submitted.size)
        ready += OpusCodecOutput(byteArrayOf(0), 0) // expired output arrives after the newer input
        ready += OpusCodecOutput(byteArrayOf(8), submitted.last())
        val outputs = pump.encode(byteArrayOf(9), 5120)
        assertEquals(listOf(4800), outputs.map { it.rtpTimestamp })
        assertEquals(8L, pump.droppedFrames)
        assertEquals(1L, pump.unmatchedOutputs)
    }

    @Test fun delayedOutputCannotBeAssignedToANewerInputOrReplayStaleSpeech() {
        var now = 0L
        val submitted = mutableListOf<Long>()
        val ready = ArrayList<OpusCodecOutput>()
        val pump = OpusEncodingPump({ _, pts -> submitted += pts; true },
            { ready.toList().also { ready.clear() } }, { now })
        pump.encode(byteArrayOf(1), 100)
        now = 300_000_000
        ready += OpusCodecOutput(byteArrayOf(1), submitted.first())
        assertTrue(pump.encode(byteArrayOf(2), 4900).isEmpty())
        assertEquals(1L, pump.droppedFrames)
        ready += OpusCodecOutput(byteArrayOf(2), submitted.last())
        assertEquals(4900, pump.encode(byteArrayOf(3), 5220).single().rtpTimestamp)
    }
}
