package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter

/**
 * Selector and minimum intervals belong to the receiving control link. Parameter 0 is the iAP2
 * MinimumIntervalInMilliseconds between LocationInformation messages; 0x8001..0x8007 are the
 * newer per-sentence intervals seen from current iPhones.
 */
class Iap2LocationSubscription(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var components: Set<Int> = emptySet()
        private set
    private var intervals = emptyMap<Int, Long>()
    private var messageInterval: Long? = null
    private var lastMessage: Long? = null
    private val lastSent = mutableMapOf<Int, Long>()
    private val lastSentence = mutableMapOf<Int, String>()
    fun request(frame: Iap2Frame, supported: Set<Int> = setOf(1, 2, 4)): Boolean {
        stop()
        return try {
            val fields = Iap2BodyReader.of(frame).list()
            require(fields.none { it.id in 1..7 && it.payload.isNotEmpty() })
            require(fields.none { (it.id == 0 || it.id in 0x8000..0x8007) && it.payload.size != 4 })
            components = fields.filter { it.id in 1..7 }.map { it.id }.toSet().intersect(supported)
            intervals = fields.filter { it.id in 0x8001..0x8007 }.groupBy { it.id - 0x8000 }
                .mapValues { (_, fields) -> fields.maxOf(::uint32) }
            messageInterval = fields.filter { it.id == 0 }.maxOfOrNull(::uint32)
            components.isNotEmpty()
        } catch (_: Exception) { stop(); false }
    }
    fun filter(nmea: String): String? {
        val timestamp = now()
        if (lastMessage?.let { timestamp - it < messageInterval() } == true) return null
        val selected = nmea.split("\r\n").filter { sentence ->
            val id = when {
                sentence.startsWith("\$GPGGA,") -> 1
                sentence.startsWith("\$GPRMC,") -> 2
                sentence.startsWith("\$PASCD,") -> 4
                else -> return@filter false
            }
            if (id !in components) return@filter false
            // A position sentence is resent only for a new fix; wheel speed may legitimately repeat.
            if (id != 4 && lastSentence[id] == sentence) return@filter false
            val last = lastSent[id]
            if (last != null && timestamp - last < interval(id)) return@filter false
            lastSent[id] = timestamp
            lastSentence[id] = sentence
            true
        }
        if (selected.isEmpty()) return null
        lastMessage = timestamp
        return selected.joinToString("\r\n", postfix = "\r\n")
    }
    /** Time until the earliest subscribed sentence may be sent again; 0 when one is already due. */
    fun millisUntilDue(): Long? {
        if (components.isEmpty()) return null
        val timestamp = now()
        val sentence = components.minOf { id ->
            val last = lastSent[id] ?: return@minOf 0L
            (last + interval(id) - timestamp).coerceAtLeast(0)
        }
        val message = lastMessage?.let { (it + messageInterval() - timestamp).coerceAtLeast(0) } ?: 0L
        return maxOf(sentence, message)
    }
    private fun messageInterval() = (messageInterval ?: 0L).coerceAtLeast(MIN_INTERVAL_MILLIS)
    private fun interval(id: Int) =
        (intervals[id] ?: messageInterval ?: DEFAULT_INTERVAL_MILLIS).coerceAtLeast(MIN_INTERVAL_MILLIS)
    private fun uint32(field: Iap2Parameter): Long =
        field.payload.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    fun stop() {
        components = emptySet(); intervals = emptyMap(); messageInterval = null; lastMessage = null
        lastSent.clear(); lastSentence.clear()
    }

    private companion object {
        /** Used when the phone gives neither a per-sentence nor a message interval. */
        const val DEFAULT_INTERVAL_MILLIS = 1_000L
        /** Guards against a zero or tiny phone interval flooding the control link. */
        const val MIN_INTERVAL_MILLIS = 100L
    }
}
