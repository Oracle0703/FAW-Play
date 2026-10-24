package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Android 9 permits reopening the launcher after this explicit self-update.
        // Phone updates and unrelated package replacements do not relaunch the application.
        if (Build.VERSION.SDK_INT == 28 && runCatching { AppUpdates.completeVendorSelfUpdate(context) }.getOrDefault(false)) {
            runCatching { context.startActivity(Intent(context, DiPlayActivity::class.java)
                .putExtra(LAUNCHER_ENTRY_MARKER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        }
    }
}
