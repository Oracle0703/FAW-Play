package com.shilapi.xcertplay.licensing
import org.junit.Assert.*
import org.junit.Test

class DiagnosticMetadataTest {
    private val metadata=DiagnosticMetadata("0.2.31-FAW-LOG1",105,"FAW","HS5","3222","9",28,false)
    @Test fun payloadMatchesServerContractAndBindsEveryField() {
        val report="video frames=42\naudio drop=3\n"
        val expected="FAW-DIAGNOSTIC-V1\n0.2.31-FAW-LOG1\n105\nFAW\nHS5\n3222\n9\n28\nfalse\n"+LicenseVerifier.sha256Hex(report.toByteArray(Charsets.UTF_8))
        assertEquals(expected,metadata.payload(report))
        for(other in listOf(metadata.copy(versionName="other"),metadata.copy(versionCode=104),metadata.copy(manufacturer="other"),
            metadata.copy(model="other"),metadata.copy(firmware="UI35"),metadata.copy(androidRelease="10"),metadata.copy(sdk=29),metadata.copy(truncated=true)))
            assertNotEquals(expected,other.payload(report))
        assertNotEquals(expected,metadata.payload(report+"changed"))
        val key=ByteArray(91) { 1 }
        assertTrue(String(LicenseProof.bytes("diagnostic","test-nonce","com.shihab.diplay",key,expected),Charsets.UTF_8).startsWith("FAW-LICENSE-V1\ndiagnostic\n"))
    }
    @Test fun lineInjectionAndOversizedMetadataAreRejected() {
        for(action in listOf({metadata.copy(firmware="bad\nvalue")},{metadata.copy(versionName=" ")},{metadata.copy(model="x".repeat(101))},{metadata.copy(versionCode=0)})) {
            try { action(); fail("Invalid diagnostic metadata accepted") } catch(_:IllegalArgumentException) {}
        }
    }
}
