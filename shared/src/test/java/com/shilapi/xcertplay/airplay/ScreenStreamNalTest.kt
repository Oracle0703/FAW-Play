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
class ScreenStreamNalTest {
    @Test fun encryptedFramesUseTheLatestLengthSizeForBothCodecs() {
        val key = ByteArray(32) { it.toByte() }
        val frames = LinkedBlockingQueue<ByteArray>()
        val configured = LinkedBlockingQueue<VideoCodec>()
        val stream = ScreenStream(key)
        val port = stream.listen(object : ScreenStream.Listener {
            private var codec = VideoCodec.H264
            override fun onCodec(codec: VideoCodec) { this.codec = codec }
            override fun onConfig(codecData: ByteArray) { configured.offer(codec) }
            override fun onFrame(naluBytes: ByteArray) { frames.offer(naluBytes) }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val output = socket.getOutputStream()
                var counter = 0L
                for (codec in VideoCodec.entries) {
                    for (lengthSize in listOf(1, 2, 4)) {
                        val config = if (codec == VideoCodec.H264) {
                            byteArrayOf(1, 66, 0, 30, (0xfc or (lengthSize - 1)).toByte(), 0xe1.toByte(), 0, 2, 0x67, 1, 1, 0, 2, 0x68, 1)
                        } else ByteArray(23).apply { this[0] = 1; this[21] = (lengthSize - 1).toByte() }
                        val atom = byteArrayOf(0, 0, 0, 0) +
                            (if (codec == VideoCodec.H264) "avcC" else "hvcC").toByteArray() + config
                        output.write(header(atom.size, 1)); output.write(atom); output.flush()
                        assertEquals(codec, configured.poll(3, TimeUnit.SECONDS))

                        val prefix = ByteArray(lengthSize).apply { this[lastIndex] = 2 }
                        val nal = if (codec == VideoCodec.H264) byteArrayOf(0x65, 1) else byteArrayOf(0x26, 1)
                        val plain = prefix + nal
                        val header = header(plain.size + ScreenCodec.TAG_SIZE, 0)
                        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter++), plain, header)
                        output.write(header); output.write(sealed); output.flush()
                        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + nal, frames.poll(3, TimeUnit.SECONDS))
                    }
                }
            }
        } finally { stream.close() }
    }

    private fun header(size: Int, opcode: Int) = ByteArray(128).apply {
        repeat(4) { this[it] = (size ushr (8 * it)).toByte() }
        this[4] = opcode.toByte()
    }
}
