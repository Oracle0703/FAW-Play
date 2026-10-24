// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec

/** An actual cluster-control handler; a new target and state machine belong to each opened flow. */
class CarPlayUiSyncRcsFactory(
    private val targetFactory: () -> CarPlayUiSyncTarget,
    private val policy: CarPlayUiSyncInteropPolicy = CarPlayUiSyncInteropPolicy.EXPERIMENTAL_V3,
    private val onReady: (CarPlayUiSyncSession) -> Unit = {},
) : RcsDataStreamHandlerFactory {
    override fun supports(clientType: RcsClientType): Boolean =
        clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL

    override fun create(stream: RcsDataStream): RcsDataStreamHandler? =
        if (supports(stream.clientType)) Handler() else null

    private inner class Handler : RcsDataStreamHandler {
        private var session: CarPlayUiSyncSession? = null

        override fun onStreamOpened(stream: RcsDataStream) {
            check(session == null)
            session = CarPlayUiSyncSession(targetFactory(), { stream.send(it.toRcsMessage()) }, policy)
            stream.traceProtocol("UI Sync waiting for remote reset policy=experimental-reciprocal")
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM ||
                message.body.size !in 1..MAX_UI_SYNC_BODY) {
                throw CarPlayUiSyncProtocolException("Invalid UI Sync carrier or message size")
            }
            val sync = CarPlayUiSyncMessageFactory.decode(message.body)
            val current = checkNotNull(session) { "UI Sync stream has not opened" }
            val wasReady = current.state == CarPlayUiSyncSessionState.READY
            current.receive(sync)
            if (!wasReady && current.state == CarPlayUiSyncSessionState.READY) onReady(current)
            stream.traceProtocol("UI Sync type=${sync.type.wireName} state=${current.state.wireName}")
        }

        override fun onIdle(stream: RcsDataStream) {
            try {
                session?.checkTimeout()
            } catch (failure: CarPlayUiSyncProtocolException) {
                stream.traceProtocol("UI Sync expired pending handshake or acknowledgement")
                throw failure
            }
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            session?.close()
            session = null
        }
    }

    private companion object {
        const val MAX_UI_SYNC_BODY = 4 * 1024 * 1024
    }
}
