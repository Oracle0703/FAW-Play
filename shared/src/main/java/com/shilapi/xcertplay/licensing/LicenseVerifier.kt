package com.shilapi.xcertplay.licensing

import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class LicenseClaims(
    val subject: String,
    val licenseId: String,
    val deviceId: String,
    val issuedAt: Long,
    val notBefore: Long,
    val expiresAt: Long,
    val challengeId: String,
    val features: Set<String> = LicenseFeatures.ALL,
    val permanent: Boolean = false,
    val purchaseValidUntil: Long? = null
)

object LicenseFeatures {
    const val HUD = "hud"
    const val INSTRUMENT_MAP = "instrument-map"
    const val STEERING_WHEEL = "steering-wheel"
    val ALL = setOf(HUD, INSTRUMENT_MAP, STEERING_WHEEL)
}

class LicenseValidationException(val code: String, message: String) : Exception(message)

/** Verifies an RFC 7515 ES256 compact JWS using only the key supplied by the build. */
object LicenseVerifier {
    const val MAX_OFFLINE_DAYS = 30
    const val MAX_OFFLINE_SECONDS = MAX_OFFLINE_DAYS * 24L * 60 * 60
    // Retain the signed format of existing tickets; the current offline policy is applied below.
    private const val MAX_SIGNED_LIFETIME_SECONDS = 90L * 24 * 60 * 60
    private val p256Order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    fun verify(token: String, publicKeySpki: String, appId: String, deviceKeyHash: String): LicenseClaims {
        try {
            require(token.length in 1..16384) { "Invalid token length" }
            val parts = token.split('.')
            require(parts.size == 3) { "Invalid compact JWS" }
            val header = JSONObject(String(decodeUrl(parts[0]), StandardCharsets.UTF_8))
            require(header.optString("alg") == "ES256") { "Unsupported signature algorithm" }
            require(!header.has("crit") && !header.has("b64") && !header.has("jku") && !header.has("jwk") && !header.has("x5u")) { "Unsupported JWS header" }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decodeUrl(publicKeySpki))) as ECPublicKey
            require(key.params.order == p256Order && key.params.curve.field.fieldSize == 256) { "Expected P-256 key" }
            val rawSignature = decodeUrl(parts[2])
            require(rawSignature.size == 64) { "Invalid ES256 signature size" }
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            require(verifier.verify(joseSignatureToDer(rawSignature))) { "Invalid signature" }
            val payload = JSONObject(String(decodeUrl(parts[1]), StandardCharsets.UTF_8))
            require(payload.getString("iss") == "faw-play-license") { "Wrong issuer" }
            require(payload.get("aud") is String && payload.getString("aud") == appId) { "Wrong audience" }
            require(payload.getString("device_key_sha256") == deviceKeyHash) { "Wrong device" }
            val features = payload.getJSONArray("features")
            val names = (0 until features.length()).map { features.getString(it) }.toSet()
            // Previously issued carplay tickets grant the same three vehicle features during migration.
            val allowed = if ("carplay" in names) LicenseFeatures.ALL else names.intersect(LicenseFeatures.ALL)
            require(allowed.isNotEmpty()) { "Missing vehicle feature entitlement" }
            fun text(name: String): String = payload.getString(name).also { require(it.isNotBlank() && it.length <= 256) }
            fun integer(name: String): Long {
                val value = payload.get(name)
                require(value is Int || value is Long) { "Invalid timestamp" }
                return (value as Number).toLong().also { require(it > 0) }
            }
            val permanent = if (payload.has("permanent")) {
                require(payload.get("permanent") is Boolean) { "Invalid permanent flag" }
                payload.getBoolean("permanent")
            } else false
            val purchaseUntil = if (payload.has("purchase_valid_until") && !payload.isNull("purchase_valid_until")) integer("purchase_valid_until") else null
            val claims = LicenseClaims(text("sub"), text("license_id"), text("device_id"), integer("iat"), integer("nbf"), integer("exp"), text("challenge_id"), allowed, permanent, purchaseUntil)
            require(claims.notBefore <= claims.issuedAt && claims.expiresAt > claims.issuedAt &&
                claims.expiresAt - claims.issuedAt <= MAX_SIGNED_LIFETIME_SECONDS) { "Invalid license lifetime" }
            require(!permanent || purchaseUntil == null) { "Conflicting purchase validity" }
            require(purchaseUntil == null || purchaseUntil >= claims.expiresAt) { "Ticket outlives purchase" }
            return claims
        } catch (e: LicenseValidationException) {
            throw e
        } catch (_: Exception) {
            throw LicenseValidationException("INVALID_LICENSE", "授权票据无效，请联网重新验证。")
        }
    }

    /** Applies the current policy to signed claims without extending or rewriting their expiry. */
    fun effectiveExpirySeconds(claims: LicenseClaims): Long {
        val policyExpiry = if (claims.issuedAt > Long.MAX_VALUE - MAX_OFFLINE_SECONDS) {
            Long.MAX_VALUE
        } else {
            claims.issuedAt + MAX_OFFLINE_SECONDS
        }
        return minOf(claims.expiresAt, policyExpiry)
    }

    fun validateTime(claims: LicenseClaims, nowSeconds: Long) {
        if (nowSeconds < claims.issuedAt || nowSeconds < claims.notBefore) {
            throw LicenseValidationException("CLOCK_ROLLBACK", "设备时间异常，请联网重新验证授权。")
        }
        if (nowSeconds >= effectiveExpirySeconds(claims)) {
            throw LicenseValidationException("LICENSE_EXPIRED", "离线授权已到期，请联网续期。")
        }
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encodeUrl(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeUrl(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid base64url" }
        return Base64.getUrlDecoder().decode(value)
    }

    internal fun joseSignatureToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun integer(offset: Int): ByteArray {
            val unsigned = raw.copyOfRange(offset, offset + 32).dropWhile { it == 0.toByte() }.toByteArray()
                .let { if (it.isEmpty()) byteArrayOf(0) else it }
            val positive = if (unsigned[0].toInt() and 128 != 0) byteArrayOf(0) + unsigned else unsigned
            return byteArrayOf(2, positive.size.toByte()) + positive
        }
        val sequence = integer(0) + integer(32)
        return byteArrayOf(48, sequence.size.toByte()) + sequence
    }
}

/** Server and device must sign exactly these UTF-8 bytes; no trailing newline. */
object LicenseProof {
    fun bytes(purpose: String, nonce: String, appId: String, publicKeySpki: ByteArray, payload: String): ByteArray {
        require(purpose in setOf("enroll", "renew", "pair", "activate", "cancel", "diagnostic", "update"))
        require(nonce.isNotBlank() && !nonce.contains('\n') && !appId.contains('\n'))
        return "FAW-LICENSE-V1\n$purpose\n$nonce\n$appId\n${LicenseVerifier.sha256Hex(publicKeySpki)}\n${LicenseVerifier.sha256Hex(payload.toByteArray(StandardCharsets.UTF_8))}"
            .toByteArray(StandardCharsets.UTF_8)
    }
}
