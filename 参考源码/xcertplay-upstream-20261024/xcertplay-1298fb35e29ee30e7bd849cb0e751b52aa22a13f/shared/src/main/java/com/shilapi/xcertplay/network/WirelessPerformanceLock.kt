package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable

/** Keeps wireless CarPlay responsive while the session is active, including in the background. */
internal class WirelessPerformanceLock(
    context: Context,
    private val log: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private var highPerformance: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        val manager = appContext.getSystemService(WifiManager::class.java) ?: return
        if (highPerformance == null) {
            highPerformance = acquire(manager, WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xcertplay:high-performance")
        }
        // Android applies low latency only while the app is foreground; high performance covers background use.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && lowLatency == null) {
            lowLatency = acquire(manager, WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "xcertplay:low-latency")
        }
    }

    private fun acquire(manager: WifiManager, mode: Int, tag: String): WifiManager.WifiLock? {
        return try {
            manager.createWifiLock(mode, tag).apply {
                setReferenceCounted(false)
                acquire()
                log("Wireless Wi-Fi lock acquired: $tag")
            }
        } catch (error: RuntimeException) {
            log("Wireless Wi-Fi lock unavailable: $tag: ${error.message}")
            null
        }
    }

    @Synchronized
    override fun close() {
        listOfNotNull(lowLatency, highPerformance).forEach { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (error: RuntimeException) {
                log("Wireless Wi-Fi lock release failed: ${error.message}")
            }
        }
        lowLatency = null
        highPerformance = null
    }
}
