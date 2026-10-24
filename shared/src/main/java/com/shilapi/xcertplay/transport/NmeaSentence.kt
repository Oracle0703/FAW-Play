package com.shilapi.xcertplay.transport

import java.util.Locale

/** Shared numeric and checksum encoding for the NMEA sentences sent to the phone. */
internal object NmeaSentence {
    fun format(pattern: String, vararg arguments: Any): String =
        String.format(Locale.US, pattern, *arguments)

    fun encode(body: String): String {
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*${format("%02X", checksum)}\r\n"
    }
}
