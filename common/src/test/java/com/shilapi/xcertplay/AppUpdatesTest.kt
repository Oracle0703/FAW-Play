package com.shilapi.xcertplay

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.SystemClock
import com.shilapi.xcertplay.updates.UpdateException
import com.shilapi.xcertplay.updates.UpdateManifest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 33])
class AppUpdatesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("faw_app_updates", 0)
    @Before fun reset() { prefs.edit().clear().commit(); CarPlayBackgroundSession.clear() }

    @Test fun openingUpdateRecoveryReclaimsAnInterruptedPartWithoutDeletingReadyApksOrOtherCacheFiles() {
        val directory = AppUpdates.directory(app).apply { mkdirs() }
        val part = File(directory, "${"c".repeat(64)}.apk.part").apply { writeText("interrupted") }
        val ready = File(directory, "${"c".repeat(64)}.apk").apply { writeText("downloaded") }
        val unrelated = File(directory, "reference-note.txt").apply { writeText("keep") }
        try {
            AppUpdates.ready(app)
            assertFalse(part.exists())
            assertTrue(ready.exists())
            assertEquals("keep", unrelated.readText())
        } finally { part.delete(); ready.delete(); unrelated.delete() }
    }

    @Test fun silentCapabilityRequiresActualSystemGrantOrDeviceOwner() {
        val context = mock(Context::class.java)
        val dpm = mock(DevicePolicyManager::class.java)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.getSystemService(DevicePolicyManager::class.java)).thenReturn(dpm)
        `when`(context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES)).thenReturn(PackageManager.PERMISSION_DENIED)
        assertFalse(AppUpdates.capability(context).silent)
        `when`(dpm.isAdminActive(any())).thenReturn(true)
        assertFalse(AppUpdates.capability(context).silent)
        `when`(dpm.isDeviceOwnerApp("test.app")).thenReturn(true)
        assertTrue(AppUpdates.capability(context).silent)
        `when`(dpm.isDeviceOwnerApp("test.app")).thenReturn(false)
        `when`(context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES)).thenReturn(PackageManager.PERMISSION_GRANTED)
        assertTrue(AppUpdates.capability(context).silent)
    }

    @Test fun rejectsInstallationWhileConnectionIsBeingPreparedBeforeReadingApk() {
        val field = CarPlayBackgroundSession.javaClass.getDeclaredField("stopAction").apply { isAccessible = true }
        val stop: ((() -> Unit) -> Unit) = { it() }
        field.set(CarPlayBackgroundSession, stop)
        assertFalse(CarPlayBackgroundSession.active)
        val m = UpdateManifest(app.packageName, 99, "test", 28, 1, "a".repeat(64), "b".repeat(64), "", "", "")
        try {
            AppUpdates.install(app, m, File("/missing.apk"), false)
            fail("Installing during connection was accepted")
        } catch (e: UpdateException) { assertTrue(e.message!!.contains("CarPlay")) }
        finally { CarPlayBackgroundSession.clear() }
    }

    @Test fun rejectsSpoofedInstallCallbacksAndTracksSystemConfirmationAndFailure() {
        prefs.edit().putString("request_nonce", "expected").putInt("session_id", 12).putLong("target_version", 99).commit()
        fun callback(nonce: String = "expected", session: Int = 12, status: Int = PackageInstaller.STATUS_SUCCESS) =
            Intent(AppUpdates.INSTALL_ACTION).putExtra("request_nonce", nonce)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, session).putExtra(PackageInstaller.EXTRA_STATUS, status)
        AppUpdates.acceptInstallResult(app, callback(nonce = "wrong"))
        AppUpdates.acceptInstallResult(app, callback(session = 13))
        assertFalse(prefs.contains("install_status"))
        val system = Intent("system.confirm")
        AppUpdates.acceptInstallResult(app, callback(status = PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, system))
        assertNotNull(AppUpdates.confirmationIntent)
        assertEquals(12, prefs.getInt("session_id", -1))
        AppUpdates.acceptInstallResult(app, callback(status = PackageInstaller.STATUS_FAILURE_ABORTED))
        assertNull(AppUpdates.confirmationIntent)
        assertFalse(prefs.contains("target_version"))
        assertTrue(prefs.getString("install_status", "")!!.contains("未完成"))
        prefs.edit().clear().commit()
        AppUpdates.acceptInstallResult(app, Intent(AppUpdates.INSTALL_ACTION).putExtra(PackageInstaller.EXTRA_SESSION_ID, -2))
        assertFalse(prefs.contains("install_status"))
    }

    @Test fun reconcilesInstalledVersionAfterSelfUpdateRestart() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageName).thenReturn("test.app")
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.getSharedPreferences("faw_app_updates", 0)).thenReturn(prefs)
        val info = PackageInfo().apply { versionCode = 89 }
        `when`(pm.getPackageInfo("test.app", 0)).thenReturn(info)
        prefs.edit().putLong("target_version", 90).putInt("session_id", 4).putString("request_nonce", "old").commit()
        assertEquals("", AppUpdates.installStatus(context))
        info.versionCode = 90
        assertEquals("更新已安装成功。", AppUpdates.installStatus(context))
        assertFalse(prefs.contains("request_nonce"))
        assertFalse(prefs.contains("session_id"))
    }

    @Test fun vendorInstallationTimesOutWithoutClaimingSuccessOrLosingTheDownloadedApk() {
        prefs.edit().putLong("target_version", 99).putLong("vendor_started", SystemClock.elapsedRealtime())
            .putString("install_status", "车机正在安装更新…").commit()
        assertEquals("车机正在安装更新…", AppUpdates.installStatus(app))
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(121))
        assertEquals("车机未完成更新安装，请重试。", AppUpdates.installStatus(app))
        assertFalse(prefs.contains("target_version"))
        assertFalse(prefs.contains("vendor_started"))
    }

    @Test fun aRejectedNewRequestPreservesAnExistingVendorInstallation() {
        prefs.edit().putLong("target_version", 99).putLong("vendor_started", SystemClock.elapsedRealtime())
            .putString("install_status", "车机正在安装更新…").commit()
        val stop: ((() -> Unit) -> Unit) = { it() }
        CarPlayBackgroundSession.javaClass.getDeclaredField("stopAction").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, stop)
        val m = UpdateManifest(app.packageName, 99, "test", 28, 1, "a".repeat(64), "b".repeat(64), "", "", "")
        try {
            AppUpdates.install(app, m, File("/missing.apk"), true)
            fail("Installing during connection was accepted")
        } catch (e: UpdateException) { assertTrue(e.message!!.contains("CarPlay")) }
        assertTrue(prefs.contains("vendor_started"))
        assertEquals(99, prefs.getLong("target_version", 0))
        assertEquals("车机正在安装更新…", prefs.getString("install_status", ""))
    }
}
