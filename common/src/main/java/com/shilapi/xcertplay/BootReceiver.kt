package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts an enabled automatic connection in the background; boot never opens a page. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        if (!DiPlayPreferences.autoConnect(context)) {
            StartupDiagnosticSnapshot.manualEntryOnly(context)
            return
        }
        try {
            checkNotNull(context.startForegroundService(Intent(context, DiPlaySessionService::class.java))) {
                "Boot connection service was not resolved"
            }
            StartupDiagnosticSnapshot.backgroundServiceResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.backgroundServiceResult(context, error)
            // Exception messages can contain platform or device details. The user can still
            // open the launcher manually; failure must not force the home page to the front.
            Log.w(TAG, "Boot connection service could not start (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
