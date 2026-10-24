package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.licensing.LicenseVerifier
import org.json.JSONObject

class UpdateAuthorizationException(val code: String, message: String) : Exception(message)

/** A fresh, single-use device proof. Never persist it or put it in a URL. */
class UpdateAuthorization internal constructor(
    val appId: String,
    val action: String,
    val sha256: String,
    val challengeId: String,
    private val signature: String,
    private val license: String,
) {
    fun json(): JSONObject = JSONObject().put("challengeId", challengeId).put("signature", signature)
        .put("license", license).put("action", action).put("sha256", sha256)

    internal fun requireTarget(app: String, operation: String, hash: String) {
        require(appId == app && action == operation && sha256 == hash) { "Update proof target mismatch" }
    }
}

object UpdateProof {
    fun payload(action: String, appId: String, sha256: String, license: String): String {
        require(action in setOf("check", "download", "install"))
        require(appId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(if (action == "check") sha256.isEmpty() else sha256.matches(Regex("[a-f0-9]{64}")))
        require(license.isNotEmpty())
        return "$action\n$appId\n$sha256\n${LicenseVerifier.sha256Hex(license.toByteArray(Charsets.UTF_8))}"
    }
}
