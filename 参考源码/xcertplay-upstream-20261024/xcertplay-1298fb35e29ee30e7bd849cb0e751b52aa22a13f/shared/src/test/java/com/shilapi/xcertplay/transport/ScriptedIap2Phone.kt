package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An in-memory iPhone: a real [Iap2LinkEngine] on the far end of a byte pipe, driven by a script
 * that reacts to each control frame the accessory sends. The script runs on the phone thread.
 */
internal class ScriptedIap2Phone(
    linkConfig: Iap2LinkConfig,
    private val initiateSynchronization: Boolean,
    private val onLinkUp: ScriptedIap2Phone.() -> Unit,
    private val onFrame: ScriptedIap2Phone.(Iap2Frame) -> Unit,
) {
    /** Every control frame the accessory sent, in order. */
    val received = CopyOnWriteArrayList<Iap2Frame>()
    private val toPhone = LinkedBlockingQueue<ByteArray>()
    private val toAccessory = LinkedBlockingQueue<ByteArray>()
    private val engine = Iap2LinkEngine(linkConfig)
    private val framer = Iap2CsmFramer()
    private val outgoing = ArrayList<Iap2Frame>()
    private var hangUpRequested = false
    @Volatile private var failure: Throwable? = null
    private val thread = Thread(::loop, "scripted-iphone").apply { isDaemon = true }

    val accessoryStream = object : BlockingDuplexByteStream {
        private var pending = ByteArray(0)
        private var eof = false

        override fun send(data: ByteArray) {
            toPhone.put(data.copyOf())
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (pending.isEmpty() && !eof) {
                val chunk = toAccessory.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
                if (chunk === EOF) eof = true else pending = chunk
            }
            if (pending.isEmpty()) return ByteArray(0)
            val count = minOf(maxBytes, pending.size)
            return pending.copyOfRange(0, count).also { pending = pending.copyOfRange(count, pending.size) }
        }

        override fun close() {
            toPhone.put(EOF)
        }
    }

    fun start(): ScriptedIap2Phone = apply { thread.start() }

    fun send(frame: Iap2Frame) {
        outgoing += frame
    }

    /** Ends the byte stream once everything sent so far has been written. */
    fun hangUp() {
        hangUpRequested = true
    }

    fun join(timeoutMillis: Long) {
        thread.join(timeoutMillis)
        failure?.let { throw AssertionError("scripted iPhone failed", it) }
    }

    private fun loop() {
        try {
            var linkUp = false
            engine.start(initiateSynchronization, now())
            while (true) {
                val chunk = toPhone.poll(2, TimeUnit.MILLISECONDS)
                if (chunk === EOF) return
                if (chunk != null) engine.feed(chunk, now())
                engine.advanceTime(now())
                while (true) {
                    when (val event = engine.pollEvent() ?: break) {
                        is Iap2LinkEngine.Event.Control -> framer.offer(event.bytes).forEach {
                            received += it
                            onFrame(it)
                        }
                        is Iap2LinkEngine.Event.Writable -> if (event.value && !linkUp) {
                            linkUp = true
                            onLinkUp()
                        }
                        is Iap2LinkEngine.Event.Dead -> throw IllegalStateException("phone link died: ${event.reason}")
                        is Iap2LinkEngine.Event.FileTransfer -> Unit
                    }
                }
                outgoing.forEach { engine.sendControl(it.encodedFrame(), now()) }
                outgoing.clear()
                engine.takeOutput().takeIf { it.isNotEmpty() }?.let(toAccessory::put)
                if (hangUpRequested) {
                    toAccessory.put(EOF)
                    return
                }
            }
        } catch (error: Throwable) {
            failure = error
            toAccessory.put(EOF)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    private companion object {
        val EOF = ByteArray(0)
    }
}
