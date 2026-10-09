package com.shilapi.xcertplay.airplay

import android.util.Log

/** Response metadata only: never log a command payload, telephone number or pairing data. */
internal object AirPlayCommandDiagnostics {
    @JvmStatic
    fun response(message: RtspMessage.Request) {
        if (!message.method.startsWith("RTSP/") && !message.method.startsWith("HTTP/")) return
        val status = message.path.toIntOrNull() ?: return
        val cseq = message.headers["cseq"]?.toIntOrNull()
        val result = if (message.body.isEmpty()) null else runCatching {
            (BplistCodec.decode(message.body) as? Map<*, *>)?.get("status") as? Number
        }.getOrNull()
        Log.i("DiPlay-Command", "response cseq=$cseq status=$status result=${result?.toLong()} bodyBytes=${message.body.size}")
    }
}
