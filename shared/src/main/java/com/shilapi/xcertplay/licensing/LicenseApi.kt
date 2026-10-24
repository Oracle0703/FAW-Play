package com.shilapi.xcertplay.licensing

import android.os.SystemClock
import com.shilapi.xcertplay.api.ApiUrls
import com.shilapi.xcertplay.api.HttpTransport
import com.shilapi.xcertplay.api.HttpRequestControl
import org.json.JSONObject
import com.shilapi.xcertplay.updates.UpdateAuthorization
import com.shilapi.xcertplay.updates.UpdateProof
import java.util.concurrent.atomic.AtomicBoolean

class LicenseApiException(val status: Int, val code: String, message: String) : Exception(message)
/** expectedChallengeId comes from this live request, never from parsing the returned ticket. */
data class IssuedLicense(val token: String, val expectedChallengeId: String)
data class DeviceActivation(
    val sessionId: String,
    val pollToken: String,
    val activationUrl: String,
    val deviceFingerprint: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
    val receivedAtElapsed: Long
)

object LicenseApi {
    fun uploadDiagnostic(appId: String, key: ByteArray, metadata: DiagnosticMetadata, report: String,
                         control: HttpRequestControl = HttpRequestControl(), sign: (ByteArray) -> String): DiagnosticReceipt {
        require(report.isNotBlank() && report.toByteArray(Charsets.UTF_8).size <= DiagnosticMetadata.MAX_REPORT_BYTES)
        val challenge = challenge("diagnostic", appId, key, control)
        val json = post("/v1/diagnostics", JSONObject()
            .put("challengeId", challenge.getString("challengeId"))
            .put("signature", sign(LicenseProof.bytes("diagnostic", challenge.getString("nonce"), appId, key, metadata.payload(report))))
            .put("metadata", metadata.json()).put("report", report), control)
        val id = json.getString("reportId")
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        java.util.UUID.fromString(id)
        val fingerprint = LicenseVerifier.sha256Hex(key).take(16).uppercase(java.util.Locale.ROOT)
        require(json.getString("deviceFingerprint") == fingerprint && json.getBoolean("truncated") == metadata.truncated)
        return DiagnosticReceipt(id, fingerprint, metadata.truncated)
    }

    fun beginActivation(appId: String, key: ByteArray, deviceName: String,
                        control: HttpRequestControl = HttpRequestControl(), sign: (ByteArray) -> String): DeviceActivation {
        val name = deviceName.take(80)
        val challenge = challenge("pair", appId, key, control)
        val json = post("/v1/activation/sessions", JSONObject()
            .put("challengeId", challenge.getString("challengeId"))
            .put("signature", sign(LicenseProof.bytes("pair", challenge.getString("nonce"), appId, key, name)))
            .put("deviceName", name), control)
        val path = json.getString("activationPath")
        // Accept only the locally configured backend and the fixed activation route.
        require(path.matches(Regex("/activate/#token=[A-Za-z0-9_-]{43}")))
        val fingerprint = LicenseVerifier.sha256Hex(key).take(16).uppercase(java.util.Locale.ROOT)
        require(json.getString("deviceFingerprint") == fingerprint)
        val interval = json.getInt("interval")
        val expires = json.getInt("expiresIn")
        require(interval in 5..60 && expires in 1..600)
        val id = json.getString("sessionId")
        java.util.UUID.fromString(id)
        val poll = json.getString("pollToken")
        require(poll.matches(Regex("[A-Za-z0-9_-]{43}")))
        return DeviceActivation(id, poll, ApiUrls.backend("/activate/") + path.removePrefix("/activate/"), fingerprint, interval, expires, SystemClock.elapsedRealtime())
    }

    fun awaitActivation(grant: DeviceActivation, appId: String, key: ByteArray, cancelled: AtomicBoolean, sign: (ByteArray) -> String): IssuedLicense {
        val deadline = grant.receivedAtElapsed + grant.expiresInSeconds * 1000L
        fun requestControl(cancellable: Boolean = true): HttpRequestControl {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw LicenseApiException(410, "ACTIVATION_EXPIRED", "激活二维码已过期，请重新生成。")
            return HttpRequestControl(minOf(30_000L, remaining), if (cancellable) cancelled else null)
        }
        while (!cancelled.get()) {
            val pollAt = SystemClock.elapsedRealtime() + grant.intervalSeconds * 1000L
            while (!cancelled.get() && SystemClock.elapsedRealtime() < minOf(pollAt, deadline)) {
                Thread.sleep(minOf(250L, pollAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
            if (cancelled.get()) break
            if (SystemClock.elapsedRealtime() >= deadline) throw LicenseApiException(410, "ACTIVATION_EXPIRED", "激活二维码已过期，请重新生成。")
            val status = post("/v1/activation/status", JSONObject()
                .put("sessionId", grant.sessionId).put("pollToken", grant.pollToken), requestControl()).getString("status")
            if (cancelled.get()) break
            when (status) {
                "pending" -> Unit
                "ready" -> {
                    val control = requestControl()
                    val challenge = challenge("activate", appId, key, control)
                    control.ensureActive()
                    // Claim consumes a grant. Once submitted, retain a valid completed ticket
                    // even if the screen closes; the response still has a bounded total budget.
                    val reply = post("/v1/activation/claim", deviceRequest(grant, challenge, "activate", appId, key, sign),
                        requestControl(cancellable = false))
                    return IssuedLicense(reply.getString("license"), challenge.getString("challengeId"))
                }
                else -> throw LicenseApiException(0, "INVALID_RESPONSE", "激活状态无效，请重新扫码。")
            }
        }
        throw LicenseApiException(0, "CANCELLED", "已停止等待激活结果。")
    }

    fun cancelActivation(grant: DeviceActivation, appId: String, key: ByteArray,
                         control: HttpRequestControl = HttpRequestControl(), sign: (ByteArray) -> String) {
        val challenge = challenge("cancel", appId, key, control)
        post("/v1/activation/cancel", deviceRequest(grant, challenge, "cancel", appId, key, sign), control)
    }

    private fun deviceRequest(grant: DeviceActivation, challenge: JSONObject, purpose: String, appId: String, key: ByteArray, sign: (ByteArray) -> String) =
        JSONObject().put("sessionId", grant.sessionId).put("challengeId", challenge.getString("challengeId"))
            .put("signature", sign(LicenseProof.bytes(purpose, challenge.getString("nonce"), appId, key, grant.sessionId)))

    fun renew(appId: String, publicKeySpki: ByteArray, license: String,
              control: HttpRequestControl = HttpRequestControl(), sign: (ByteArray) -> String): IssuedLicense {
        val challenge = challenge("renew", appId, publicKeySpki, control)
        val proof = LicenseProof.bytes("renew", challenge.getString("nonce"), appId, publicKeySpki, license)
        return IssuedLicense(post("/v1/licenses/renew", JSONObject().put("challengeId", challenge.getString("challengeId"))
            .put("signature", sign(proof)).put("license", license), control).getString("license"), challenge.getString("challengeId"))
    }

    fun prepareUpdate(appId: String, key: ByteArray, license: String, action: String, sha256: String,
                      control: HttpRequestControl = HttpRequestControl(), sign: (ByteArray) -> String): UpdateAuthorization {
        val payload = UpdateProof.payload(action, appId, sha256, license)
        val challenge = challenge("update", appId, key, control)
        return UpdateAuthorization(appId, action, sha256, challenge.getString("challengeId"),
            sign(LicenseProof.bytes("update", challenge.getString("nonce"), appId, key, payload)), license)
    }

    private fun challenge(purpose: String, appId: String, key: ByteArray, control: HttpRequestControl): JSONObject =
        post("/v1/challenges", JSONObject().put("purpose", purpose).put("appId", appId)
            .put("publicKeySpki", LicenseVerifier.encodeUrl(key))
            .put("offlineDays", LicenseVerifier.MAX_OFFLINE_DAYS), control).also {
            require(it.getInt("expiresIn") in 1..300)
            require(it.getString("nonce").length in 16..512 && it.getString("challengeId").length in 1..128)
        }

    private fun post(path: String, body: JSONObject, control: HttpRequestControl): JSONObject {
        val response = HttpTransport.request("POST", ApiUrls.backend(path), body.toString(), control = control)
        val status = response.status
        val json = try { JSONObject(response.body) } catch (_: Exception) { JSONObject() }
        if (status !in 200..299) {
            val code = json.optString("error", "HTTP_$status")
            // Server response text may contain HTML, tokens or internal details.
            val message = when (code) {
                "DIAGNOSTIC_TOO_LARGE" -> "诊断报告超过服务器限制，请保存到本地后联系提供方。"
                "DIAGNOSTIC_RATE_LIMITED" -> "报告上传次数已达限制，请一小时后重试。"
                "DEVICE_LIMIT", "DEVICE_LIMIT_REACHED" -> "兑换码的设备额度已用完，请联系提供方解绑旧车机。"
                "LICENSE_REVOKED", "DEVICE_REVOKED" -> "此设备授权已被撤销，请联系提供方。"
                "ACTIVATION_EXPIRED", "ACTIVATION_CANCELLED" -> "二维码已失效，请重新生成并扫码。"
                "NO_LICENSE" -> "暂无可用授权，请重新扫码激活或联系提供方。"
                "LICENSE_EXPIRED" -> "购买授权已到期，请联系提供方续费。"
                "INVALID_ACTIVATION_CODE", "ACTIVATION_CODE_INVALID", "ACTIVATION_CODE_USED" -> "兑换码无效或已过期，请检查后重试。"
                else -> if (status == 429) "请求过于频繁，请稍后重试。" else "授权服务器暂未完成请求（$status），请稍后重试。"
            }
            throw LicenseApiException(status, code, message)
        }
        require(response.body.isNotBlank() && json.length() > 0) { "Empty JSON response" }
        return json
    }
}
