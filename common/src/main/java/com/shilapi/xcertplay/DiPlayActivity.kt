// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Intent marker used when the app itself explicitly opens its software home page. */
internal const val EXPLICIT_SOFTWARE_PAGE = "com.shihab.diplay.explicit_software_page"
/** Marker used by non-launcher entry points which intentionally use the installed launcher alias. */
internal const val LAUNCHER_ENTRY_MARKER = "com.shihab.diplay.launcher_entry"
/** Marker carried when the launcher has already chosen the disconnected software route. */
internal const val LAUNCHER_SOFTWARE_FALLBACK = "com.shihab.diplay.launcher_software_fallback"
/** Marker carried by in-app Host launches so they are never mistaken for the installed icon. */
internal const val INTERNAL_HOST_ENTRY = "com.shihab.diplay.internal_host_entry"
internal const val DI_PLAY_ENTRY_COMPONENT = "com.shilapi.xcertplay.DiPlayActivity"

/**
 * The installed icon is the invisible [DiPlayActivity] router, never the Host itself. Keep the
 * intent contract in one place so OEM launchers, widgets, notifications and the router agree on
 * the same entry semantics.
 *
 * The marker is the only reliable fact once an intent has left the launcher: the router must
 * hand it to the Host, because a component-targeted [CarPlayHostActivity] intent carries neither
 * `ACTION_MAIN` nor `CATEGORY_LAUNCHER`. Dropping it made the Host treat a desktop tap as an
 * internal launch, which revealed the preparation cover and built the full Host UI instead of
 * handing off to the retained CarPlay surface.
 */
internal object LauncherEntryRouting {
    fun isLauncherIntent(value: Intent): Boolean {
        if (value.getBooleanExtra(INTERNAL_HOST_ENTRY, false)) return false
        val componentEntry = value.component?.className == DI_PLAY_ENTRY_COMPONENT
        val launcherEntry = value.action == Intent.ACTION_MAIN && value.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (!componentEntry && !launcherEntry && !value.getBooleanExtra(LAUNCHER_ENTRY_MARKER, false)) return false
        val page = value.getStringExtra(PAGE_KEY)
        return page == null || page == PAGE_HOME && !value.getBooleanExtra(EXPLICIT_SOFTWARE_PAGE, false)
    }

    /**
     * Re-stamps an in-flight launcher request for the Host. A launcher click is a task handoff,
     * not a new connection attempt, so the intent must not drag `NEW_TASK`-visible identity
     * changes or the background-entry token into the Host.
     */
    fun forHost(value: Intent, context: Context): Intent =
        Intent(context, CarPlayHostActivity::class.java).apply {
            putExtra(LAUNCHER_ENTRY_MARKER, true)
            value.getStringExtra(PAGE_KEY)?.let { putExtra(PAGE_KEY, it) }
            // Resolve into the Host's own task. NEW_TASK alone would still be satisfied by the
            // retained CarPlay task, which is what hides the handoff decision from the Host.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }

    private const val PAGE_KEY = "page"
    private const val PAGE_HOME = "home"
}

/** Keeps the installed launcher/OEM component stable without ever drawing the software page. */
class DiPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // A launcher click carries no background-entry token, so this is a no-op for it and
            // still rejects a stale queued auto-entry. Only the Host accepts the token.
            if (!CarPlayBackgroundSession.acceptBackgroundEntry(intent)) return
            // Explicit software/recovery links always remain accessible. A launcher click
            // restores the live phone even when its main video was withdrawn on HOME.
            if (isLauncherEntry(intent) && CarPlayBackgroundSession.launcherEntryBelongsToHost()) {
                try {
                    startActivity(LauncherEntryRouting.forHost(intent, this))
                    CarPlayBackgroundSession.cancelAutomaticEntry("launcher-connected-resume")
                    CarPlayBackgroundSession.recordUiDiagnostic("launcher routed directly to current CarPlay without software window")
                    return
                } catch (error: RuntimeException) {
                    CarPlayBackgroundSession.recordUiDiagnostic("launcher direct entry failed=${error.javaClass.simpleName}")
                }
            }
            // The router has its own short-lived task. The software page lives in the
            // normal app task, so another launcher click cannot restore its old snapshot
            // before this routing decision has run.
            val software = Intent(intent).setClass(this, DiPlaySettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            startActivity(software)
            CarPlayBackgroundSession.recordUiDiagnostic("launcher routed to software page=${intent.getStringExtra("page") ?: "home"}")
        } catch (error: RuntimeException) {
            CarPlayBackgroundSession.recordUiDiagnostic("launcher routing failed=${error.javaClass.simpleName}")
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun isLauncherEntry(value: Intent): Boolean = LauncherEntryRouting.isLauncherIntent(value)
}
