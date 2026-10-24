package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.updates.UpdateAuthorizationException
import com.shilapi.xcertplay.updates.UpdateException
import com.shilapi.xcertplay.updates.UpdateManifest
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 33])
class UpdateConfirmationAuthorizationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("faw_app_updates", 0)
    private val hash = "ab".repeat(32)
    private val licenseFile get() = File(app.noBackupFilesDir, "product-license-v1.json")
    private var originalLicense: ByteArray? = null

    @Before fun setup() {
        prefs.edit().clear().commit()
        setConfirmation(null)
        originalLicense = licenseFile.takeIf { it.exists() }?.readBytes()
    }

    @After fun cleanup() {
        prefs.edit().clear().commit()
        setConfirmation(null)
        originalLicense?.let { licenseFile.writeBytes(it) } ?: licenseFile.delete()
    }

    private fun manifest() = UpdateManifest(app.packageName, 90, "new", 28, 1, hash,
        "cd".repeat(32), "/unused.apk", "", "")

    private fun pending(): Intent {
        prefs.edit().putString("request_nonce", "expected-nonce").putInt("session_id", 12)
            .putLong("target_version", 90).putString("target_sha256", hash).commit()
        val confirmation = Intent("test.system.confirm")
        AppUpdates.acceptInstallResult(app, callback(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation))
        return confirmation
    }

    private fun callback(status: Int) = Intent(AppUpdates.INSTALL_ACTION)
        .putExtra("request_nonce", "expected-nonce").putExtra(PackageInstaller.EXTRA_SESSION_ID, 12)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)

    @Test fun confirmationMustMatchTheExactPendingPackageVersionAndArtifact() {
        val ticket = "cached-offline-license-must-survive".toByteArray()
        licenseFile.parentFile!!.mkdirs()
        licenseFile.writeBytes(ticket)
        val current = manifest()
        for (different in listOf(current.copy(packageName = "other.app"), current.copy(versionCode = 91),
            current.copy(sha256 = "ef".repeat(32)))) {
            pending()
            assertThrows(UpdateException::class.java) { AppUpdates.confirmationFor(app, different) }
            assertNull("A stale confirmation must not remain available", AppUpdates.confirmationIntent)
            assertEquals(12, prefs.getInt("session_id", -1))
            assertEquals(hash, prefs.getString("target_sha256", null))
            assertArrayEquals(ticket, licenseFile.readBytes())
        }
    }

    @Test fun missingOrLegacyPendingMetadataCannotAuthorizeAConfirmation() {
        for (field in listOf("session_id", "request_nonce", "target_version", "target_sha256")) {
            pending()
            prefs.edit().remove(field).commit()
            assertThrows(UpdateException::class.java) { AppUpdates.confirmationFor(app, manifest()) }
            assertNull(AppUpdates.confirmationIntent)
        }
        pending()
        setConfirmation(null)
        assertThrows(UpdateException::class.java) { AppUpdates.confirmationFor(app, manifest()) }
    }

    @Test fun theExactPendingTargetReturnsOnlyItsSystemConfirmation() {
        val expected = pending()
        assertEquals(expected.action, AppUpdates.confirmationFor(app, manifest()).action)
        assertEquals(hash, prefs.getString("target_sha256", null))
        assertEquals(90L, prefs.getLong("target_version", 0))
    }

    @Test fun localDevelopmentAllowanceCannotReplaceOnlineAuthorizationOrEraseTheCachedTicket() {
        assumeFalse(ProductLicense.isRequired())
        assumeTrue(ProductLicense.configurationProblem() != null)
        val ticket = "cached-offline-license-must-survive".toByteArray()
        licenseFile.parentFile!!.mkdirs()
        licenseFile.writeBytes(ticket)
        pending()
        assertTrue(ProductLicense.check(app).allowed)
        AppUpdates.confirmationFor(app, manifest())
        val error = assertThrows(UpdateAuthorizationException::class.java) {
            AppUpdates.authorizeInstallation(app, manifest())
        }
        assertEquals("NOT_CONFIGURED", error.code)
        assertArrayEquals(ticket, licenseFile.readBytes())
        assertNotNull(AppUpdates.confirmationIntent)
        assertEquals(hash, prefs.getString("target_sha256", null))
    }

    @Test fun terminalResultsClearTheArtifactBindingButPendingUserActionKeepsIt() {
        for (status in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_INVALID)) {
            pending()
            assertEquals(hash, prefs.getString("target_sha256", null))
            AppUpdates.acceptInstallResult(app, callback(status))
            assertFalse(prefs.contains("target_sha256"))
            assertFalse(prefs.contains("target_version"))
            assertNull(AppUpdates.confirmationIntent)
        }
    }

    @Test fun timedOutVendorInstallClearsItsArtifactBinding() {
        prefs.edit().putLong("target_version", 999_999).putString("target_sha256", hash)
            .putLong("vendor_started", SystemClock.elapsedRealtime()).commit()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(121))
        assertTrue(AppUpdates.installStatus(app).contains("未完成"))
        assertFalse(prefs.contains("target_sha256"))
        assertFalse(prefs.contains("target_version"))
        assertFalse(prefs.contains("vendor_started"))
    }

    private fun setConfirmation(intent: Intent?) {
        AppUpdates.javaClass.getDeclaredField("confirmationIntent").apply { isAccessible = true }.set(AppUpdates, intent)
    }
}
