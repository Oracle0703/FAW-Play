package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.licensing.LicenseProof
import com.shilapi.xcertplay.api.HttpTransport
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UpdateAuthorizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = "com.shihab.diplay"
    private val hash = "ab".repeat(32)
    private val license = "fixture-license.授权"
    private val licenseHash = "a23e7356f473a5a228c1d3c6e771c17a8a401fd270c36ac6a223bdca330ec030"

    @Test fun usesPublishedUtf8PayloadAndUpdateProofBytesWithoutTrailingNewline() {
        val payload = UpdateProof.payload("download", app, hash, license)
        assertEquals("download\n$app\n$hash\n$licenseHash", payload)
        val expected = "FAW-LICENSE-V1\nupdate\nfixture-nonce\n$app\n" +
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81\n" +
            "505cc83606f71253c05f577150fe5957b35377e2c7c494f7db82b3ec214f5482"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8),
            LicenseProof.bytes("update", "fixture-nonce", app, byteArrayOf(1, 2, 3), payload))
        assertEquals("check\n$app\n\n$licenseHash", UpdateProof.payload("check", app, "", license))
    }

    @Test fun signatureBindsOperationApplicationArtifactLicensePurposeAndNonce() {
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun proof(action: String = "download", appId: String = app, digest: String = hash, ticket: String = license,
                  purpose: String = "update", nonce: String = "fixture-nonce") =
            LicenseProof.bytes(purpose, nonce, appId, key.public.encoded,
                UpdateProof.payload(action, appId, digest, ticket))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key.private); update(proof())
        }.sign()
        fun verifies(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key.public); update(bytes); verify(signature)
        }
        assertTrue(verifies(proof()))
        assertFalse(verifies(proof(action = "install")))
        assertFalse(verifies(proof(appId = "com.other.app")))
        assertFalse(verifies(proof(digest = "cd".repeat(32))))
        assertFalse(verifies(proof(ticket = "$license\n")))
        assertFalse(verifies(proof(purpose = "renew")))
        assertFalse(verifies(proof(nonce = "another-fixture-nonce")))
    }

    @Test fun checkHasNoArtifactHashAndDownloadAndInstallRequireLowercaseSha256() {
        UpdateProof.payload("check", app, "", license)
        rejects { UpdateProof.payload("check", app, hash, license) }
        for (action in listOf("download", "install")) {
            UpdateProof.payload(action, app, hash, license)
            for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "AB".repeat(32), "g".repeat(64), "$hash\n", "../update.apk")) {
                rejects { UpdateProof.payload(action, app, invalid, license) }
            }
        }
        for (action in listOf("renew", "DOWNLOAD", "install\ncheck")) {
            rejects { UpdateProof.payload(action, app, hash, license) }
        }
        for (invalid in listOf("$app\nother.app", "../other.app", "single")) {
            rejects { UpdateProof.payload("check", invalid, "", license) }
        }
        rejects { UpdateProof.payload("check", app, "", "") }
    }

    @Test fun authorizationCannotBeReusedForAnotherApplicationOperationOrArtifact() {
        val authorization = authorization()
        authorization.requireTarget(app, "download", hash)
        rejects { authorization.requireTarget("com.other.app", "download", hash) }
        rejects { authorization.requireTarget(app, "install", hash) }
        rejects { authorization.requireTarget(app, "check", "") }
        rejects { authorization.requireTarget(app, "download", "cd".repeat(32)) }
    }

    @Test fun transportsRejectCrossTargetProofsBeforeNetworkAccessOrCreatingDownloadFiles() {
        rejects {
            UpdateTransport.fetch(app, "unused-key", "unused-signer", authorization())
        }
        rejects {
            UpdateTransport.fetch(app, "unused-key", "unused-signer", authorization("check", "other.app", ""))
        }
        val manifest = UpdateManifest(app, 90, "new", 28, 1, hash, "cd".repeat(32),
            "/v1/updates/$app/stable/artifacts/$hash.apk", "", "")
        val directory = File(temporary.root, "never-created")
        for (wrongProof in listOf(authorization("install"), authorization(packageName = "other.app"),
            authorization(digest = "ef".repeat(32)))) {
            rejects {
                UpdateTransport.download(manifest, directory, AtomicBoolean(false), { _, _ -> fail("Unexpected download") }, wrongProof)
            }
        }
        assertFalse(directory.exists())
    }

    @Test fun jsonPreservesProofCredentialsAndObjectToStringDoesNotExposeThem() {
        val signature = "fixture-device-signature+/="
        val authorization = authorization(signature = signature)
        val json = JSONObject(authorization.json().toString())
        assertEquals("fixture-challenge", json.getString("challengeId"))
        assertEquals(signature, json.getString("signature"))
        assertEquals(license, json.getString("license"))
        assertEquals("download", json.getString("action"))
        assertEquals(hash, json.getString("sha256"))
        assertFalse(authorization.toString().contains(license))
        assertFalse(authorization.toString().contains(signature))
    }

    @Test fun allUpdateOperationsSendTheFixedAutunnHeaderAlongsideTheirDeviceProof() {
        data class Received(val method: String, val path: String, val marker: List<String>?,
                            val contentType: String?, val body: String)
        val received = LinkedBlockingQueue<Received>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.add(Received(exchange.requestMethod, exchange.requestURI.toString(),
                exchange.requestHeaders["Autunn"], exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            for ((action, path) in listOf("check" to "/v1/updates/$app/stable",
                "download" to "/v1/updates/$app/stable/artifacts/$hash.apk",
                "install" to "/v1/updates/$app/authorize")) {
                val proof = authorization(action, digest = if (action == "check") "" else hash)
                HttpTransport.withConnection("http://127.0.0.1:${server.address.port}$path") { connection ->
                    UpdateTransport.postProof(connection, proof)
                    assertEquals(200, connection.responseCode)
                    connection.inputStream.close()
                }
                val request = received.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("Update request missing")
                assertEquals("POST", request.method)
                assertEquals(path, request.path)
                assertEquals(listOf("1"), request.marker)
                assertEquals("application/json; charset=UTF-8", request.contentType)
                val body = JSONObject(request.body)
                assertEquals(action, body.getString("action"))
                assertEquals(license, body.getString("license"))
                assertEquals("fixture-challenge", body.getString("challengeId"))
                assertEquals("fixture-device-signature", body.getString("signature"))
            }
        } finally { server.stop(0) }
    }

    @Test fun unauthorizedStatusesRejectKnownCodesUsingLocalMessages() {
        for (status in listOf(401, 403)) {
            for (code in listOf("NO_LICENSE", "ACTIVATION_REQUIRED", "INVALID_LICENSE", "LICENSE_EXPIRED", "DEVICE_REVOKED", "LICENSE_REVOKED", "UPDATE_HEADER_INVALID")) {
                val error = authorizationFailure(status,
                    JSONObject().put("error", code).put("message", "fixture-private-response").toString())
                assertEquals(code, error.code)
                assertFalse(error.toString().contains("fixture-private-response"))
            }
        }
    }

    @Test fun unknownAndHtmlAuthorizationErrorsDoNotExposeServerTextOrTokens() {
        val privateText = "fixture-private-token"
        for (status in listOf(401, 403)) {
            for (body in listOf("", "<html>$privateText</html>",
                JSONObject().put("error", privateText).put("message", privateText).toString())) {
                val error = authorizationFailure(status, body)
                assertEquals("UPDATE_UNAUTHORIZED", error.code)
                assertFalse(error.toString().contains(privateText))
                assertFalse(error.code.contains(privateText))
            }
        }
    }

    @Test fun redirectsAndOtherNonSuccessStatusesAreRejectedWithoutExposingResponseBody() {
        UpdateTransport.requireStatus(200, "")
        for (status in listOf(201, 204, 302, 307, 404, 429, 500)) {
            try {
                UpdateTransport.requireStatus(status, "<html>fixture-private-response</html>")
                fail("Non-success update response accepted: $status")
            } catch (error: UpdateException) {
                assertFalse(error.toString().contains("fixture-private-response"))
            }
        }
    }

    private fun authorization(action: String = "download", packageName: String = app, digest: String = hash,
                              signature: String = "fixture-device-signature") =
        UpdateAuthorization(packageName, action, digest, "fixture-challenge", signature, license)

    private fun authorizationFailure(status: Int, body: String): UpdateAuthorizationException {
        try {
            UpdateTransport.requireStatus(status, body)
            throw AssertionError("Unauthorized update response accepted: $status")
        } catch (error: UpdateAuthorizationException) {
            return error
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid update authorization accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
