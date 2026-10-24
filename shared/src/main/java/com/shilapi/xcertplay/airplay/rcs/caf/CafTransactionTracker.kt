package com.shilapi.xcertplay.airplay.rcs.caf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CafPendingTransaction(
    val pluginId: Long,
    val transactionId: Long,
    val requestCommand: CafCommand,
    val expectedResponses: Set<CafCommand>,
)

/**
 * Correlates CAF request/response transactions independently of the stream thread.
 */
class CafTransactionTracker(
    firstTransactionId: Long = 1L,
    private val maxPending: Int = 256,
) {
    private val nextTransactionId = AtomicLong(firstTransactionId)
    private val pending = ConcurrentHashMap<Long, CafPendingTransaction>()

    init {
        require(firstTransactionId > 0) { "CAF transaction IDs must start above zero" }
        require(maxPending > 0) { "CAF pending limit must be positive" }
    }

    @Synchronized
    fun begin(
        pluginId: Long,
        requestCommand: CafCommand,
        expectedResponses: Set<CafCommand>,
    ): CafPendingTransaction {
        require(requestCommand in CafDirectionalMessage.REQUEST_COMMANDS) {
            "${requestCommand.wireName} is not a transaction request"
        }
        require(expectedResponses.isNotEmpty()) {
            "${requestCommand.wireName} must declare at least one expected response"
        }
        require(expectedResponses.all {
            it in CafDirectionalMessage.RESPONSE_COMMANDS || it == CafCommand.GENERAL_ERROR
        }) { "CAF transactions must expect response or error commands" }
        if (pending.size >= maxPending) throw CafProtocolException("CAF pending transaction limit exceeded")
        while (true) {
            val transactionId = nextTransactionId.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            }
            val transaction = CafPendingTransaction(
                pluginId = pluginId,
                transactionId = transactionId,
                requestCommand = requestCommand,
                expectedResponses = expectedResponses,
            )
            if (pending.putIfAbsent(transactionId, transaction) == null) {
                return transaction
            }
        }
    }

    fun accept(reader: CafMessageReader): CafPendingTransaction? {
        // The peer has its own request-number space. An inbound request must never consume a
        // locally pending transaction merely because its numeric ID happens to match.
        if (reader.command !in CafDirectionalMessage.RESPONSE_COMMANDS &&
            reader.command != CafCommand.GENERAL_ERROR
        ) return null
        val transactionId = reader.transactionId ?: return null
        val transaction = pending[transactionId] ?: return null
        if (reader.pluginId != transaction.pluginId) {
            throw CafProtocolException(
                "CAF transaction $transactionId belongs to plugin ${transaction.pluginId}, " +
                    "not ${reader.pluginId}",
            )
        }
        if (reader.command !in transaction.expectedResponses) {
            throw CafProtocolException(
                "CAF transaction $transactionId expected ${transaction.expectedResponses
                    .joinToString { it.wireName }}, got ${reader.command.wireName}",
            )
        }
        return if (pending.remove(transactionId, transaction)) transaction else null
    }

    fun cancel(transaction: CafPendingTransaction): Boolean =
        pending.remove(transaction.transactionId, transaction)

    fun size(): Int = pending.size

    fun clear() = pending.clear()
}
