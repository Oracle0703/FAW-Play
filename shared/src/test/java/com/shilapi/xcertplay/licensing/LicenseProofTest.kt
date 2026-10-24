package com.shilapi.xcertplay.licensing

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class LicenseProofTest {
    // Fixed P-256 key and transcript digests checked independently with Python hashlib.
    private val key = LicenseVerifier.decodeUrl("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbZ-D_Rcyq-j8x5TXOV3f9pRYOzGC_ZhsUUPlOsKiekwC1C9gaI0GcRrK4UXGJ5guSBGpIIHfEAF5UUJlpr-acg")
    private val nonce = "nonce-nas-qr-protocol-v1"
    private val app = "com.shihab.diplay"
    private val session = "6e9d4031-9c2e-43c8-b793-5a9d96262491"

    @Test fun phoneQrOperationsMatchTheServerUtf8WireContract() {
        val expectedKey = "7037e549f2ec82ea7c1b337cea678c0b8e585bba31b677088f18305f056326d6"
        for ((purpose, payload, digest) in listOf(
            Triple("pair", "红旗 HS5", "20e300175bf210797f74e563f3edcbd9bb7b4e7a261e4a9fdfe9caf43490a23a"),
            Triple("activate", session, "22393084accfec33686ab239e4bdb32b5a390cde893a5774f6bc3211d96a23a7"),
            Triple("cancel", session, "22393084accfec33686ab239e4bdb32b5a390cde893a5774f6bc3211d96a23a7")
        )) {
            val expected = "FAW-LICENSE-V1\n$purpose\n$nonce\n$app\n$expectedKey\n$digest"
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), LicenseProof.bytes(purpose, nonce, app, key, payload))
        }
    }
    @Test fun approvalProofCannotBeTransferredToOtherSessionAppOrOperation() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val device = generator.generateKeyPair()
        fun transcript(purpose: String = "activate", appId: String = app, payload: String = session) =
            LicenseProof.bytes(purpose, nonce, appId, device.public.encoded, payload)
        val signed = Signature.getInstance("SHA256withECDSA").run { initSign(device.private); update(transcript()); sign() }
        fun verifies(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initVerify(device.public); update(bytes); verify(signed) }
        assertTrue(verifies(transcript()))
        assertFalse(verifies(transcript(payload = session + "-other")))
        assertFalse(verifies(transcript(appId = "other.app")))
        assertFalse(verifies(transcript(purpose = "cancel")))
    }
    @Test fun unknownOperationsAndTranscriptLineInjectionAreRejected() {
        for (args in listOf(Triple("approve", nonce, app), Triple("pair", "bad\nnonce", app), Triple("activate", nonce, "bad\napp"))) {
            try { LicenseProof.bytes(args.first,args.second,args.third,key,session); fail("Invalid transcript accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
