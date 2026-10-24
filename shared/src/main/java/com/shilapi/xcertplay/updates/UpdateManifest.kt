package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.licensing.LicenseVerifier
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

data class UpdateManifest(
    val packageName: String, val versionCode: Long, val versionName: String,
    val minSdk: Int, val sizeBytes: Long, val sha256: String,
    val signingCertSha256: String, val downloadPath: String, val releaseNotes: String,
    val envelope: String
)

class UpdateException(message: String) : Exception(message)

/** Metadata is authenticated before any download location or version is used. */
object UpdateManifestVerifier {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = 65536
    private val hash = Regex("[0-9a-f]{64}")
    private val p256Order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    fun verify(envelope: String, publicKey: String, expectedPackage: String, expectedSigner: String): UpdateManifest {
        try {
            require(envelope.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES)
            require(expectedSigner.matches(hash))
            val outer = JSONObject(envelope)
            val encoded = outer.get("manifest") as String
            val signature = LicenseVerifier.decodeUrl(outer.get("signature") as String)
            require(encoded.length in 1..32768 && signature.size in 8..80)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(LicenseVerifier.decodeUrl(publicKey))) as ECPublicKey
            require(key.params.order == p256Order && key.params.curve.field.fieldSize == 256)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(("FAW-UPDATE-V1\n" + encoded).toByteArray(Charsets.UTF_8))
            require(verifier.verify(signature))
            val bytes = LicenseVerifier.decodeUrl(encoded)
            val json = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
            fun integer(name: String, min: Long, max: Long): Long {
                val value = json.get(name)
                require(value is Int || value is Long)
                return (value as Number).toLong().also { require(it in min..max) }
            }
            fun text(name: String, max: Int): String = (json.get(name) as String).also { require(it.length <= max) }
            require(integer("schema", 1, 1) == 1L)
            val app = text("packageName", 255)
            require(app == expectedPackage)
            val cert = text("signingCertSha256", 64)
            require(cert == expectedSigner && cert.matches(hash))
            val digest = text("sha256", 64).also { require(it.matches(hash)) }
            val path = text("downloadPath", 512)
            require(path == "/v1/updates/$app/stable/artifacts/$digest.apk")
            return UpdateManifest(app, integer("versionCode", 1, Int.MAX_VALUE.toLong()),
                text("versionName", 128).also { require(it.isNotBlank()) },
                integer("minSdk", 28, Int.MAX_VALUE.toLong()).toInt(),
                integer("sizeBytes", 1, MAX_APK_BYTES), digest, cert, path, text("releaseNotes", 4000), envelope)
        } catch (_: Exception) {
            throw UpdateException("更新信息校验失败，请联系提供方。")
        }
    }
}
