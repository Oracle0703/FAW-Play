package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build

/** Reads the live group's frequency; Android 9 has no public frequency getter. */
internal class P2pGroupRadioInfo {
    private val driverSettled = LegacyHotspotRadio.Settled()
    var detail: String = "frequency unavailable"
        private set

    fun frequencyMHz(group: WifiP2pGroup, interfaceName: String): Int? {
        val framework = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else {
            // Some car frameworks backport this getter. No hidden setters or access override.
            runCatching { (group.javaClass.getMethod("getFrequency").invoke(group) as? Number)?.toInt() }
                .getOrNull()
        }
        if (framework != null && wifiFrequencyMhzToChannel(framework) != null) {
            detail = "frequencySource=group"
            return framework
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            detail = "group frequency unavailable"
            return null
        }
        val reading = LegacyHotspotRadio.readP2p(interfaceName)
        val frequency = driverSettled.observe(reading.frequencyMHz, System.nanoTime() / 1_000_000)
        detail = reading.error ?: if (frequency == null) "driver frequency settling" else "frequencySource=driver"
        return frequency
    }
}
