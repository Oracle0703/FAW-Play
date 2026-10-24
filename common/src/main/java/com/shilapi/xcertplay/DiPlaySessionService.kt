package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.host.R

/** Owns connection and vehicle monitoring independently of the current page. */
class DiPlaySessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var foregroundStarted = false
    private var vehicleData: java.io.Closeable? = null
    private val automaticEntryCheck = Runnable { enterReadyProjection() }
    private val entryDispatcher = AutomaticEntryDispatcher(this, eligible = { !destroyed })

    /** Coalesced state-change delivery; the visible page uses the same dispatcher policy. */
    fun scheduleAutomaticEntry() {
        main.removeCallbacks(automaticEntryCheck)
        if (!destroyed) main.post(automaticEntryCheck)
    }

    private fun enterReadyProjection() = entryDispatcher.dispatch()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Meet the FGS deadline before reading recovery preferences or preparing a stack.
        if (!foregroundStarted) {
            refreshSessionState(initialStart = true)
            com.shilapi.xcertplay.hud.AutunnNavigationOutputs.onAppOpened(applicationContext)
            com.shilapi.xcertplay.licensing.ProductLicense.refreshAsync(applicationContext)
        }
        CarPlayBackgroundSession.attachService(this)
        CarPlayBackgroundSession.recordUiDiagnostic("stage=service-start startId=$startId " +
            "stop=${intent?.action == ACTION_STOP} restart=${intent == null}")
        if (intent?.action == ACTION_STOP) {
            // A manual disconnect may retain the lightweight ignition watch. Only a fully
            // stopped session ends this service; parking and closing do not hold audio/location.
            CarPlayBackgroundSession.stop { refreshSessionState() }
        } else CarPlayBackgroundSession.resumeService(this)
        refreshSessionState()
        return if (CarPlayBackgroundSession.serviceMode() == "stopped") START_NOT_STICKY else START_STICKY
    }

    /** Session ownership can change while no Activity exists; keep its notification current. */
    fun refreshSessionState(initialStart: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshSessionState(initialStart) }
            return
        }
        if (destroyed) return
        if (!initialStart) scheduleAutomaticEntry()
        try {
            updateForegroundNotification(initialStart)
            foregroundStarted = true
        } catch (error: RuntimeException) {
            // Notification/FGS policy must never interrupt ownership transfer or resource cleanup.
            Log.w(TAG, "Could not refresh CarPlay foreground service", error)
            CarPlayBackgroundSession.recordUiDiagnostic("service notification refresh failed=${error.javaClass.simpleName}")
        }
    }

    private fun updateForegroundNotification(initialStart: Boolean = false) {
        val mode = if (initialStart) "waiting" else CarPlayBackgroundSession.serviceMode()
        // Vehicle reads share a separate, serial worker and follow connection ownership.
        // Waiting for ignition alone must not keep the wider vehicle provider alive.
        if (mode == "connected" && vehicleData == null) {
            vehicleData = com.shilapi.xcertplay.hud.HongqiVehicleData.acquire(applicationContext)
        } else if (mode != "connected") {
            vehicleData?.close()
            vehicleData = null
        }
        if (mode == "stopped") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiPlayActivity::class.java)
            .putExtra(LAUNCHER_ENTRY_MARKER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("Autunn Play")
            .setContentText(when (mode) {
                "connected" -> "CarPlay已连接"
                "connecting" -> "正在连接CarPlay"
                "closing" -> "正在断开CarPlay连接"
                "failed" -> "CarPlay准备失败，请返回软件主页重试"
                "cleanup-blocked" -> "上一连接尚未释放，已暂停重连"
                else -> "CarPlay待连接"
            })
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "断开连接", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (mode == "connecting" || mode == "connected") {
                if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                // Keep location reporting available when the reversing camera or another car
                // application covers a live CarPlay session.
                if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
            }
            try {
                startForeground(1, notification, types)
            } catch (error: SecurityException) {
                if (types == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) throw error
                // Android 14+ may deny upgrading while-in-use microphone/location types during
                // background ignition recovery. The device connection can still continue; a
                // foreground Host refresh will request the live types again when eligible.
                CarPlayBackgroundSession.recordUiDiagnostic("service foreground types limited by background permission policy; using connectedDevice")
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.AutunnNavigationOutputs.endNow()
        // A foreground launcher may reopen while cleanup is still running. Decide from
        // the current use when cleanup completes, so the old callback cannot stop its service.
        CarPlayBackgroundSession.terminate { refreshSessionState() }
        refreshSessionState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        vehicleData?.close()
        vehicleData = null
        entryDispatcher.cancel()
        main.removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.detachService(this)
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
        private const val TAG = "Autunn-SessionService"
    }
}
