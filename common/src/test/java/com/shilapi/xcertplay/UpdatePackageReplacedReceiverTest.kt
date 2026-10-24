package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.shilapi.xcertplay.licensing.LicensePackageSignature
import com.shilapi.xcertplay.shared.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UpdatePackageReplacedReceiverTest {
    private val context = mock(Context::class.java)
    private val pm = mock(PackageManager::class.java)
    private val signing = mock(SigningInfo::class.java)
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("faw_app_updates", 0)
    private val info = PackageInfo().apply { versionCode = 100; signingInfo = signing }
    private val certificate = javaClass.getResourceAsStream("/autunn-play-release-certificate.der")!!.use { it.readBytes() }
    private val receiver = UpdatePackageReplacedReceiver()

    @Before fun setup() {
        prefs.edit().clear().commit()
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.getSharedPreferences("faw_app_updates", 0)).thenReturn(prefs)
        `when`(pm.getPackageInfo(eq("test.app"), anyInt())).thenReturn(info)
        `when`(signing.apkContentsSigners).thenReturn(arrayOf(Signature(certificate)))
    }

    private fun markPending() {
        prefs.edit().putLong("target_version", 100).putLong("vendor_started", 1).commit()
    }

    @Test fun successfulSelfUpdateOpensTheMainActivityOnce() {
        // Unconfigured source builds cannot authorize a production certificate.
        // Release validation supplies the original signer and runs this assertion.
        assumeTrue(LicensePackageSignature.matchesSingleSigner(arrayOf(certificate), BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256))
        markPending()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val launch = ArgumentCaptor.forClass(Intent::class.java)
        verify(context).startActivity(launch.capture())
        assertEquals(DiPlayActivity::class.java.name, launch.value.component!!.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, launch.value.flags)
        assertEquals("更新已安装成功。", prefs.getString("install_status", ""))
        assertFalse(prefs.contains("vendor_started"))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(context, times(1)).startActivity(any(Intent::class.java))
    }

    @Test fun unrelatedReplacementWithoutAnAppUpdateDoesNotOpenAnything() {
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(context, never()).startActivity(any(Intent::class.java))
    }

    @Test fun aDifferentBroadcastCannotCompleteOrLaunchAPendingUpdate() {
        markPending()
        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_REPLACED))
        verify(context, never()).startActivity(any(Intent::class.java))
        assertTrue(prefs.contains("vendor_started"))
    }

    @Test fun anUninstalledTargetVersionDoesNotLaunchOrClaimSuccess() {
        markPending()
        info.versionCode = 99
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(context, never()).startActivity(any(Intent::class.java))
        assertTrue(prefs.contains("target_version"))
        assertFalse(prefs.contains("install_status"))
    }

    @Test fun anUnexpectedSigningCertificateDoesNotLaunch() {
        markPending()
        `when`(signing.apkContentsSigners).thenReturn(arrayOf(Signature(byteArrayOf(1, 2))))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(context, never()).startActivity(any(Intent::class.java))
        assertTrue(prefs.contains("vendor_started"))
    }

    @Test @Config(sdk = [33]) fun newerAndroidDoesNotAssumeTheVehicleBackgroundLaunchPolicy() {
        markPending()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(context, never()).startActivity(any(Intent::class.java))
        assertTrue(prefs.contains("vendor_started"))
    }
}
