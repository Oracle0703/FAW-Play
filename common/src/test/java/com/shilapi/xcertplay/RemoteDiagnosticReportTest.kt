package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test

class RemoteDiagnosticReportTest {
    @Test fun redactionKeepsPerformanceAndSafeInstallerReaderButRemovesPersonalDataAndSecrets() {
        val report=RemoteDiagnosticReport.prepare("video frames=42\naudio dropped=3\ncertificateSource=SigningInfo\npassword=secret\ntoken=secret\nssid=secret\nPHONE name=my phone\nTRACE private data\nuser@example.com\nFAW-abcdefghijklmnopqrstuvwxyz0123456789\nAUTUNN-abcdefghijklmnopqrstuvwxyz0123456789\npeer=192.168.1.9 mac=aa:bb:cc:dd:ee:ff\n")
        assertFalse(report.truncated)
        assertTrue(report.text.contains("video frames=42")); assertTrue(report.text.contains("audio dropped=3"))
        assertTrue(report.text.contains("signerReader=SigningInfo")); assertTrue(report.text.contains("[ip]"))
        for(value in listOf("secret","my phone","private data","example.com","FAW-","AUTUNN-","192.168","aa:bb")) assertFalse(value,report.text.contains(value))
    }
    @Test fun byteBudgetKeepsNewestFirstReportAndMakesOmittedHistoryExplicit() {
        val report=RemoteDiagnosticReport.prepare("state summary\nlatest audio queue=2\n"+(1..100).joinToString("\n") { "较早日志 $it" }, 200)
        assertTrue(report.truncated); assertTrue(report.text.toByteArray(Charsets.UTF_8).size<=200)
        assertTrue(report.text.startsWith("state summary\nlatest audio queue=2")); assertTrue(report.text.contains("older history omitted"))
        assertFalse(report.text.contains("\uFFFD"))
    }
    @Test fun controlsCannotInjectMetadataTranscriptLines() {
        assertEquals("HS5 firmware 3222",RemoteDiagnosticReport.metadataText("HS5\nfirmware\t3222",200))
        assertEquals(80,RemoteDiagnosticReport.metadataText("x".repeat(100),80).length)
    }
    @Test fun httpHeadersAndBareSignedCredentialsAreNotUploaded() {
        val result=RemoteDiagnosticReport.prepare("audio frames=42\nAuthorization: Bearer abc\nCookie: session=abc\naccess_key=abc\neyJheaderValue.eyJclaimsValue.veryLongSignatureValue\ncertificateSource=raw-public-data\ncertificateSource=legacy\n")
        assertEquals("audio frames=42\nsignerReader=legacy\n\n",result.text)
    }
}
