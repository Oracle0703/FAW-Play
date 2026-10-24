package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application callback for one type-130 RCS stream.
 *
 * The callback receives transport-level [RcsMessage] values. CAF and iAP2 payload decoding belong
 * to the selected channel strategy, not to the stream listener.
 */
interface RcsDataStreamHandler {
    fun onStreamOpened(stream: RcsDataStream) {}
    fun onMessage(stream: RcsDataStream, message: RcsMessage) {}
    /** Serialized receive-poll callback; protocol owners may expire pending operations here. */
    fun onIdle(stream: RcsDataStream) {}
    fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {}
}

/**
 * Selects the application-level codec for a newly created RCS stream.
 *
 * This factory is where CAF, cluster-control, update, and logging handlers are instantiated. It
 * keeps client-type selection independent from the stream's common framing.
 */
fun interface RcsDataStreamHandlerFactory {
    /**
     * Creates an inactive handler. SETUP can end before it is attached, so acquire external
     * resources in onStreamOpened rather than here; an unattached handler receives no callbacks.
     */
    fun create(stream: RcsDataStream): RcsDataStreamHandler?

    /** Declares whether this factory can decode the selected client type. */
    fun supports(clientType: RcsClientType): Boolean = true
}

/**
 * Reusable server-side transport for a CarPlay type-130 data stream.
 *
 * It owns the dual-stack listener, directional ChaCha20-Poly1305 framing, APTransport package
 * framing, peer wait, and callback lifecycle. iAPChannel and the CAF/cluster strategies all use
 * this class; they differ only in the [RcsClientType] used to create them and the handler attached
 * after SETUP.
 */
class RcsDataStream private constructor(
    val clientType: RcsClientType,
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val bindAddress: InetAddress,
    private val expectedPeerAddress: InetAddress?,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val peerConnected = CountDownLatch(1)
    private val stateLock = Object()
    // Application callbacks are serialized, but never run while stateLock is held.
    private val callbackLock = Object()
    private val listeners = mutableListOf<RcsListener>()
    private val acceptThreads = mutableListOf<Thread>()
    private val pending = ArrayDeque<RcsMessage>()
    private var pendingBytes = 0
    private var opened = false
    private var closedCallbackSent = false
    private var closeCause: Throwable? = null
    private var handler: RcsDataStreamHandler? = null
    private var listeningPort: Int? = null

    @Volatile
    private var channel: RcsChannel? = null

    val port: Int
        get() = synchronized(stateLock) {
            listeningPort ?: throw IllegalStateException("RCS data stream has not been opened")
        }

    val isClosed: Boolean
        get() = closed.get()

    fun listen(handler: RcsDataStreamHandler? = null): Int = synchronized(stateLock) {
        check(listeningPort == null) { "RCS data stream is already listening" }
        if (closed.get()) throw IOException("RCS data stream is closed")
        this.handler = handler
        try {
            val primary = RcsChannel.listen(
                readKey = readKey,
                writeKey = writeKey,
                bindAddress = bindAddress,
                onTrace = ::trace,
            )
            listeners += primary
            listeningPort = primary.port

            val secondaryAddress = if (bindAddress is Inet6Address) {
                InetAddress.getByName("0.0.0.0")
            } else {
                InetAddress.getByName("::")
            }
            runCatching {
                RcsChannel.listen(
                    readKey = readKey,
                    writeKey = writeKey,
                    bindAddress = secondaryAddress,
                    port = primary.port,
                    onTrace = ::trace,
                )
            }.onSuccess { listeners += it }
                .onFailure { trace("secondary listener unavailable kind=${it.javaClass.simpleName}") }

            listeners.forEach { listener ->
                acceptThreads += Thread(
                    { acceptLoop(listener) },
                    "carplay-rcs-${clientType.name}-${primary.port}",
                ).apply {
                    isDaemon = true
                    start()
                }
            }
            trace("listening port=${primary.port}")
            primary.port
        } catch (failure: Throwable) {
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
            listeningPort = null
            throw failure
        }
    }

    /** Attaches exactly one application handler after the AirPlay SETUP response has been sent. */
    fun attach(handler: RcsDataStreamHandler) {
        synchronized(callbackLock) {
            val queued: List<RcsMessage>
            val sendOpen: Boolean
            synchronized(stateLock) {
                if (closed.get()) throw IOException("RCS data stream is closed")
                check(this.handler == null) { "RCS data stream already has a handler" }
                this.handler = handler
                sendOpen = opened
                queued = pending.toList()
                pending.clear()
                pendingBytes = 0
            }
            try {
                if (sendOpen) handler.onStreamOpened(this)
                for (message in queued) {
                    if (closed.get()) break
                    handler.onMessage(this, message)
                }
            } catch (failure: Throwable) {
                closeWithCause(failure)
                throw failure
            }
        }
    }

    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS) && channel != null && !closed.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun send(message: RcsMessage) {
        if (!awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("RCS peer is unavailable for ${clientType.name}")
        }
        val active = channel ?: throw IOException("RCS channel is not connected")
        active.send(message)
    }

    internal fun traceProtocol(message: String) = trace(message)

    /** Closes sockets immediately; an existing receive worker delivers the serialized callback. */
    override fun close() = closeWithCause(null)

    private fun closeWithCause(cause: Throwable?) {
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            closeCause = cause
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
            acceptThreads.forEach { if (it !== Thread.currentThread()) it.interrupt() }
            acceptThreads.clear()
            val active = channel
            channel = null
            pending.clear()
            pendingBytes = 0
            peerConnected.countDown()
            runCatching { active?.close() }
            readKey.fill(0)
            writeKey.fill(0)
        }
        // Never wait for application code here: session.close() may run on the UI/control
        // thread while onMessage waits for that same thread. Closing the listeners and socket
        // wakes the already-owned receive workers; their finally path sends the callback.
    }

    private fun notifyClosed() {
        synchronized(callbackLock) {
            val callback = synchronized(stateLock) {
                if (closedCallbackSent) return
                closedCallbackSent = true
                handler to closeCause
            }
            runCatching { callback.first?.onStreamClosed(this, callback.second) }
                .onFailure { trace("close callback failed kind=${it.javaClass.simpleName}") }
        }
    }

    private fun acceptLoop(listener: RcsListener) {
        var accepted: RcsChannel? = null
        var ownsChannel = false
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val candidate = listener.accept(0) ?: continue
                if (expectedPeerAddress != null && candidate.peerAddress != expectedPeerAddress) {
                    candidate.close()
                    trace("rejected peer outside control session")
                    continue
                }
                accepted = candidate
                ownsChannel = synchronized(stateLock) {
                    if (closed.get() || channel != null) false else {
                        channel = candidate
                        // A data stream has one peer. The other address family cannot replace it.
                        listeners.forEach { runCatching { it.close() } }
                        listeners.clear()
                        peerConnected.countDown()
                        true
                    }
                }
                if (!ownsChannel) return
                notifyOpened()
                while (!closed.get()) {
                    val message = candidate.receiveNext(RECEIVE_POLL_MILLIS)
                    if (message != null) dispatch(message)
                    else if (candidate.isPeerEnded || candidate.isClosed) break
                    else notifyIdle()
                }
                break
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { accepted?.close() }
            if (ownsChannel) {
                if (failure != null) trace("stream failed kind=${failure.javaClass.simpleName}")
                closeWithCause(failure)
            }
            if (closed.get()) notifyClosed()
        }
    }

    private fun notifyOpened() {
        synchronized(callbackLock) {
            val callback = synchronized(stateLock) {
                if (closed.get()) return
                opened = true
                handler
            }
            callback?.onStreamOpened(this)
        }
    }

    private fun dispatch(message: RcsMessage) {
        synchronized(callbackLock) {
            val callback = synchronized(stateLock) {
                if (closed.get()) return
                val target = handler
                if (target == null) {
                    if (pending.size >= MAX_PENDING_MESSAGES ||
                        message.body.size > MAX_PENDING_BYTES - pendingBytes
                    ) {
                        throw IOException("RCS pending-message limit exceeded for ${clientType.name}")
                    }
                    pending.addLast(message)
                    pendingBytes += message.body.size
                }
                target
            }
            callback?.onMessage(this, message)
        }
    }

    private fun notifyIdle() {
        synchronized(callbackLock) {
            val callback = synchronized(stateLock) { if (closed.get()) null else handler }
            callback?.onIdle(this)
        }
    }

    private fun trace(message: String) {
        runCatching { onTrace("RCS [${clientType.name}] $message") }
    }

    companion object {
        private const val RECEIVE_POLL_MILLIS = 1_000L
        private const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAX_PENDING_BYTES = 1_048_576
        private const val MAX_PENDING_MESSAGES = 256

        fun open(
            clientType: RcsClientType,
            readKey: ByteArray,
            writeKey: ByteArray,
            bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
            onTrace: (String) -> Unit = {},
            expectedPeerAddress: InetAddress? = null,
        ): RcsDataStream {
            require(readKey.size == 32) { "RCS read key must be 32 bytes" }
            require(writeKey.size == 32) { "RCS write key must be 32 bytes" }
            return RcsDataStream(
                clientType = clientType,
                readKey = readKey.copyOf(),
                writeKey = writeKey.copyOf(),
                bindAddress = if (bindAddress is Inet4Address && !bindAddress.isLoopbackAddress) {
                    InetAddress.getByName("0.0.0.0")
                } else bindAddress,
                expectedPeerAddress = expectedPeerAddress,
                onTrace = onTrace,
            )
        }
    }
}
