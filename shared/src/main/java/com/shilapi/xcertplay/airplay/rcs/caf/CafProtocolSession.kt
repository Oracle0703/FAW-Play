package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reusable CAF transport service over one CarPlayProtocolData/Data2 RCS stream.
 *
 * The session owns carrier framing and request/response correlation. Plugin semantics are split
 * across [CafPluginHandler] callbacks, while config-tree parsing is selected by
 * [CafConfigTreeDecoderFactory].
 */
class CafProtocolSession(
    val clientType: RcsClientType,
    val stream: RcsDataStream,
    private val plugins: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
    val transactions: CafTransactionTracker = CafTransactionTracker(),
) : RcsDataStreamHandler, Closeable {
    private val opened = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()
    private val openedHandlers = mutableListOf<CafPluginHandler>()

    init {
        require(clientType.payloadStyle == RcsPayloadStyle.CAF_BINARY_PLIST_OPACK) {
            "${clientType.name} is not a CAF binary-plist OPACK channel"
        }
        require(plugins.supports(clientType)) {
            "No CAF plugin is registered for ${clientType.name}"
        }
    }

    fun send(frame: CafRcsFrame) {
        requirePluginChannel(frame.envelope.pluginId)
        traceFrame("TX", frame.envelope, frame.rcsBody)
        stream.send(frame.asRcsMessage())
    }

    fun requestConfig(pluginId: Long): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.CONFIG_REQUEST,
            expectedResponses = setOf(CafCommand.CONFIG_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        sendTracked(transaction) { CarAccessoryMessages.configRequest(pluginId, transaction.transactionId) }
        return transaction
    }

    fun register(pluginId: Long, registration: CafRegistration): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.REGISTER_REQUEST,
            expectedResponses = setOf(
                CafCommand.REGISTER_RESPONSE,
                CafCommand.GENERAL_ERROR,
            ),
        )
        sendTracked(transaction) {
            CarAccessoryMessages.registerRequest(
                pluginId,
                transaction.transactionId,
                registration,
            )
        }
        return transaction
    }

    fun unregister(pluginId: Long, identifiers: List<Long>): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.UNREGISTER_REQUEST,
            expectedResponses = setOf(
                CafCommand.UNREGISTER_RESPONSE,
                CafCommand.GENERAL_ERROR,
            ),
        )
        sendTracked(transaction) {
            CarAccessoryMessages.unregisterRequest(
                pluginId,
                transaction.transactionId,
                identifiers,
            )
        }
        return transaction
    }

    fun read(pluginId: Long, identifiers: List<Long>): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.READ_REQUEST,
            expectedResponses = setOf(CafCommand.READ_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        sendTracked(transaction) { CarAccessoryMessages.readRequest(pluginId, transaction.transactionId, identifiers) }
        return transaction
    }

    fun write(pluginId: Long, values: Map<Long, Any?>): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.WRITE_REQUEST,
            expectedResponses = setOf(CafCommand.WRITE_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        sendTracked(transaction) { CarAccessoryMessages.writeRequest(pluginId, transaction.transactionId, values) }
        return transaction
    }

    fun control(pluginId: Long, values: Map<Long, Any?>): CafPendingTransaction {
        val transaction = transactions.begin(
            pluginId = pluginId,
            requestCommand = CafCommand.CONTROL_REQUEST,
            expectedResponses = setOf(CafCommand.CONTROL_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        sendTracked(transaction) { CarAccessoryMessages.controlRequest(pluginId, transaction.transactionId, values) }
        return transaction
    }

    private fun sendTracked(transaction: CafPendingTransaction, frame: () -> CafRcsFrame) {
        try {
            send(frame())
        } catch (failure: Throwable) {
            transactions.cancel(transaction)
            throw failure
        }
    }

    fun decoderFor(pluginId: Long): CafConfigTreeDecoder {
        val registration = plugins.requireRegistration(pluginId)
        return registration.configTreeDecoder
            ?: decoderFactory.create(registration.protocolVersion, registration.pluginConfig)
    }

    override fun onStreamOpened(stream: RcsDataStream) {
        synchronized(lifecycleLock) {
            if (closed.get() || !opened.compareAndSet(false, true)) return
            // A handler can own multiple registrations. Its transport lifecycle still occurs once.
            val handlers = plugins.registrationsFor(clientType).map { it.handler }
                .fold(mutableListOf<CafPluginHandler>()) { unique, handler ->
                    if (unique.none { it === handler }) unique.add(handler)
                    unique
                }
            try {
                handlers.forEach { handler ->
                    if (closed.get()) return
                    openedHandlers.add(handler)
                    handler.onSessionOpened(this)
                }
            } catch (failure: Throwable) {
                retireHandlers()
                throw failure
            }
        }
    }

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CafProtocolException(
                "CAF channel received unsupported RCS messageType " +
                    "0x${message.messageType.toString(16)}",
            )
        }
        val reader = CarAccessoryMessages.reader(message.body)
        traceFrame("RX", reader.envelope, message.body)
        requirePluginChannel(reader.pluginId)
        val pending = transactions.accept(reader)
        CafInboundDispatcher.dispatch(
            session = this,
            handler = plugins.require(reader.pluginId),
            message = reader,
            pending = pending,
        )
    }

    override fun close() {
        transactions.clear()
        stream.close()
        retireHandlers()
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        transactions.clear()
        retireHandlers()
    }

    private fun retireHandlers() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            // Closing one plugin must not leave the remaining plugins subscribed to vehicle data.
            openedHandlers.toList().asReversed().forEach { handler ->
                runCatching { handler.onSessionClosed(this) }
            }
            openedHandlers.clear()
        }
    }

    private fun requirePluginChannel(pluginId: Long) {
        if (clientType !in plugins.requireRegistration(pluginId).clientTypes) {
            throw CafProtocolException("CAF plugin $pluginId is not registered for ${clientType.name}")
        }
    }

    private fun traceFrame(
        direction: String,
        envelope: CafEnvelope,
        body: ByteArray,
    ) {
        val message = envelope.message
        stream.traceProtocol(
            "CAF $direction pluginID=${envelope.pluginId} " +
                "command=${message.command.wireName} " +
                "transactionID=${message.transactionId ?: "none"} " +
                "error=${message.error ?: "none"} " +
                "body=${body.size}B",
        )
    }

    companion object {
        fun handlerFactory(
            plugins: CafPluginRegistry,
            decoderFactory: CafConfigTreeDecoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
        ): RcsDataStreamHandlerFactory = CafRcsHandlerFactory(plugins, decoderFactory)
    }
}

private class CafRcsHandlerFactory(
    private val plugins: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
) : RcsDataStreamHandlerFactory {
    init {
        plugins.allRegistrations().forEach { registration ->
            registration.configTreeDecoder
                ?: decoderFactory.create(
                    registration.protocolVersion,
                    registration.pluginConfig,
                )
        }
    }

    override fun supports(clientType: RcsClientType): Boolean = plugins.supports(clientType)

    override fun create(stream: RcsDataStream): RcsDataStreamHandler? =
        if (plugins.supports(stream.clientType)) {
            CafProtocolSession(
                clientType = stream.clientType,
                stream = stream,
                plugins = plugins,
                decoderFactory = decoderFactory,
            )
        } else {
            null
        }
}

internal object CafInboundDispatcher {
    fun dispatch(
        session: CafProtocolSession,
        handler: CafPluginHandler,
        message: CafMessageReader,
        pending: CafPendingTransaction?,
    ) {
        if (pending != null) {
            handler.onTransactionCompleted(session, pending, message)
        }
        when (message.command) {
            CafCommand.CONFIG_REQUEST -> handler.onConfigRequest(session, message)
            CafCommand.CONFIG_RESPONSE -> handler.onConfigResponse(session, message)
            CafCommand.CONFIG_NOTIFY -> handler.onConfigNotify(session, message)
            CafCommand.REGISTER_REQUEST -> handler.onRegisterRequest(session, message)
            CafCommand.REGISTER_RESPONSE -> handler.onRegisterResponse(session, message)
            CafCommand.UNREGISTER_REQUEST -> handler.onUnregisterRequest(session, message)
            CafCommand.UNREGISTER_RESPONSE -> handler.onUnregisterResponse(session, message)
            CafCommand.READ_REQUEST -> handler.onReadRequest(session, message)
            CafCommand.READ_RESPONSE -> handler.onReadResponse(session, message)
            CafCommand.WRITE_REQUEST -> handler.onWriteRequest(session, message)
            CafCommand.WRITE_RESPONSE -> handler.onWriteResponse(session, message)
            CafCommand.CONTROL_REQUEST -> handler.onControlRequest(session, message)
            CafCommand.CONTROL_RESPONSE -> handler.onControlResponse(session, message)
            CafCommand.CONTROL_NOTIFY -> handler.onControlNotify(session, message)
            CafCommand.UPDATE_NOTIFY -> handler.onUpdateNotify(session, message)
            CafCommand.GENERAL_ERROR -> handler.onGeneralError(session, message)
        }
    }
}
