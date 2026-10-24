package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.licensing.LicenseVerifier
import com.shilapi.xcertplay.api.HttpRequestControl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UpdateSecurityTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = LicenseVerifier.encodeUrl(key.public.encoded)
    private val app = "com.shihab.diplay"
    private val cert = "ab".repeat(32)
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val digest = LicenseVerifier.sha256Hex(bytes)
    private fun metadata() = JSONObject().put("schema", 1).put("packageName", app)
        .put("versionCode", 90).put("versionName", "测试更新").put("minSdk", 28)
        .put("sizeBytes", bytes.size).put("sha256", digest).put("signingCertSha256", cert)
        .put("downloadPath", "/v1/updates/$app/stable/artifacts/$digest.apk").put("releaseNotes", "更新说明")
    private fun envelope(m: JSONObject = metadata(), domain: String = "FAW-UPDATE-V1\n"): String {
        val encoded = LicenseVerifier.encodeUrl(m.toString().toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key.private); update((domain + encoded).toByteArray(Charsets.UTF_8))
        }.sign()
        return JSONObject().put("manifest", encoded).put("signature", LicenseVerifier.encodeUrl(signature)).toString()
    }
    private fun verify(s: String) = UpdateManifestVerifier.verify(s, publicKey, app, cert)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Untrusted update accepted") } catch (_: UpdateException) {}
    }

    @Test fun authenticatesMetadataBeforeUsingItAndRejectsTamperingAndDomainConfusion() {
        assertEquals(90L, verify(envelope()).versionCode)
        val signed = JSONObject(envelope())
        signed.put("manifest", LicenseVerifier.encodeUrl(metadata().put("versionCode", 999).toString().toByteArray()))
        rejects { verify(signed.toString()) }
        rejects { verify(envelope(domain = "FAW-LICENSE-V1\n")) }
        val foreign = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        rejects { UpdateManifestVerifier.verify(envelope(), LicenseVerifier.encodeUrl(foreign.public.encoded), app, cert) }
    }

    @Test fun rejectsCrossPackageSignerAndRedirectOrTraversalLocations() {
        for (m in listOf(metadata().put("packageName", "other.app"), metadata().put("signingCertSha256", "cd".repeat(32)),
            metadata().put("downloadPath", "https://other.invalid/update.apk"), metadata().put("downloadPath", "/v1/updates/../update.apk"),
            metadata().put("downloadPath", "/v1/updates/$app/stable/artifacts/$digest.apk?url=other"))) {
            rejects { verify(envelope(m)) }
        }
    }

    @Test fun rejectsCoercedNumbersOversizedFilesAndUnsupportedSchema() {
        for ((name, value) in listOf("versionCode" to "90", "versionCode" to 90.5, "schema" to 2, "minSdk" to 27,
            "sizeBytes" to 0, "sizeBytes" to (UpdateManifestVerifier.MAX_APK_BYTES + 1), "sha256" to "../bad")) {
            rejects { verify(envelope(metadata().put(name, value))) }
        }
        rejects { verify(" ".repeat(UpdateManifestVerifier.MAX_ENVELOPE_BYTES + 1)) }
    }

    @Test fun streamsExactArtifactAndReportsProgress() {
        val output = ByteArrayOutputStream()
        var progress = 0L
        UpdateTransport.copyVerified(ByteArrayInputStream(bytes), output, verify(envelope()), AtomicBoolean(false)) { count, total ->
            assertEquals(bytes.size.toLong(), total); assertTrue(count > progress); progress = count
        }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), progress)
    }

    @Test fun rejectsTruncationExcessBytesWrongDigestAndCancellation() {
        val m = verify(envelope())
        for (data in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(0), bytes.clone().apply { this[0] = 10 })) {
            rejects { UpdateTransport.copyVerified(ByteArrayInputStream(data), ByteArrayOutputStream(), m, AtomicBoolean(false)) { _, _ -> } }
        }
        rejects { UpdateTransport.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), m, AtomicBoolean(true)) { _, _ -> } }
        val cancel = AtomicBoolean(false)
        rejects { UpdateTransport.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), m, cancel) { _, _ -> cancel.set(true) } }
    }

    @Test fun cancellationWhichArrivesInsideReadCannotWriteOrReportAnotherChunk() {
        val cancelled = AtomicBoolean(false)
        val output = ByteArrayOutputStream()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                cancelled.set(true)
                return count
            }
        }
        rejects {
            UpdateTransport.copyVerified(input, output, verify(envelope()), cancelled) { _, _ -> fail("Cancelled data reached progress") }
        }
        assertEquals(0, output.size())
    }

    @Test fun anExpiredReadCannotWriteDataEvenWhenTheStreamReturnsNormally() {
        var now = 0L
        val output = ByteArrayOutputStream()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                now = 10_000_000L
                return super.read(buffer, offset, length)
            }
        }
        val manifest = verify(envelope())
        assertThrows(java.net.SocketTimeoutException::class.java) {
            UpdateTransport.copyVerified(input, output, manifest, AtomicBoolean(false), HttpRequestControl(10, null) { now }) { _, _ ->
                fail("Expired data reached progress")
            }
        }
        assertEquals(0, output.size())
    }
}
