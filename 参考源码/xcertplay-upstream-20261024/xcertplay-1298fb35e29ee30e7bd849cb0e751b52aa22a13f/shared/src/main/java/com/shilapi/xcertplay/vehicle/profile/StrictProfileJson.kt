package com.shilapi.xcertplay.vehicle.profile

/** Bounded JSON with duplicate-key rejection. No expression or executable configuration support. */
internal class StrictProfileJson(private val source: String) {
    private var index = 0
    fun parse(): Map<String, Any?> {
        require(source.length <= 1_048_576) { "Profile exceeds 1 MiB" }
        val value = value(0); space(); require(index == source.length) { "Trailing JSON" }
        @Suppress("UNCHECKED_CAST") return value as? Map<String, Any?> ?: error("JSON root must be object")
    }
    private fun space() { while (index < source.length && source[index] in " \r\n\t") index++ }
    private fun value(depth: Int): Any? {
        require(depth <= 24) { "JSON nesting limit" }; space(); require(index < source.length) { "Incomplete JSON" }
        return when (source[index]) {
            '{' -> { index++; val map = linkedMapOf<String, Any?>(); space()
                if (source.getOrNull(index) == '}') { index++; map } else {
                    do { space(); val key = string(); require(!map.containsKey(key)) { "Duplicate key: $key" }; space(); expect(':'); map[key] = value(depth + 1); space()
                        if (source.getOrNull(index) != ',') break; index++
                    } while (true); expect('}'); map
                }
            }
            '[' -> { index++; val list = arrayListOf<Any?>(); space()
                if (source.getOrNull(index) == ']') { index++; list } else {
                    do { require(list.size < 1000) { "JSON array limit" }; list += value(depth + 1); space(); if (source.getOrNull(index) != ',') break; index++ } while (true)
                    expect(']'); list
                }
            }
            '"' -> string()
            't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
            else -> { val start = index; while (index < source.length && source[index] in "-+0123456789.eE") index++
                val token = source.substring(start, index)
                require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "Invalid number" }
                token.toDouble().also { require(it.isFinite()) { "Non-finite number" } }
            }
        }
    }
    private fun string(): String {
        expect('"'); val out = StringBuilder()
        while (index < source.length) {
            val c = source[index++]; if (c == '"') return out.toString()
            require(c.code >= 32) { "JSON control character" }
            if (c != '\\') out.append(c) else {
                require(index < source.length); when (val escape = source[index++]) {
                    '"', '/', '\\' -> out.append(escape); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { require(index + 4 <= source.length); out.append(source.substring(index,index + 4).toInt(16).toChar()); index += 4 }
                    else -> error("Invalid JSON escape")
                }
            }
            require(out.length <= 8192) { "JSON string limit" }
        }
        error("Unterminated string")
    }
    private fun expect(c: Char) { require(source.getOrNull(index) == c) { "Expected $c at $index" }; index++ }
    private fun literal(token: String, value: Any?): Any? { require(source.startsWith(token,index)); index += token.length; return value }
}
