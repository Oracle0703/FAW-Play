package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    internal const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    internal const val MAX_OBJECTS = 100_000
    internal const val MAX_DEPTH = 64
    private const val MAX_REFERENCES = 262_144

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size in 40..MAX_INPUT_BYTES) { "bplist: invalid input size" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }
        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer width" }
        val count = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val top = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val table = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        require(count in 1..MAX_OBJECTS.toLong()) { "bplist: object budget exceeded" }
        require(top in 0 until count) { "bplist: invalid root reference" }
        require(table >= magic.size && table <= trailer.toLong() - count * offsetSize) {
            "bplist: invalid offset table"
        }
        val offsets = LongArray(count.toInt()) { index ->
            readBigEndianLong(bytes, table + index.toLong() * offsetSize, offsetSize).also {
                require(it >= magic.size && it < table) { "bplist: invalid object offset" }
            }
        }
        return Decoder(bytes, offsets, refSize, table.toInt()).read(top.toInt(), 0)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    /** Acyclic object graph with memoized shared references and explicit allocation budgets. */
    private class Decoder(
        private val bytes: ByteArray,
        private val offsets: LongArray,
        private val refSize: Int,
        private val dataEnd: Int,
    ) {
        private val state = ByteArray(offsets.size)
        private val values = arrayOfNulls<Any>(offsets.size)
        private var referencesLeft = MAX_REFERENCES
        // Distinct IDs may point at overlapping payloads; memoization alone cannot bound copies.
        private var leafBytesLeft = MAX_INPUT_BYTES.toLong() * 4

        fun read(index: Int, depth: Int): Any? {
            require(index in offsets.indices) { "bplist: invalid object reference" }
            require(depth <= MAX_DEPTH) { "bplist: nesting depth exceeded" }
            require(state[index] != 1.toByte()) { "bplist: cyclic reference" }
            if (state[index] == 2.toByte()) return values[index]
            state[index] = 1
            var position = offsets[index].toInt()
            val marker = bytes[position++].toInt() and 0xff
            val type = marker ushr 4
            val nibble = marker and 0x0f

            fun range(length: Long) {
                require(length >= 0 && length <= dataEnd.toLong() - position) { "bplist: truncated object" }
            }
            fun integer(width: Int): Long {
                require(width in 1..8) { "bplist: invalid integer width" }
                range(width.toLong())
                return readBigEndianLong(bytes, position.toLong(), width)
            }
            fun count(): Int {
                if (nibble != 0x0f) return nibble
                range(1)
                val sizeMarker = bytes[position++].toInt() and 0xff
                require((sizeMarker ushr 4) == 1 && (sizeMarker and 0x0f) <= 3) { "bplist: invalid length" }
                val width = 1 shl (sizeMarker and 0x0f)
                val value = integer(width)
                require(value in 0..MAX_INPUT_BYTES.toLong()) { "bplist: length exceeds limit" }
                position += width
                return value.toInt()
            }
            fun reference(offset: Long): Int {
                val value = readBigEndianLong(bytes, offset, refSize)
                require(value >= 0 && value < offsets.size) { "bplist: invalid object reference" }
                return value.toInt()
            }
            fun leaf(length: Long, allocationFactor: Int = 1) {
                range(length)
                val cost = length * allocationFactor
                require(cost <= leafBytesLeft) { "bplist: decoded byte budget exceeded" }
                leafBytesLeft -= cost
            }
            val value: Any? = when (type) {
                0x0 -> when (nibble) {
                    0 -> null
                    8 -> false
                    9 -> true
                    else -> throw IllegalArgumentException("bplist: unsupported primitive")
                }
                0x1 -> { require(nibble <= 3) { "bplist: integer exceeds 64 bits" }; integer(1 shl nibble) }
                0x2 -> when (nibble) {
                    2 -> Float.fromBits(integer(4).toInt())
                    3 -> Double.fromBits(integer(8))
                    else -> throw IllegalArgumentException("bplist: unsupported real width")
                }
                0x3 -> {
                    require(nibble == 3) { "bplist: invalid date width" }
                    java.util.Date(Math.round((Double.fromBits(integer(8)) + APPLE_EPOCH_SECONDS) * 1000))
                }
                0x4 -> { val length = count(); leaf(length.toLong()); bytes.copyOfRange(position, position + length) }
                0x5 -> { val length = count(); leaf(length.toLong(), 2); String(bytes, position, length, Charsets.US_ASCII) }
                0x6 -> { val length = count().toLong() * 2; leaf(length, 2); String(bytes, position, length.toInt(), Charsets.UTF_16BE) }
                0x8 -> integer(nibble + 1)
                0xa, 0xc, 0xd -> {
                    val count = count()
                    val refs = count.toLong() * if (type == 0xd) 2 else 1
                    require(refs <= referencesLeft) { "bplist: reference budget exceeded" }
                    referencesLeft -= refs.toInt()
                    range(refs * refSize)
                    if (type == 0xd) {
                        val dict = LinkedHashMap<String, Any?>(count)
                        for (i in 0 until count) {
                            val key = reference(position + i.toLong() * refSize)
                            val item = reference(position + (count + i).toLong() * refSize)
                            val name = read(key, depth + 1)
                            require(name is String) { "bplist: dictionary key must be a string" }
                            dict[name] = read(item, depth + 1)
                        }
                        dict
                    } else {
                        ArrayList<Any?>(count).also { array ->
                            for (i in 0 until count) array.add(read(reference(position + i.toLong() * refSize), depth + 1))
                        }
                    }
                }
                else -> throw IllegalArgumentException("bplist: unsupported object type")
            }
            values[index] = value
            state[index] = 2
            return value
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(size in 1..8 && offset >= 0 && offset <= bytes.size.toLong() - size) {
            "bplist: invalid integer range"
        }
        var value = 0L
        val base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
