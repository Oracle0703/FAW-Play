package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.updates.UpdateAuthorizationException
import com.shilapi.xcertplay.updates.UpdateManifest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayOutputStream

/** Exercise the real staging/commit/result path; cryptographic validation has separate coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 33],
    shadows = [UpdateInstallCommitBoundaryTest.InstallationPrerequisitesShadow::class])
class UpdateInstallCommitBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("faw_app_updates", 0)
    private val pm = mock(PackageManager::class.java)
    private val installer = mock(PackageInstaller::class.java)
    private val installSession = mock(PackageInstaller.Session::class.java)
    private val context = object : ContextWrapper(app) {
        override fun getPackageManager(): PackageManager = pm
    }
    private val updates = AppUpdates
    private val prerequisites = mock(InstallationPrerequisites::class.java)
    private val control = HttpRequestControl(120_000L)
    private val manifest = UpdateManifest(app.packageName, 999_999, "fixture", 28, 3,
        "a".repeat(64), "b".repeat(64), "/unused.apk", "", "")
    private lateinit var apk: java.io.File
    private lateinit var staged: ByteArrayOutputStream

    @Before fun setup() {
        prefs.edit().clear().commit()
        resetDisconnectedBackground()
        assertFalse("This fixture requires a fully disconnected CarPlay session", CarPlayBackgroundSession.hasSession())
        AppUpdates.javaClass.getDeclaredField("confirmationIntent").apply { isAccessible = true }
            .set(AppUpdates, null)
        apk = temporary.newFile("verified.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        staged = ByteArrayOutputStream()
        `when`(pm.packageInstaller).thenReturn(installer)
        `when`(installer.createSession(any(PackageInstaller.SessionParams::class.java))).thenReturn(42)
        `when`(installer.openSession(42)).thenReturn(installSession)
        `when`(installSession.openWrite("base.apk", 0, manifest.sizeBytes)).thenReturn(staged)
        InstallationPrerequisitesShadow.delegate = prerequisites
        `when`(prerequisites.capability(context)).thenReturn(UpdateInstallCapability(true, "fixture-system-grant"))
        doThrow(IllegalStateException("fixture close failure")).`when`(installSession).close()
    }

    @After fun cleanup() {
        InstallationPrerequisitesShadow.delegate = null
        prefs.edit().clear().commit()
        resetDisconnectedBackground()
        AppUpdates.javaClass.getDeclaredField("confirmationIntent").apply { isAccessible = true }
            .set(AppUpdates, null)
    }

    /** clear() deliberately retains real retirement barriers; this fixture owns no live resources. */
    private fun resetDisconnectedBackground() = synchronized(CarPlayBackgroundSession) {
        fun field(name: String) = CarPlayBackgroundSession.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
        val handler = field("main").get(CarPlayBackgroundSession) as Handler
        handler.removeCallbacksAndMessages(null)
        (field("retiring").get(CarPlayBackgroundSession) as MutableList<*>).clear()
        (field("stopWaiters").get(CarPlayBackgroundSession) as MutableList<*>).clear()
        field("stopping").setBoolean(CarPlayBackgroundSession, false)
        CarPlayBackgroundSession.clear()
        handler.removeCallbacksAndMessages(null)
    }

    @Test fun closeFailureAfterSuccessfulCommitPreservesTrackingAndAcceptsTheSystemResult() {
        updates.install(context, manifest, apk, true, control)

        assertArrayEquals(apk.readBytes(), staged.toByteArray())
        val order = inOrder(prerequisites, installSession)
        order.verify(prerequisites).validateArchive(context, manifest, apk, control)
        order.verify(installSession).openWrite("base.apk", 0, manifest.sizeBytes)
        order.verify(installSession).fsync(staged)
        order.verify(prerequisites).authorizeInstallation(context, manifest, control)
        order.verify(installSession).commit(any(IntentSender::class.java))
        order.verify(installSession).close()
        verify(installer, never()).abandonSession(42)
        assertEquals(42, prefs.getInt("session_id", -1))
        assertEquals(manifest.sha256, prefs.getString("target_sha256", null))
        assertEquals(manifest.versionCode, prefs.getLong("target_version", 0))
        val nonce = prefs.getString("request_nonce", null)
        assertFalse(nonce.isNullOrBlank())

        val callback = Intent(AppUpdates.INSTALL_ACTION).putExtra("request_nonce", nonce)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("fixture.system.confirm"))
        AppUpdates.acceptInstallResult(context, callback)
        assertNotNull(AppUpdates.confirmationIntent)
        assertEquals(manifest.sha256, prefs.getString("target_sha256", null))
        callback.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        AppUpdates.acceptInstallResult(context, callback)
        assertNull(AppUpdates.confirmationIntent)
        assertFalse(prefs.contains("session_id"))
        assertFalse(prefs.contains("request_nonce"))
        assertTrue(prefs.getString("install_status", "")!!.contains("成功"))
    }

    @Test fun authorizationFailureBeforeCommitStillAbandonsAndClearsOnlyTheFailedRequest() {
        val rejected = UpdateAuthorizationException("LICENSE_REVOKED", "fixture denied")
        doAnswer { throw rejected }.`when`(prerequisites).authorizeInstallation(context, manifest, control)

        val error = assertThrows(UpdateAuthorizationException::class.java) {
            updates.install(context, manifest, apk, true, control)
        }
        assertSame(rejected, error)
        verify(installSession, never()).commit(any(IntentSender::class.java))
        verify(installer).abandonSession(42)
        assertFalse(prefs.contains("session_id"))
        assertFalse(prefs.contains("request_nonce"))
        assertFalse(prefs.contains("target_sha256"))
        assertTrue(apk.isFile)
    }

    interface InstallationPrerequisites {
        fun capability(context: Context): UpdateInstallCapability
        fun validateArchive(context: Context, manifest: UpdateManifest, file: java.io.File, control: HttpRequestControl)
        fun authorizeInstallation(context: Context, manifest: UpdateManifest, control: HttpRequestControl)
    }

    /**
     * Kotlin inline use blocks dispatch through AppUpdates.INSTANCE, not a Mockito spy's copy.
     * Shadow only external prerequisites so the actual singleton executes staging and commit.
     */
    @Implements(AppUpdates::class, isInAndroidSdk = false)
    class InstallationPrerequisitesShadow {
        @Implementation fun capability(context: Context): UpdateInstallCapability =
            requireNotNull(delegate).capability(context)

        @Implementation fun validateArchive(context: Context, manifest: UpdateManifest, file: java.io.File,
                                            control: HttpRequestControl) =
            requireNotNull(delegate).validateArchive(context, manifest, file, control)

        @Implementation fun authorizeInstallation(context: Context, manifest: UpdateManifest,
                                                   control: HttpRequestControl) =
            requireNotNull(delegate).authorizeInstallation(context, manifest, control)

        companion object {
            var delegate: InstallationPrerequisites? = null
        }
    }
}
