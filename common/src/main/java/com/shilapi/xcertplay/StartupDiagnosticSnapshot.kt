package com.shilapi.xcertplay

import android.content.Context

/** Only the latest boot delivery and background-start result; no private Intent or error details. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putLong("receivedElapsedRealtime", android.os.SystemClock.elapsedRealtime())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass")
                .remove("serviceStartResult")
                .remove("serviceFailureClass").apply()
        }
    }

    fun manualEntryOnly(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("launchResult", "manual-entry-only").apply()
        }
    }

    fun backgroundServiceResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("serviceStartResult", if (failure == null) "requested" else "failed")
                .putString("serviceFailureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?"))
                .putString("launchResult", if (failure == null) "background-service-start-requested"
                    else "background-service-start-failed")
            editor.apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receivedAt = prefs.getLong("receivedAt", 0)
        if (receivedAt == 0L) {
            "Boot diagnostics received=false; no boot delivery has been recorded by this version."
        } else {
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "receivedElapsedRealtimeMs=${prefs.getLong("receivedElapsedRealtime", -1)} " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}; " +
                "serviceStartResult=${prefs.getString("serviceStartResult", "not-requested")} " +
                "serviceFailureClass=${prefs.getString("serviceFailureClass", "none") ?: "none"}; " +
                "boot never opens an Activity; background-service-start-requested does not prove a connection or visible UI."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}
