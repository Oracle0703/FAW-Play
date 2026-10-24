package com.shilapi.xcertplay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.*
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutunnVendorInstallerTest {
    private val context = mock(Context::class.java)
    private val pm = mock(PackageManager::class.java)
    private val signing = mock(SigningInfo::class.java)
    private lateinit var info: PackageInfo

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        val certificate = javaClass.getResourceAsStream("/hs5-sota-support-certificate.der")!!.use { it.readBytes() }
        `when`(signing.apkContentsSigners).thenReturn(arrayOf(Signature(certificate)))
        info = PackageInfo().apply {
            packageName = AutunnVendorInstaller.PACKAGE; versionCode = 2731; versionName = "20240905-114725"
            signingInfo = signing
            applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM; enabled = true }
            receivers = arrayOf(ActivityInfo().apply {
                name = AutunnVendorInstaller.RECEIVER; enabled = true; exported = true
            })
            services = arrayOf(ServiceInfo().apply {
                name = "${AutunnVendorInstaller.PACKAGE}.SotaSupportService"; enabled = true; exported = true
            })
        }
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getPackageInfo(eq(AutunnVendorInstaller.PACKAGE), anyInt())).thenReturn(info)
        `when`(pm.checkPermission(Manifest.permission.INSTALL_PACKAGES, AutunnVendorInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
    }

    @Test fun requiresTheVerifiedOemSignerVersionAndInstallationGrant() {
        assertTrue(AutunnVendorInstaller.available(context))
        info.versionCode++
        assertFalse(AutunnVendorInstaller.available(context))
        info.versionCode--
        `when`(signing.apkContentsSigners).thenReturn(arrayOf(Signature(byteArrayOf(1, 2))))
        assertFalse(AutunnVendorInstaller.available(context))
        setup()
        `when`(pm.checkPermission(Manifest.permission.INSTALL_PACKAGES, AutunnVendorInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertFalse(AutunnVendorInstaller.available(context))
    }

    @Test fun acceptsThe3337ContractWhoseOemBytecodeMatchesTheVehicleBaseline() {
        info.versionCode = 3337
        info.versionName = "20250210-144658"
        assertTrue(AutunnVendorInstaller.available(context))
    }

    @Test fun readsTheLegacyCertificateWhenAndroid9OmitsSigningInfo() {
        val certificate = javaClass.getResourceAsStream("/hs5-sota-support-certificate.der")!!.use { it.readBytes() }
        info.signingInfo = null
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(Signature(certificate))
        assertTrue(AutunnVendorInstaller.available(context))
        assertEquals("legacy", AutunnVendorInstaller.inspect(context).certificateSource)
    }

    @Test fun modernSignerMismatchCannotBeRescuedByATrustedLegacyCertificate() {
        @Suppress("DEPRECATION")
        info.signatures = signing.apkContentsSigners
        `when`(signing.apkContentsSigners).thenReturn(arrayOf(Signature(byteArrayOf(1, 2))))
        val result = AutunnVendorInstaller.inspect(context)
        assertFalse(result.available)
        assertEquals("CERTIFICATE_MISMATCH", result.code)
        assertEquals("SigningInfo", result.certificateSource)
    }

    @Test fun legacyCompatibilityStillRequiresExactlyOneTrustedCurrentCertificate() {
        val trusted = signing.apkContentsSigners[0]
        info.signingInfo = null
        @Suppress("DEPRECATION")
        info.signatures = null
        assertEquals("CERTIFICATE_UNAVAILABLE", AutunnVendorInstaller.inspect(context).code)
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(Signature(byteArrayOf(1, 2)))
        assertEquals("CERTIFICATE_MISMATCH", AutunnVendorInstaller.inspect(context).code)
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(trusted, trusted)
        assertEquals("CERTIFICATE_MISMATCH", AutunnVendorInstaller.inspect(context).code)
    }

    @Test fun bothVersionCodeAndVersionNameMustMatchAnAuditedPair() {
        info.versionName = "20250210-144658"
        assertEquals("VERSION_UNVERIFIED", AutunnVendorInstaller.inspect(context).code)
        info.versionCode = 3337
        assertTrue(AutunnVendorInstaller.available(context))
        info.versionCode = 3338
        assertEquals("VERSION_UNVERIFIED", AutunnVendorInstaller.inspect(context).code)
    }

    @Test fun honoursRuntimeDisableOverridesForTheAppAndBothComponents() {
        `when`(pm.getApplicationEnabledSetting(AutunnVendorInstaller.PACKAGE))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        assertEquals("APP_DISABLED", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getApplicationEnabledSetting(AutunnVendorInstaller.PACKAGE))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val receiver = ComponentName(AutunnVendorInstaller.PACKAGE, AutunnVendorInstaller.RECEIVER)
        val service = ComponentName(AutunnVendorInstaller.PACKAGE, info.services!![0].name)
        `when`(pm.getComponentEnabledSetting(receiver)).thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertEquals("RECEIVER_DISABLED", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getComponentEnabledSetting(receiver)).thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        `when`(pm.getComponentEnabledSetting(service)).thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertEquals("SERVICE_DISABLED", AutunnVendorInstaller.inspect(context).code)
    }

    @Test fun usesManifestEnabledValuesUnlessExplicitlyEnabledByPackageManager() {
        info.applicationInfo!!.enabled = false
        assertEquals("APP_DISABLED", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getApplicationEnabledSetting(AutunnVendorInstaller.PACKAGE))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        info.receivers!![0].enabled = false
        assertEquals("RECEIVER_DISABLED", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getComponentEnabledSetting(ComponentName(AutunnVendorInstaller.PACKAGE, AutunnVendorInstaller.RECEIVER)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        info.services!![0].enabled = false
        assertEquals("SERVICE_DISABLED", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getComponentEnabledSetting(ComponentName(AutunnVendorInstaller.PACKAGE, info.services!![0].name)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        assertTrue(AutunnVendorInstaller.available(context))
    }

    @Test fun distinguishesAbsentPackagesFromQueryFailuresWithoutExposingExceptionContents() {
        `when`(pm.getPackageInfo(eq(AutunnVendorInstaller.PACKAGE), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException("private exception contents"))
        assertEquals("PACKAGE_NOT_FOUND", AutunnVendorInstaller.inspect(context).code)
        `when`(pm.getPackageInfo(eq(AutunnVendorInstaller.PACKAGE), anyInt()))
            .thenThrow(SecurityException("private exception contents"))
        val report = AutunnVendorInstaller.diagnosticReport(context)
        assertTrue(report.contains("reason=QUERY_FAILED"))
        assertTrue(report.contains("SecurityException"))
        assertFalse(report.contains("private exception contents"))
    }

    @Test fun exposesTheDetectedVersionAndSpecificFailureOnTheUpdatePageAndInDiagnostics() {
        `when`(context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        info.versionCode = 3337
        info.versionName = "20250210-144658"
        val capability = AppUpdates.capability(context)
        assertTrue(capability.silent)
        assertTrue(capability.vendor)
        info.versionCode = 9999
        val rejected = AppUpdates.capability(context)
        assertFalse(rejected.silent)
        assertTrue(rejected.message.contains("9999/20250210-144658"))
        val report = AutunnVendorInstaller.diagnosticReport(context)
        assertTrue(report.contains("reason=VERSION_UNVERIFIED"))
        assertTrue(report.contains("certificateSource=SigningInfo"))
    }

    @Test fun rejectsReplacedDisabledOrPermissionProtectedComponentsAndOtherDevices() {
        assertTrue(AutunnVendorInstaller.available(context))
        info.applicationInfo!!.flags = 0
        assertFalse(AutunnVendorInstaller.available(context))
        info.applicationInfo!!.flags = ApplicationInfo.FLAG_SYSTEM
        info.receivers!![0].permission = "vendor.signature.permission"
        assertFalse(AutunnVendorInstaller.available(context))
        info.receivers!![0].permission = null
        info.services!![0].exported = false
        assertFalse(AutunnVendorInstaller.available(context))
        info.services!![0].exported = true
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "phone")
        assertFalse(AutunnVendorInstaller.available(context))
    }

    @Test @Config(sdk = [33]) fun doesNotAssumeTheAndroid9ContractOnNewerFirmware() {
        assertFalse(AutunnVendorInstaller.available(context))
    }
}
