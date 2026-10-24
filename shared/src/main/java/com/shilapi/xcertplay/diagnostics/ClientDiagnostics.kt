package com.shilapi.xcertplay.diagnostics

import android.os.SystemClock
import android.util.Log

/** Process diagnostics bridge. Producers send metadata; the application owns redaction and storage. */
object ClientDiagnostics {
    @Volatile private var sink: ((String) -> Unit)? = null

    fun attach(writer: (String) -> Unit) { sink = writer }

    @JvmStatic fun record(area: String, event: String) {
        val line = "component=$area elapsedRealtimeMs=${SystemClock.elapsedRealtime()} $event"
        Log.i("Autunn-Diagnostics", line)
        // Diagnostics must not propagate a storage failure into a vehicle or media operation.
        try { sink?.invoke(line) } catch (_: Exception) { }
    }
}
