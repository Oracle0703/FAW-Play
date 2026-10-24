package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AudioBacklogTest {
    @Test fun overflowKeepsCurrentAudioWithinPlayableTimeBudget() {
        val backlog = AudioBacklog<Int>(AudioBacklogPolicy(60, 1000)) { 0 }
        repeat(5) { backlog.offer(it, 20_000_000, 100) }
        assertEquals(60L, backlog.queuedMillis)
        assertEquals(listOf(2, 3, 4), List(3) { backlog.poll(0, TimeUnit.MILLISECONDS) })
        assertEquals(2L, backlog.takeDiscarded())
    }

    @Test fun ageLimitExpiresAudioEvenWhenNoNewPacketArrives() {
        var now = 0L
        val backlog = AudioBacklog<String>(AudioBacklogPolicy(100, 150)) { now }
        backlog.offer("stale", 20_000_000, 20)
        now = 150_000_000
        assertNull(backlog.poll(0, TimeUnit.MILLISECONDS))
        assertEquals(1L, backlog.takeDiscarded())
        assertTrue(backlog.offer("current", 20_000_000, 20))
        assertEquals("current", backlog.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test fun oversizedPacketIsRejectedWithoutDiscardingValidQueuedAudio() {
        val backlog = AudioBacklog<Int>(AudioBacklogPolicy(100, 1000)) { 0 }
        backlog.offer(1, 20_000_000, 100)
        assertFalse(backlog.offer(2, 200_000_000, 100))
        assertFalse(backlog.offer(3, 20_000_000, 2_000_000))
        assertEquals(1, backlog.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test fun phoneNavigationAndSpeechKeepDifferentBudgets() {
        fun policy(type: String) = AudioBacklogPolicy.forStream(
            AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100, type), true, 500)
        assertEquals(AudioBacklogPolicy(1000, 1500), policy("media"))
        assertEquals(AudioBacklogPolicy(160, 240), policy("telephony"))
        assertEquals(AudioBacklogPolicy(800, 1500), policy("default"))
        assertEquals(AudioBacklogPolicy(600, 900), policy("speechRecognition"))
    }

    @Test fun packetDurationUsesCodecAndActualSamples() {
        val aac = AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 100)
        assertEquals(1024L * 1_000_000_000 / 48000, AudioPacketDuration.nanos(aac, ByteArray(100)))
        assertEquals(20_000_000L, AudioPacketDuration.nanos(aac.copy(codec = AudioCodecKind.LPCM), ByteArray(12 + 3840)))
        fun opus(toc: Int, count: Int = 0) = AudioPacketDuration.nanos(aac.copy(codec = AudioCodecKind.OPUS),
            ByteArray(14).apply { this[12] = toc.toByte(); this[13] = count.toByte() })
        assertEquals(2_500_000L, opus(16 shl 3))
        assertEquals(60_000_000L, opus(3 shl 3))
        assertEquals(40_000_000L, opus((19 shl 3) or 1))
        assertEquals(120_000_000L, opus((19 shl 3) or 3, 63))
    }
}
