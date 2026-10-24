package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.hud.AutunnAdbShell
import com.shilapi.xcertplay.hud.AutunnParcel
import java.lang.reflect.Field
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pauses the head unit's automatic Wi-Fi network search while wireless CarPlay runs.
 *
 * Android 9/10 head units search for other networks even while hosting wireless CarPlay. On a
 * shared radio this can delay association or streaming. Through the approved local adb shell,
 * `IWifiManager.enableWifiConnectivityManager(false)` pauses that search when the firmware permits
 * it (CONNECTIVITY_INTERNAL is required). One process-wide worker owns leases and restores scans only
 * after the last lease ends. A durable marker precedes every disable; failed restores retry and
 * recover when the app next opens. Nothing happens without adb approval. Same LAN is excluded.
 *
 * The binder transaction number differs between firmware builds, so it is read from the framework
 * itself instead of being hard-coded; when it cannot be read, scans are left alone.
 */
internal class WifiScanPause(
    context: Context,
    private val log: (String) -> Unit,
    private val control: WifiScanPauseControl = ProcessWifiScanPause,
) {
    private val app = context.applicationContext
    private val owner = Any()
    @Volatile private var closed = false

    /** Asynchronously stops the periodic scans; repeated calls are harmless. */
    @Synchronized
    fun pause() {
        if (closed) return
        control.acquire(app, owner, { !closed }, log)
    }

    /** Ends this lease; shared recovery remains alive after this controller has closed. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        control.release(app, owner, log)
    }

    internal companion object {
        internal const val TAG = "DiPlayWifiScan"

        fun eligible(backend: WirelessHotspotBackend, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sdk in Build.VERSION_CODES.P..Build.VERSION_CODES.Q && backend != WirelessHotspotBackend.EXISTING_WIFI

        fun restoreIfNeeded(context: Context) = ProcessWifiScanPause.recover(context.applicationContext)

        fun command(code: Int, enabled: Boolean): String {
            require(code > 0)
            return "service call wifi $code i32 ${if (enabled) 1 else 0}"
        }

        /** `void` replies carry only a zero exception word. */
        fun accepted(output: String?): Boolean = AutunnParcel.words(output).firstOrNull() == 0L

        /**
         * Both Android 9 and 10 expose this method. Use the running framework's constant, including
         * vendor changes, rather than an AOSP transaction number. The indirect lookup also works on
         * Android 10's reflection restrictions. Unavailable/blocked lookups leave scans unchanged;
         * later releases are not attempted.
         */
        fun enableConnectivityManagerTransaction(): Int? {
            if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.P..Build.VERSION_CODES.Q) return null
            return runCatching {
                val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
                val lookup = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
                val field = lookup.invoke(stub, "TRANSACTION_enableWifiConnectivityManager") as Field
                field.isAccessible = true
                field.getInt(null)
            }.getOrNull()?.takeIf { it > 0 }
        }
    }
}

internal interface WifiScanPauseControl {
    fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit)
    fun release(app: Context, owner: Any, log: (String) -> Unit)
}

/** The single writer is shared across full controller rebuilds, including delayed restore retries. */
private object ProcessWifiScanPause : WifiScanPauseControl {
    private const val PREFS = "diplay_wifi_scan_pause"
    private const val JOURNAL = "restore_pending"
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, WifiScanPause.TAG).apply { isDaemon = true }
    }
    private val adb = AutunnAdbShell(WifiScanPause.TAG)
    private var session: WifiScanPauseSession? = null
    private var retry: ScheduledFuture<*>? = null

    override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
        enqueue(app) { state -> log("Wi-Fi connectivity scans paused=${state.acquire(owner, current)}") }
    }

    override fun release(app: Context, owner: Any, log: (String) -> Unit) {
        enqueue(app) { state -> log("Wi-Fi scan pause lease released; cleanup ok=${state.release(owner)}") }
    }

    fun recover(app: Context) {
        enqueue(app) { state ->
            if (state.recoveryPending()) Log.i(WifiScanPause.TAG, "Wi-Fi scan recovery restored=${state.recover()}")
        }
    }

    private fun enqueue(app: Context, action: (WifiScanPauseSession) -> Unit) {
        worker.execute {
            val state = state(app)
            runCatching { action(state) }.onFailure { Log.w(WifiScanPause.TAG, "Wi-Fi scan recovery will retry", it) }
            if (state.recoveryPending()) {
                if (retry == null) retry = worker.schedule({
                    retry = null
                    recover(app)
                }, 30, TimeUnit.SECONDS)
            } else {
                retry?.cancel(false)
                retry = null
                adb.close()
            }
        }
    }

    private fun state(app: Context): WifiScanPauseSession {
        session?.let { return it }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val journal = WifiScanPauseJournal(prefs.contains(JOURNAL)) { pending ->
            val edit = prefs.edit()
            if (pending) edit.putBoolean(JOURNAL, true) else edit.remove(JOURNAL)
            edit.commit()
        }
        return WifiScanPauseSession(
            transactionCode = WifiScanPause::enableConnectivityManagerTransaction,
            setEnabled = { code, enabled -> WifiScanPause.accepted(adb.run(app, WifiScanPause.command(code, enabled))) },
            loadJournal = { journal.pending },
            saveJournal = journal::save,
        ).also { session = it }
    }
}
