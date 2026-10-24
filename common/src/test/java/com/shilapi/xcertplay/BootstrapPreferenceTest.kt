package com.shilapi.xcertplay

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.*
import org.junit.Test

import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BootstrapPreferenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun prepare() {
        identity()
        ReflectionHelpers.setStaticField(DiPlayBootstrap::class.java, "ready", false)
    }

    @Test fun authenticationPreservesEnabledDebugPreferenceAcrossProcessRestarts() {
        AirPlayPersistence.saveDebugLogsEnabled(app, true)
        repeat(2) {
            ReflectionHelpers.setStaticField(DiPlayBootstrap::class.java, "ready", false)
            DiPlayBootstrap.ensure(app, MfiTarget.LOCAL)
            assertTrue(AirPlayPersistence.loadDebugLogsEnabled(app))
        }
    }

    @Test fun authenticationAlsoPreservesExplicitlyDisabledPreference() {
        AirPlayPersistence.saveDebugLogsEnabled(app, false)
        DiPlayBootstrap.ensure(app, MfiTarget.LOCAL)
        assertFalse(AirPlayPersistence.loadDebugLogsEnabled(app))
    }

    private fun identity(): File = File(app.noBackupFilesDir, "offline-mfi").apply { mkdirs() }.also { directory ->
        // Fresh self-signed test material for each test. No stored accessory identity.
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay synthetic test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.encoded)
        val certificate = DERSequence(arrayOf<ASN1Encodable>(
            tbs, algorithm, DERBitString(signer.sign()),
        )).encoded
        File(directory, "identity.pk8").writeBytes(pair.private.encoded)
        File(directory, "certificate.p7b").writeBytes(certificate)
    }

}
