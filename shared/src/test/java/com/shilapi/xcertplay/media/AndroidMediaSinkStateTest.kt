package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class AndroidMediaSinkStateTest {
    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }

    @Test fun aFailedCloseCanRetryItsPendingCleanupWithoutAcceptingOldPackets() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var failCallback = true
        val sink = AndroidMediaSink(onScreenStreamActiveChanged = { type, active ->
            if (!active && failCallback) throw IllegalStateException("temporary callback failure")
            events.add(type to active)
        })
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        try {
            sink.close()
            fail("Expected the incomplete close to be reported")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("screen state callback"))
        }
        assertFalse(field<Boolean>(sink, "sinkResourcesReleased"))
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96)
        sink.onAudioStarted(id, format, 0)
        sink.onAudioRtp(id, format, ByteArray(0), 0)
        sink.onMicrophoneStarted(id, MicrophoneConfig("telephony", 48_000, 1, 100, 20,
            InetAddress.getLoopbackAddress(), 1, ByteArray(32)))
        sink.onScreenStreamActive(112, true)
        assertTrue(field<Map<*, *>>(sink, "audioRenderers").isEmpty())
        assertTrue(field<Map<*, *>>(sink, "microphoneUplinks").isEmpty())
        failCallback = false
        sink.close()
        assertTrue(field<Boolean>(sink, "sinkResourcesReleased"))
        assertEquals(listOf(110 to true, 111 to true, 110 to false, 111 to false), events)
        sink.close()
        assertEquals(4, events.size)
    }

    private fun <T> field(target: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    }
}
