package com.shilapi.xcertplay.licensing

import org.json.JSONObject

/** Canonical UTF-8 transcript shared with the NAS; no credentials or purchase ticket. */
data class DiagnosticMetadata(val versionName: String, val versionCode: Long, val manufacturer: String,
    val model: String, val firmware: String, val androidRelease: String, val sdk: Int, val truncated: Boolean) {
    init {
        require(versionCode in 1..2147483647 && sdk in 1..100)
        for ((value, limit) in listOf(versionName to 80, manufacturer to 80, model to 100, firmware to 200, androidRelease to 40)) {
            require(value.length <= limit && value.none { it.isISOControl() })
        }
        require(versionName.isNotBlank())
    }
    fun payload(report: String): String = "FAW-DIAGNOSTIC-V1\n$versionName\n$versionCode\n$manufacturer\n$model\n$firmware\n$androidRelease\n$sdk\n$truncated\n${LicenseVerifier.sha256Hex(report.toByteArray(Charsets.UTF_8))}"
    fun json(): JSONObject = JSONObject().put("versionName", versionName).put("versionCode", versionCode)
        .put("manufacturer", manufacturer).put("model", model).put("firmware", firmware)
        .put("androidRelease", androidRelease).put("sdk", sdk).put("truncated", truncated)
    companion object { const val MAX_REPORT_BYTES = 1024 * 1024 }
}
data class DiagnosticReceipt(val reportId: String, val deviceFingerprint: String, val truncated: Boolean)
