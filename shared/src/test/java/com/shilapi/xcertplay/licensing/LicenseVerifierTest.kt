package com.shilapi.xcertplay.licensing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LicenseVerifierTest {
    private val signer = keyPair()
    private val app = "com.shihab.diplay"
    private val deviceHash = "ab".repeat(32)
    private val publicKey = LicenseVerifier.encodeUrl(signer.public.encoded)
    private val issued = 1_800_000_000L

    private fun payload() = JSONObject().put("iss", "faw-play-license").put("aud", app).put("sub", "account-1")
        .put("license_id", "license-1").put("device_id", "device-1").put("device_key_sha256", deviceHash)
        .put("challenge_id", "current-challenge")
        .put("features", JSONArray().put("carplay")).put("iat", issued).put("nbf", issued).put("exp", issued + 604800)

    private fun token(payload: JSONObject = payload(), header: JSONObject = JSONObject().put("alg", "ES256").put("typ", "JWT")): String {
        val input = LicenseVerifier.encodeUrl(header.toString().toByteArray()) + "." + LicenseVerifier.encodeUrl(payload.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(signer.private); update(input.toByteArray(Charsets.US_ASCII)) }
        return input + "." + LicenseVerifier.encodeUrl(derToJose(signature.sign()))
    }

    @Test fun acceptsLegacySevenDayEs256JwsAndStrictExpiryBoundary() {
        val claims = LicenseVerifier.verify(token(), publicKey, app, deviceHash)
        assertEquals("account-1", claims.subject)
        assertEquals(issued + 604800, LicenseVerifier.effectiveExpirySeconds(claims))
        LicenseVerifier.validateTime(claims, issued + 604799)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, issued + 604800) }
        assertInvalid("CLOCK_ROLLBACK") { LicenseVerifier.validateTime(claims, issued - 1) }
    }

    @Test fun acceptsThirtyDayOfflineTicketAndExpiresAtExactBoundary() {
        val expiry = issued + 30L * 86400
        val claims = LicenseVerifier.verify(token(payload().put("exp", expiry)), publicKey, app, deviceHash)
        assertEquals(30, LicenseVerifier.MAX_OFFLINE_DAYS)
        assertEquals(expiry, claims.expiresAt)
        assertEquals(expiry, LicenseVerifier.effectiveExpirySeconds(claims))
        LicenseVerifier.validateTime(claims, expiry - 1)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, expiry) }
    }

    @Test fun capsHistoricalNinetyDayPermanentTicketAtThirtyDaysWithoutChangingSignedClaims() {
        val signedExpiry = issued + 90L * 86400
        val effectiveExpiry = issued + 30L * 86400
        val claims = LicenseVerifier.verify(token(payload().put("permanent", true).put("exp", signedExpiry)), publicKey, app, deviceHash)
        assertTrue(claims.permanent)
        assertEquals(signedExpiry, claims.expiresAt)
        assertEquals(effectiveExpiry, LicenseVerifier.effectiveExpirySeconds(claims))
        LicenseVerifier.validateTime(claims, effectiveExpiry - 1)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, effectiveExpiry) }
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, signedExpiry - 1) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("exp", signedExpiry + 1)), publicKey, app, deviceHash) }
    }

    @Test fun effectiveExpiryDoesNotOverflowNearMaximumTimestamp() {
        val nearMaximum = payload().put("iat", Long.MAX_VALUE - 100).put("nbf", Long.MAX_VALUE - 100)
            .put("exp", Long.MAX_VALUE)
        val claims = LicenseVerifier.verify(token(nearMaximum), publicKey, app, deviceHash)
        assertEquals(Long.MAX_VALUE, LicenseVerifier.effectiveExpirySeconds(claims))
        LicenseVerifier.validateTime(claims, Long.MAX_VALUE - 1)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, Long.MAX_VALUE) }
    }

    @Test fun extendedOfflineWindowCannotOutliveFinitePurchase() {
        val purchaseUntil = issued + 12L * 86400
        val finite = payload().put("purchase_valid_until", purchaseUntil).put("exp", purchaseUntil)
        val claims = LicenseVerifier.verify(token(finite), publicKey, app, deviceHash)
        assertEquals(purchaseUntil, claims.expiresAt)
        assertEquals(purchaseUntil, LicenseVerifier.effectiveExpirySeconds(claims))
        assertEquals(purchaseUntil, claims.purchaseValidUntil)
        LicenseVerifier.validateTime(claims, purchaseUntil - 1)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, purchaseUntil) }
        assertInvalid { LicenseVerifier.verify(token(finite.put("exp", issued + 90L * 86400)), publicKey, app, deviceHash) }
    }

    @Test fun verifiesIndependentBackendOpenSslFixture() {
        val json = javaClass.getResourceAsStream("/licensing/backend-es256-fixture.json")!!.use {
            JSONObject(String(it.readBytes(), Charsets.UTF_8))
        }
        val claims = LicenseVerifier.verify(json.getString("license"), json.getString("publicKeySpki"),
            json.getString("appId"), json.getString("deviceKeySha256"))
        assertEquals(1_800_000_000L, claims.issuedAt)
        assertEquals(1_800_604_800L, claims.expiresAt)
        LicenseVerifier.validateTime(claims, json.getLong("serverTime"))
        LicenseFreshness.acceptTime(claims, json.getString("challengeId"))
        assertInvalid("STALE_LICENSE") { LicenseFreshness.acceptTime(claims, "different-challenge") }
    }

    @Test fun acceptsScopedAndPermanentPurchasesWithoutRemovingOfflineExpiry() {
        val all = JSONArray().put("hud").put("instrument-map").put("steering-wheel")
        val claims = LicenseVerifier.verify(token(payload().put("features", all).put("permanent", true)), publicKey, app, deviceHash)
        assertEquals(LicenseFeatures.ALL, claims.features); assertTrue(claims.permanent)
        assertInvalid("LICENSE_EXPIRED") { LicenseVerifier.validateTime(claims, issued + 604800) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("features", all).put("permanent", true).put("exp", issued + 7_776_001L)), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("permanent", "true")), publicKey, app, deviceHash) }
    }
    @Test fun featureScopesArePreservedAndLegacyTicketsGrantOnlyVehicleFeatures() {
        val hud = LicenseVerifier.verify(token(payload().put("features", JSONArray().put("hud"))), publicKey, app, deviceHash)
        assertEquals(setOf(LicenseFeatures.HUD), hud.features)
        assertEquals(LicenseFeatures.ALL, LicenseVerifier.verify(token(), publicKey, app, deviceHash).features)
        assertFalse("carplay" in LicenseFeatures.ALL)
        assertInvalid { LicenseVerifier.verify(token(payload().put("permanent", true).put("purchase_valid_until", issued + 604800)), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("purchase_valid_until", issued + 3600)), publicKey, app, deviceHash) }
    }

    @Test fun packageSignaturePolicyAcceptsOnlyExactlyOneConfiguredCurrentCertificate() {
        val certificate = byteArrayOf(1, 5, 9, 13)
        val hash = LicenseVerifier.sha256Hex(certificate)
        assertTrue(LicensePackageSignature.matchesSingleSigner(arrayOf(certificate), hash))
        assertFalse(LicensePackageSignature.matchesSingleSigner(arrayOf(certificate, certificate), hash))
        assertFalse(LicensePackageSignature.matchesSingleSigner(emptyArray(), hash))
        assertFalse(LicensePackageSignature.matchesSingleSigner(arrayOf(byteArrayOf(2)), hash))
        assertFalse(LicensePackageSignature.matchesSingleSigner(arrayOf(certificate), ""))
    }

    @Test fun rejectsForgedPayloadAndUntrustedSigningKey() {
        val original = token()
        val parts = original.split('.')
        val forged = parts[0] + "." + LicenseVerifier.encodeUrl(payload().put("sub", "attacker").toString().toByteArray()) + "." + parts[2]
        assertInvalid { LicenseVerifier.verify(forged, publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(original, LicenseVerifier.encodeUrl(keyPair().public.encoded), app, deviceHash) }
    }

    @Test fun rejectsOtherDeviceApplicationIssuerAndMissingEntitlement() {
        assertInvalid { LicenseVerifier.verify(token(), publicKey, app, "cd".repeat(32)) }
        assertInvalid { LicenseVerifier.verify(token(), publicKey, "com.other.app", deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("iss", "other-server")), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("features", JSONArray().put("music"))), publicKey, app, deviceHash) }
    }

    @Test fun rejectsAlgorithmConfusionEmbeddedKeysAndExcessiveOfflineLifetime() {
        assertInvalid { LicenseVerifier.verify(token(header = JSONObject().put("alg", "HS256")), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(header = JSONObject().put("alg", "ES256").put("jwk", JSONObject())), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("exp", issued + 7_776_001L)), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("nbf", issued + 1)), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().put("iat", issued.toString())), publicKey, app, deviceHash) }
        assertInvalid { LicenseVerifier.verify(token(payload().also { it.remove("challenge_id") }), publicKey, app, deviceHash) }
    }

    @Test fun deviceProofUsesPublishedBytesAndDoesNotVerifyWithDifferentNonce() {
        val proof = LicenseProof.bytes("enroll", "test-nonce", app, byteArrayOf(1, 2, 3), "CODE-123")
        assertEquals("FAW-LICENSE-V1\nenroll\ntest-nonce\ncom.shihab.diplay\n039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81\n2a961f91e4e7433ea54d1be658f22a0b034c253ca80cd81916b41240eda85681", String(proof))
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(signer.private); update(proof) }.sign()
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.public); update(proof) }
        assertTrue(verifier.verify(signature))
        verifier.initVerify(signer.public)
        verifier.update(LicenseProof.bytes("enroll", "different-nonce", app, byteArrayOf(1, 2, 3), "CODE-123"))
        assertFalse(verifier.verify(signature))
    }

    private fun assertInvalid(code: String = "INVALID_LICENSE", block: () -> Unit) {
        try { block(); fail("Invalid license accepted") }
        catch (e: LicenseValidationException) { assertEquals(code, e.code) }
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 2
        fun readInteger(): ByteArray {
            require(der[offset++].toInt() == 2)
            val size = der[offset++].toInt() and 255
            val bytes = der.copyOfRange(offset, offset + size).dropWhile { it == 0.toByte() }.toByteArray()
            offset += size
            require(bytes.size <= 32)
            return ByteArray(32 - bytes.size) + bytes
        }
        return readInteger() + readInteger()
    }
}
