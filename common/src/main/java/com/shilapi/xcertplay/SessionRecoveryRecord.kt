package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings

/** Control intent only. No vehicle sample, session, pairing key or network handle is restored. */
internal data class SessionRecoveryRecord(
    val useGeneration: Long,
    val desired: Boolean,
    val manualDisconnect: Boolean,
    val parked: Boolean,
    val terminated: Boolean,
    val retries: Int,
    val retryStopped: Boolean,
    val useSawIgnitionOn: Boolean = false,
    val explicitUse: Boolean = false,
    val retryRequiresUserAction: Boolean = false,
    val addressFamily: String? = null,
    val addressFamilySwitched: Boolean = false,
    val startupDeadlineMillis: Long? = null,
    val automaticBootstrapAttempted: Boolean = false,
    val confirmedAddressFamily: String? = null,
) {
    companion object {
        private const val PREFS = "faw_session_control"
        private fun boot(context: Context): Int = Settings.Global.getInt(
            context.contentResolver, Settings.Global.BOOT_COUNT, -1)

        fun load(context: Context): SessionRecoveryRecord? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val currentBoot = boot(context)
            if (currentBoot < 0 || prefs.getInt("boot", -2) != currentBoot) return null
            return SessionRecoveryRecord(prefs.getLong("use", 0), prefs.getBoolean("desired", false),
                prefs.getBoolean("manual", false), prefs.getBoolean("parked", false),
                prefs.getBoolean("terminated", false), prefs.getInt("retries", 0),
                prefs.getBoolean("retry_stopped", false), prefs.getBoolean("use_ignited", false),
                prefs.getBoolean("explicit_use", false),
                prefs.getBoolean("retry_user_action", prefs.getBoolean("retry_stopped", false) &&
                    prefs.getInt("retries", 0) < com.shilapi.xcertplay.network.WirelessStartupPolicy.MAX_STARTUP_RETRIES),
                prefs.getString("address_family", null), prefs.getBoolean("address_family_switched", false),
                prefs.getLong("startup_deadline", 0L).takeIf { it > 0L },
                prefs.getBoolean("automatic_bootstrap_attempted", false),
                prefs.getString("confirmed_address_family", null))
        }

        fun save(context: Context, value: SessionRecoveryRecord): Boolean {
            // End-of-use intent must survive process death before any automatic resume.
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("boot", boot(context)).putLong("use", value.useGeneration)
                .putBoolean("desired", value.desired).putBoolean("manual", value.manualDisconnect)
                .putBoolean("parked", value.parked).putBoolean("terminated", value.terminated)
                .putInt("retries", value.retries).putBoolean("retry_stopped", value.retryStopped)
                .putBoolean("use_ignited", value.useSawIgnitionOn)
                .putBoolean("explicit_use", value.explicitUse)
                .putBoolean("retry_user_action", value.retryRequiresUserAction)
                .putString("address_family", value.addressFamily)
                .putBoolean("address_family_switched", value.addressFamilySwitched)
                .putLong("startup_deadline", value.startupDeadlineMillis ?: 0L)
                .putBoolean("automatic_bootstrap_attempted", value.automaticBootstrapAttempted)
                .putString("confirmed_address_family", value.confirmedAddressFamily).commit()
            if (!saved) CarPlayBackgroundSession.recordUiDiagnostic("session control persistence failed")
            return saved
        }
    }
}
