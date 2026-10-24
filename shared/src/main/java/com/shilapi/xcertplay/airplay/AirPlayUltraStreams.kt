// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.math.BigInteger
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Extra type-130 streams belong to this session, independently of its iAP and video channels. */
internal class AirPlayUltraStreams(
    private val config: AirPlayConfig,
    private val sharedSecret: () -> ByteArray?,
    private val localAddress: () -> InetAddress?,
    private val remoteAddress: () -> InetAddress?,
    private val allocateId: () -> Long,
    private val diagnostic: (String) -> Unit,
    private val reserveSeed: (String) -> Boolean = { true },
) : Closeable {
    private data class Entry(
        val id: Long,
        val clientName: String,
        val stream: RcsDataStream,
        val handler: RcsDataStreamHandler,
        var attached: Boolean = false,
    )
    private val lock = Any()
    private val streams = linkedMapOf<Long, Entry>()
    // Recreating a stream resets its AEAD counters. Never derive that stream's keys from an
    // already-used seed again in the same authenticated control session.
    private val usedSeeds = mutableSetOf<String>()
    private var closed = false
    private val attachments = lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "autunn-ultra-attach").apply { isDaemon = true }
        }
    }

    fun setup(stream: Map<String, Any?>, enabledFeatures: Set<String>): Map<String, Any?>? {
        synchronized(lock) { if (closed) return null }
        if (strictLong(stream["type"]) != 130L) return null
        val client = (stream["clientTypeUUID"] as? String)?.let(RcsClientTypes::findByUuid) ?: return null
        if (client == RcsClientTypes.IAP_CHANNEL) return null
        val feature = client.feature ?: return null
        val ultra = config.ultra ?: return null
        if (!ultra.negotiationAllowed || feature !in ultra.readyFeatures || feature.wireName !in enabledFeatures) return null
        val runtime = ultra.runtime?.takeIf { it.supportsRcsClientType(client) } ?: return null
        try {
            AirPlayFeatureNegotiation.validateEnabledFeatures(config, setOf(feature))
        } catch (_: AirPlayConfigurationException) { return null }
        val seed = unsigned64Decimal(stream["seed"]) ?: return null
        val connectionId = stream["streamConnectionID"]?.let {
            if (unsigned64Decimal(it) == null) return null
            unsignedPlistInteger(it)
        }
        val secret = sharedSecret() ?: return null
        val peer = remoteAddress() ?: return null
        val bind = localAddress() ?: return null
        synchronized(lock) {
            if (closed || usedSeeds.size >= MAX_STREAM_SETUPS || !usedSeeds.add(seed)) return null
        }
        if (!runCatching { reserveSeed(seed) }.getOrDefault(false)) return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            secret, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII), 32,
        )
        val transport = RcsDataStream.open(
            clientType = client,
            readKey = key("DataStream-Output-Encryption-Key"),
            writeKey = key("DataStream-Input-Encryption-Key"),
            bindAddress = bind,
            expectedPeerAddress = peer,
            onTrace = diagnostic,
        )
        try {
            val port = transport.listen()
            val handler = runtime.create(transport) ?: run { transport.close(); return null }
            val id = allocateId()
            val previous = synchronized(lock) {
                if (closed) { transport.close(); return null }
                require(id >= FIRST_ULTRA_STREAM_ID && id !in streams) { "Invalid allocated RCS stream ID" }
                // Re-SETUP replaces only this client's previous stream. Old TEARDOWN IDs cannot
                // close its replacement, another RCS client, the iAP tunnel or video settings.
                val old = streams.values.filter { it.clientName == client.name }
                old.forEach { streams.remove(it.id) }
                streams[id] = Entry(id, client.name, transport, handler)
                old
            }
            previous.forEach { it.stream.close() }
            emit("Ultra stream accepted client=${client.name} streamID=$id")
            return linkedMapOf<String, Any?>("type" to 130, "streamID" to id, "dataPort" to port).apply {
                connectionId?.let { this["streamConnectionID"] = it }
            }
        } catch (error: Exception) {
            transport.close()
            emit("Ultra stream rejected client=${client.name} error=${error.javaClass.simpleName}")
            return null
        }
    }

    /** Only called after the RTSP batch has been flushed; handlers cannot race their SETUP reply. */
    fun responseSent() {
        val (pending, executor) = synchronized(lock) {
            if (closed) return
            val pending = streams.values.filter { !it.attached }.onEach { it.attached = true }.toList()
            if (pending.isEmpty()) return
            // Initialize under the same lock as close so an executor cannot be created after
            // close has already decided that there was no executor to shut down.
            pending to attachments.value
        }
        try {
            executor.execute {
                pending.forEach { entry ->
                    val current = synchronized(lock) { !closed && streams[entry.id] === entry }
                    if (current) try {
                        entry.stream.attach(entry.handler)
                    } catch (error: Exception) {
                        synchronized(lock) { streams.remove(entry.id, entry) }
                        entry.stream.close()
                        emit("Ultra stream attachment failed client=${entry.clientName} error=${error.javaClass.simpleName}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { pending.forEach { streams.remove(it.id, it) } }
            pending.forEach { it.stream.close() }
        }
    }

    fun teardown(body: Any?) {
        val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty()
        val all = entries.isEmpty()
        val ids = entries.mapNotNull { item ->
            val stream = item as? Map<*, *> ?: return@mapNotNull null
            if (strictLong(stream["type"]) != 130L) return@mapNotNull null
            strictLong(stream["streamID"])
        }.toSet()
        val allData = entries.any { item ->
            val stream = item as? Map<*, *> ?: return@any false
            strictLong(stream["type"]) == 130L && !stream.containsKey("streamID")
        }
        val ending = synchronized(lock) {
            streams.values.filter { all || allData || it.id in ids }.also { list ->
                list.forEach { streams.remove(it.id) }
            }
        }
        ending.forEach { it.stream.close() }
    }

    override fun close() {
        val ending = synchronized(lock) {
            if (closed) return
            closed = true
            streams.values.toList().also { streams.clear() }
        }
        ending.forEach { it.stream.close() }
        if (attachments.isInitialized()) attachments.value.shutdownNow()
    }

    private fun emit(message: String) { runCatching { diagnostic(message) } }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> null
    }

    private fun unsigned64Decimal(value: Any?): String? = when (value) {
        is Long, is Int, is Short, is Byte -> unsignedPlistDecimal(value)
        is BigInteger -> value.takeIf { it.signum() >= 0 && it.bitLength() <= 64 }?.toString()
        else -> null
    }

    companion object {
        private const val FIRST_ULTRA_STREAM_ID = 3L
        private const val MAX_STREAM_SETUPS = 256
    }
}
