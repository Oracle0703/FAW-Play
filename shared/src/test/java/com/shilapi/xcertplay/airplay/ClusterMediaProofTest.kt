package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise authenticated wire records through the real receiver, media owner and proof monitor. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class ClusterMediaProofTest {
    @Test fun shortClearRecordsCannotReachTheDecoderOrProveProgressAndDoNotConsumeTheNextNonce() {
        val shared = ByteArray(32) { it.toByte() }
        val configured = CountDownLatch(1)
        val frames = LinkedBlockingQueue<ByteArray>()
        lateinit var renderer: (String) -> Unit
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { renderer = handler }
            override fun onVideoConfig(type: Int, codecData: ByteArray) { configured.countDown() }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames.offer(naluBytes) }
        })
        val session = AirPlaySession(Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
                AirPlayDisplayConfig(960, 540), cluster = AirPlayDisplayConfig(1600, 600,
                    initialUrl = CarPlayClusterDisplay.MAP_URL)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
        try {
            PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(session.pairVerify, shared)
            val response = session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
                .invoke(session, listOf(mapOf("type" to 111L, "streamConnectionID" to 1L))) as List<*>
            val port = (response.single() as Map<*, *>)["dataPort"] as Int
            val event = object : Socket() {
                private val output = ByteArrayOutputStream()
                override fun getOutputStream(): OutputStream = output
            }
            for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
                session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            session.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(session)
            assertTrue(session.clusterContentProgress()!!.requestWritten)
            Socket(InetAddress.getLoopbackAddress(), port).use { peer ->
                val output = peer.getOutputStream()
                val clearPicture = byteArrayOf(0, 0, 0, 2, 0x65, 0x80.toByte())
                output.write(header(0, 0)) // Ignored empty video record.
                output.write(header(clearPicture.size, 0)); output.write(clearPicture)
                val config = byteArrayOf(1, 66, 0, 30, 0xff.toByte(), 0xe1.toByte(), 0, 2, 0x67, 1, 1, 0, 2, 0x68, 1)
                val atom = ByteArray(4) + "avcC".toByteArray() + config
                output.write(header(atom.size, 1)); output.write(atom); output.flush()
                assertTrue(configured.await(3, TimeUnit.SECONDS)) // Prior records were processed in TCP order.
                assertTrue(frames.isEmpty())
                assertFalse(session.clusterContentProgress()!!.validVideoReceived)
                assertFalse(session.clusterContentProgress()!!.frameRendered)

                val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
                    "DataStream-Output-Encryption-Key".toByteArray(), 32)
                val frameHeader = header(clearPicture.size + ScreenCodec.TAG_SIZE, 0)
                // Nonce zero proves ignored short/empty records did not advance the sealed stream.
                output.write(frameHeader)
                output.write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), clearPicture, frameHeader))
                output.flush()
                assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()), frames.poll(3, TimeUnit.SECONDS))
                assertTrue(session.clusterContentProgress()!!.validVideoReceived)
                assertFalse(session.clusterContentProgress()!!.frameRendered)
                renderer("first frame rendered")
                assertTrue(session.clusterContentProgress()!!.frameRendered)
            }
        } finally { session.close() }
    }

    private fun header(size: Int, opcode: Int) = ByteArray(128).apply {
        repeat(4) { this[it] = (size ushr (8 * it)).toByte() }
        this[4] = opcode.toByte()
    }
}
