package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupDiagnosticSnapshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        DiPlayPreferences.saveAutoConnect(app, false)
        ShadowLog.clear()
    }

    @Test fun disabledBootIsRecordedWithoutLaunchingOrChangingTheSetting() {
        var launched = false
        var serviceRequested = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = true }
            override fun startForegroundService(service: Intent): ComponentName? {
                serviceRequested = true
                return ComponentName(app, DiPlaySessionService::class.java)
            }
        }
        DiPlayPreferences.saveAutoConnect(app, true)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(launched)
        assertFalse(serviceRequested)
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(DiPlayPreferences.autoConnect(app))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchEnabledAtBoot=false launchResult=disabled"))
    }

    @Test fun disablingAutomaticConnectionLeavesTheDesktopAndRequiresManualEntry() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        var launch: Intent? = null
        var serviceRequested = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launch = intent }
            override fun startForegroundService(service: Intent): ComponentName? {
                serviceRequested = true
                return ComponentName(app, DiPlaySessionService::class.java)
            }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(serviceRequested)
        assertNull(launch)
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
        val report = StartupDiagnosticSnapshot.report(app)
        assertTrue(report.contains("launchResult=manual-entry-only"))
        assertTrue(report.contains("serviceStartResult=not-requested serviceFailureClass=none"))
    }

    @Test fun automaticConnectionStartsOnlyTheBackgroundServiceWithoutOpeningHome() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var requested: Intent? = null
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                requested = service
                return ComponentName(app, DiPlaySessionService::class.java)
            }
            override fun startActivity(intent: Intent) { launched = true }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(DiPlaySessionService::class.java.name, requested!!.component!!.className)
        assertFalse(launched)
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(DiPlayPreferences.autoConnect(app))
        val report = StartupDiagnosticSnapshot.report(app)
        assertTrue(report.contains("launchResult=background-service-start-requested"))
        assertTrue(report.contains("serviceStartResult=requested serviceFailureClass=none"))
        assertTrue(report.contains("does not prove a connection or visible UI"))
    }

    @Test fun rejectedBackgroundServiceStaysOnTheDesktopAndRecordsNoPrivateErrorDetails() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var launch: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw SecurityException("Jane's phone token=private-data")
            }
            override fun startActivity(intent: Intent) { launch = intent }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(launch)
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(DiPlayPreferences.autoConnect(app))
        val report = StartupDiagnosticSnapshot.report(app)
        assertTrue(report.contains("launchResult=background-service-start-failed failureClass=none"))
        assertTrue(report.contains("serviceStartResult=failed serviceFailureClass=SecurityException"))
        assertFalse(report.contains("Jane"))
        assertFalse(report.contains("private-data"))
        val logs = ShadowLog.getLogsForTag("xcertplay-boot")
        assertTrue(logs.any { it.msg.contains("SecurityException") })
        assertTrue(logs.all { it.throwable == null && !it.msg.contains("Jane") && !it.msg.contains("private-data") })

        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val nextBoot = StartupDiagnosticSnapshot.report(app)
        assertTrue(nextBoot.contains("launchResult=disabled failureClass=none"))
        assertTrue(nextBoot.contains("serviceStartResult=not-requested serviceFailureClass=none"))
    }

    @Test fun unresolvedBackgroundServiceRecordsFailureWithoutOpeningHomeOrChangingSettings() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = null
            override fun startActivity(intent: Intent) { launched = true }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(launched)
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(DiPlayPreferences.autoConnect(app))
        assertTrue(StartupDiagnosticSnapshot.report(app)
            .contains("serviceStartResult=failed serviceFailureClass=IllegalStateException"))
    }

    @Test fun aFailedServiceNeverAttemptsAnActivityFallback() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var activityAttempts = 0
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("private service details")
            }
            override fun startActivity(intent: Intent) {
                activityAttempts++
                throw SecurityException("private activity details")
            }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val report = StartupDiagnosticSnapshot.report(app)
        assertEquals(0, activityAttempts)
        assertTrue(report.contains("launchResult=background-service-start-failed failureClass=none"))
        assertTrue(report.contains("serviceStartResult=failed serviceFailureClass=IllegalStateException"))
        assertFalse(report.contains("private"))
    }

    @Test fun aManualEntryBootReplacesThePreviousServiceFailureEvidence() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var activityAttempts = 0
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw SecurityException("Jane's phone token=private-data")
            }
            override fun startActivity(intent: Intent) { activityAttempts++ }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val failed = StartupDiagnosticSnapshot.report(app)
        assertTrue(failed.contains("launchResult=background-service-start-failed"))
        assertTrue(failed.contains("serviceFailureClass=SecurityException"))
        assertFalse(failed.contains("Jane")); assertFalse(failed.contains("private-data"))
        assertNotNull(DiagnosticRedactor.redact(failed))
        DiPlayPreferences.saveAutoConnect(app, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val latest = StartupDiagnosticSnapshot.report(app)
        assertTrue(latest.contains("launchResult=manual-entry-only failureClass=none"))
        assertTrue(latest.contains("serviceStartResult=not-requested serviceFailureClass=none"))
        assertEquals(0, activityAttempts)
    }

    @Test fun unrelatedBroadcastsDoNotCreateOrOverwriteBootEvidence() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        var launched = false
        var serviceRequested = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = true }
            override fun startForegroundService(service: Intent): ComponentName? {
                serviceRequested = true
                return ComponentName(app, DiPlaySessionService::class.java)
            }
        }
        BootReceiver().onReceive(context, Intent("custom.intent.token=secret"))
        assertFalse(launched)
        assertFalse(serviceRequested)
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("received=false"))
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(DiPlayPreferences.autoConnect(app))

        // A later unrelated broadcast must not replace an already received boot either.
        DiPlayPreferences.saveAutoConnect(app, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val bootEvidence = StartupDiagnosticSnapshot.report(app)
        BootReceiver().onReceive(context, Intent("custom.intent.token=secret"))
        assertEquals(bootEvidence, StartupDiagnosticSnapshot.report(app))
        assertFalse(StartupDiagnosticSnapshot.report(app).contains("secret"))
    }
}
