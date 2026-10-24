package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScreenStreamEmptyConfigTest {
    @Test fun emptyConfigKeepsH264SocketOpenUntilRealConfigAndFramesArrive() {
        assertEmptyConfigsPreserveStream(VideoCodec.H264)
    }

    @Test fun emptyConfigKeepsH265SocketOpenUntilRealConfigAndFramesArrive() {
        assertEmptyConfigsPreserveStream(VideoCodec.H265)
    }

    @Test fun nonemptyTruncatedConfigStillClosesTheSocketWithAValidationFailure() {
        val events = LinkedBlockingQueue<Event>()
        val stream = ScreenStream(ByteArray(32))
        val port = stream.listen(listener(events))
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().apply {
                    write(header(1, 1)); write(byteArrayOf(1)); flush()
                }
                val event = events.poll(3, TimeUnit.SECONDS)
                assertTrue("Truncated configuration must fail before publishing codec/config: $event", event is Closed)
                val failure = (event as Closed).cause
                assertTrue("Expected a validation failure, got $failure", failure is IllegalArgumentException)
                assertEquals("Truncated video configuration", failure?.message)
            }
        } finally { stream.close() }
    }

    private fun assertEmptyConfigsPreserveStream(codec: VideoCodec) {
        val key = ByteArray(32) { (it * 3).toByte() }
        val events = LinkedBlockingQueue<Event>()
        val stream = ScreenStream(key)
        val port = stream.listen(listener(events))
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val output = socket.getOutputStream()
                var frameCounter = 0L
                for (lengthSize in listOf(1, 2, 4)) {
                    // The phone may announce an empty configuration before it has codec data.
                    output.write(header(0, 1))
                    val config = codecConfig(codec, lengthSize)
                    val atom = ByteArray(4) +
                        (if (codec == VideoCodec.H264) "avcC" else "hvcC").toByteArray() + config
                    output.write(header(atom.size, 1)); output.write(atom); output.flush()
                    // Publishing codec/config only once also proves the empty message was not
                    // forwarded to the decoder before this actual configuration.
                    assertEquals(Codec(codec), events.poll(3, TimeUnit.SECONDS))
                    val configured = events.poll(3, TimeUnit.SECONDS)
                    assertTrue("Expected real codec data, got $configured", configured is Configuration)
                    assertArrayEquals(config, (configured as Configuration).bytes)

                    repeat(2) { frameIndex ->
                        if (frameIndex == 1) output.write(header(0, 1))
                        val nal = if (codec == VideoCodec.H264) byteArrayOf(0x65, lengthSize.toByte())
                            else byteArrayOf(0x26, lengthSize.toByte())
                        val prefix = ByteArray(lengthSize).apply { this[lastIndex] = nal.size.toByte() }
                        val plain = prefix + nal
                        val frameHeader = header(plain.size + ScreenCodec.TAG_SIZE, 0)
                        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(frameCounter++), plain, frameHeader)
                        output.write(frameHeader); output.write(sealed); output.flush()
                        val frame = events.poll(3, TimeUnit.SECONDS)
                        assertTrue("Expected a frame after empty config, got $frame", frame is Frame)
                        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + nal, (frame as Frame).bytes)
                    }
                }
                assertTrue("Empty configurations must not publish extra callbacks", events.isEmpty())
            }
        } finally { stream.close() }
    }

    private fun listener(events: LinkedBlockingQueue<Event>) = object : ScreenStream.Listener {
        override fun onCodec(codec: VideoCodec) { events.offer(Codec(codec)) }
        override fun onConfig(codecData: ByteArray) { events.offer(Configuration(codecData)) }
        override fun onFrame(naluBytes: ByteArray) { events.offer(Frame(naluBytes)) }
        override fun onClosed(cause: Throwable?) { events.offer(Closed(cause)) }
    }

    private fun codecConfig(codec: VideoCodec, lengthSize: Int): ByteArray = if (codec == VideoCodec.H264) {
        byteArrayOf(1, 66, 0, 30, (0xfc or (lengthSize - 1)).toByte(), 0xe1.toByte(), 0, 2, 0x67, 1, 1, 0, 2, 0x68, 1)
    } else ByteArray(23).apply { this[0] = 1; this[21] = (lengthSize - 1).toByte() }

    private fun header(size: Int, opcode: Int) = ByteArray(128).apply {
        repeat(4) { this[it] = (size ushr (8 * it)).toByte() }
        this[4] = opcode.toByte()
    }

    private sealed interface Event
    private data class Codec(val codec: VideoCodec) : Event
    private data class Configuration(val bytes: ByteArray) : Event
    private data class Frame(val bytes: ByteArray) : Event
    private data class Closed(val cause: Throwable?) : Event
}
