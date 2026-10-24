package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val fastPeerDetection: Boolean = false,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        fun onClosed(cause: Throwable?) {}
    }

    private var nalLengthSize = 4
    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val emptyConfigLogged = AtomicBoolean(false)
    private val shortVideoLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            if (closed.get()) { safeClose(accepted); return }
            com.shilapi.xcertplay.network.TcpLiveness.configure(accepted, fastPeerDetection) {
                onDiagnostic("screen $it")
            }
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        var messageOpcode = -1
        var messageBodyBytes = -1
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                messageOpcode = -1
                messageBodyBytes = -1
                stats.reading()
                val header = readFully(input, HEADER_LEN, allowEnd = true) ?: break
                val bodySize = readU32Le(header, 0)
                messageOpcode = header[OPCODE_OFFSET].toInt() and 0xff
                messageBodyBytes = bodySize
                if (bodySize < 0 || bodySize > MAX_BODY) throw IOException("Invalid screen record size")
                val body = readFully(input, bodySize) ?: throw EOFException("Missing screen record body")
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, arrivalNanos, stats)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
            if (!closed.get()) onDiagnostic("screen receiver failed opcode=$messageOpcode " +
                "bodyBytes=$messageBodyBytes error=${error.javaClass.simpleName}")
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, arrivalNanos: Long, stats: StreamReceiveStats) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                // A video record cannot authenticate without its complete tag. Empty records
                // and malformed short records consume no nonce and cannot reach the decoder
                // or satisfy receiver progress. Keep accepting the next sealed record.
                if (body.size < ScreenCodec.TAG_SIZE) {
                    if (body.isNotEmpty() && shortVideoLogged.compareAndSet(false, true))
                        onDiagnostic("screen unauthenticated short video record ignored bytes=${body.size}")
                    return
                }
                val started = System.nanoTime()
                val payload = ScreenCodec.decryptFrame(key, frameCounter.get(), header, body)
                    .also { stats.decrypted(System.nanoTime() - started); frameCounter.incrementAndGet() }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                        "head=${payload.hexPrefix(16)}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload, nalLengthSize), ScreenCodec.senderNanos(header), arrivalNanos)
            }
            OP_VIDEO_CONFIG -> {
                // A header-only message has no codec data. Keep receiving without replacing
                // the last valid configuration or consuming an encrypted frame nonce.
                if (body.isEmpty()) {
                    if (emptyConfigLogged.compareAndSet(false, true))
                        onDiagnostic("screen empty video configuration ignored")
                    return
                }
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                nalLengthSize = ScreenCodec.nalLengthSize(codec, codecData)
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, length: Int, allowEnd: Boolean = false): ByteArray? {
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) {
                if (allowEnd && offset == 0) return null
                throw EOFException("Incomplete screen record")
            }
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /** Configuration/SEI alone is not evidence that a screen supplied its first picture. */
    internal fun hasPictureAccessUnit(codec: VideoCodec, annexB: ByteArray): Boolean {
        val units = MediaCodecSupport.annexBNalUnits(annexB)
        if (units.isEmpty()) return false
        var picture = false
        for (unit in units) {
            if (unit[0].toInt() and 0x80 != 0) return false
            if (codec == VideoCodec.H265) {
                if (unit.size < 2 || unit[1].toInt() and 7 == 0) return false
                if (unit.size > 2 && ((unit[0].toInt() ushr 1) and 0x3f) in 0..31) picture = true
            } else if (unit.size > 1 && (unit[0].toInt() and 0x1f) in 1..5) picture = true
        }
        return picture
    }

    /** NTP 32.32 sender time; keep its clock separate from Android arrival/presentation time. */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < 16) return 0L
        var raw = 0L
        for (index in 15 downTo 8) raw = (raw shl 8) or (header[index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray {
        require(body.size >= TAG_SIZE) { "Truncated encrypted video record" }
        return AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)
    }

    /** Wire data is length-prefixed even when its first length happens to be one. */
    fun lengthPrefixedToAnnexB(payload: ByteArray, lengthSize: Int = 4): ByteArray {
        if (lengthSize != 4) return MediaCodecSupport.toAnnexB(payload, lengthSize, allowAnnexB = false)
        // Validate the entire AU before changing any byte. Four-byte prefixes need no payload copy.
        var offset = 0
        while (offset < payload.size) {
            if (payload.size - offset < 4) return ByteArray(0)
            val length = nalLength4(payload, offset)
            if (length <= 0 || length > payload.size - offset - 4) return ByteArray(0)
            offset += 4 + length
        }
        offset = 0
        while (offset < payload.size) {
            val length = nalLength4(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    private fun nalLength4(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    fun nalLengthSize(codec: VideoCodec, codecData: ByteArray): Int {
        val offset = if (codec == VideoCodec.H265) 21 else 4
        require(codecData.size > offset) { "Truncated video configuration" }
        return ((codecData[offset].toInt() and 3) + 1).also {
            require(it != 3) { "Reserved NAL length size" }
        }
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
