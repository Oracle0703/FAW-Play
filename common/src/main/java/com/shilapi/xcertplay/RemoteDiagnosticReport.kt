package com.shilapi.xcertplay

import com.shilapi.xcertplay.licensing.DiagnosticMetadata

/** State summary first, then newest sessions. Local exports retain the complete history. */
internal object RemoteDiagnosticReport {
    data class Prepared(val text: String, val truncated: Boolean)
    private val email = Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
    private val code = Regex("(?i)(?:FAW|AUTUNN)-[A-Za-z0-9_-]{20,}")
    private val headers = Regex("(?i)(authorization|bearer\\s|cookie|access[_-]?key|[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})")
    private val signerReader = Regex("certificateSource=(SigningInfo|legacy|unavailable)\\b")
    private const val MARKER = "\n[Report size limit reached; older history omitted. Full report can be saved locally.]\n"
    fun prepare(report: String, limit: Int = DiagnosticMetadata.MAX_REPORT_BYTES): Prepared {
        require(limit >= MARKER.toByteArray(Charsets.UTF_8).size)
        val output=StringBuilder(); var bytes=0
        val budget=limit-MARKER.toByteArray(Charsets.UTF_8).size
        for (raw in report.lineSequence()) {
            // This field names the Android signing API, not a certificate or identity value.
            val line=signerReader.replace(raw) { "signerReader=${it.groupValues[1]}" }
            if (email.containsMatchIn(line) || code.containsMatchIn(line) || headers.containsMatchIn(line)) continue
            val safe=DiagnosticRedactor.redact(line)?.map { if (it.isISOControl()) ' ' else it }?.joinToString("") ?: continue
            val count=safe.toByteArray(Charsets.UTF_8).size+1
            if (bytes+count > budget) return Prepared(output.append(MARKER).toString(), true)
            output.append(safe).append('\n'); bytes+=count
        }
        return Prepared(output.toString(),false)
    }
    fun metadataText(value: String, limit: Int) = value.map { if(it.isISOControl()) ' ' else it }.joinToString("").take(limit)
}
