package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val partialMessageTimeoutMillis: Long = IncompleteMessageDeadline.DEFAULT_TIMEOUT_MILLIS,
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private class Peer(val socket: Socket) {
        // Only this peer's reader owns its nonce sequence.
        var readCounter = 0L
    }

    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var activePeer: Peer? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        val bound = bindAny()
        val boundServers = mutableListOf(bound)
        val messages = mutableListOf(
            "AirPlay iAP tunnel listener bound=${bound.localSocketAddress}",
        )
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondary ->
            boundServers += secondary
            messages +=
                "AirPlay iAP tunnel secondary listener bound=" +
                    "${secondary.localSocketAddress}"
        }.onFailure { error ->
            safeClose(secondary)
            messages +=
                "AirPlay iAP tunnel secondary listener failed address=" +
                    "$secondaryAddress port=${bound.localPort}: ${error.message}"
        }
        val acceptThreads = boundServers.map { server ->
            Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
            }
        }
        try {
            synchronized(lifecycleLock) {
                check(!closed.get()) { "iAP tunnel is closed" }
                check(servers.isEmpty()) { "iAP tunnel is already listening" }
                this.listener = listener
                servers += boundServers
                threads += acceptThreads
            }
        } catch (error: Exception) {
            boundServers.forEach(::safeClose)
            throw error
        }
        messages.forEach(listener::onDebug)
        // close() may have run after registration; those workers then see closed immediately.
        acceptThreads.forEach(Thread::start)
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        val (peer, boundServers, acceptThreads) = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            val snapshot = Triple(activePeer, servers.toList(), threads.toList())
            activePeer = null
            servers.clear()
            threads.clear()
            snapshot
        }
        // Closing the claimed socket also releases a reader blocked in InputStream.read().
        safeClose(peer?.socket)
        boundServers.forEach(::safeClose)
        acceptThreads.forEach(Thread::interrupt)
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        if (closed.get()) return
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            val peer = synchronized(lifecycleLock) {
                if (closed.get() || activePeer != null) null
                else Peer(accepted).also { activePeer = it }
            }
            if (peer == null) {
                safeClose(accepted)
                if (closed.get()) return
                listener.onDebug("AirPlay iAP tunnel rejected concurrent peer")
                continue
            }
            // Both address-family accept loops remain available to reject another peer.
            try {
                Thread({ run(peer) }, "airplay-iap-tunnel-peer").apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                release(peer)
                if (!closed.get()) listener.onClosed(error)
            }
        }
    }

    private fun run(peer: Peer) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        val cipherTimes = MessageByteTimes()
        val packageTimes = MessageByteTimes()
        val frameDeadline = IncompleteMessageDeadline("iAP encrypted frame", partialMessageTimeoutMillis) { safeClose(peer.socket) }
        val packageDeadline = IncompleteMessageDeadline("iAP package", partialMessageTimeoutMillis) { safeClose(peer.socket) }
        var failure: Throwable? = null
        var announcedData = false
        try {
            if (closed.get()) return
            peer.socket.setSoLinger(true, 0)
            peerConnected.countDown()
            listener.onOpen(peer.socket.remoteSocketAddress?.toString())
            val input = peer.socket.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                val receivedAt = System.nanoTime()
                frameDeadline.check()
                packageDeadline.check()
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (read == 0) continue
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext += buffer.copyOf(read)
                cipherTimes.append(read, receivedAt)
                val decrypted = decryptFrames(ciphertext, peer)
                cipherTimes.transferAuthenticatedFrames(ciphertext, ciphertext.size - decrypted.second.size, packageTimes)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                frameDeadline.update(cipherTimes.firstNanos)
                val bufferedPlaintext = plaintext.size
                val parsed = parsePackages(plaintext)
                plaintext = parsed.rest
                packageTimes.consume(bufferedPlaintext - plaintext.size)
                packageDeadline.update(packageTimes.firstNanos)
                // Reception is complete before user callbacks: a slow relay must not leave
                // an already completed package's old receive timer armed.
                for (message in parsed.messages) {
                    listener.onDebug("AirPlay iAP tunnel package type=comm body=${message.size}")
                    listener.onIap(message)
                }
            }
        } catch (error: Exception) {
            failure = packageDeadline.failureOr(frameDeadline.failureOr(error))
        } finally {
            frameDeadline.close()
            packageDeadline.close()
            release(peer)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun release(peer: Peer) {
        safeClose(peer.socket)
        synchronized(lifecycleLock) {
            if (activePeer === peer) activePeer = null
        }
    }

    private fun decryptFrames(buffer: ByteArray, peer: Peer): Pair<ByteArray, ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - offset < frameLength) break
            val aad = buffer.copyOfRange(offset, offset + FRAME_HEADER_LEN)
            val sealed = buffer.copyOfRange(offset + FRAME_HEADER_LEN, offset + frameLength)
            val plain = AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(peer.readCounter), sealed, aad,
            )
            peer.readCounter++
            output.add(plain)
            offset += frameLength
        }
        return concatBytes(*output.toTypedArray()) to buffer.copyOfRange(offset, buffer.size)
    }

    private data class ParsedPackages(val messages: List<ByteArray>, val rest: ByteArray)

    private fun parsePackages(buffer: ByteArray): ParsedPackages {
        var offset = 0
        val messages = mutableListOf<ByteArray>()
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            require(size in PACKAGE_HEADER_LEN..MAX_PACKAGE) { "Invalid iAP tunnel package size" }
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                messages += buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size)
            }
            offset += size
        }
        return ParsedPackages(messages, buffer.copyOfRange(offset, buffer.size))
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}
