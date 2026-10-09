package com.shilapi.xcertplay.network

/** Non-DFS channel choices. Android still enforces the radio's capabilities and country limits. */
object WifiP2pChannels {
    const val AUTO = 0
    const val PREFER_5_GHZ = -1
    val experimentalChannels = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    val channels: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + (1..11)

    fun isValid(channel: Int): Boolean = channel == PREFER_5_GHZ || channel == AUTO || channel in channels

    fun selectExperimentalChannel(preference: Int, stationMHz: Int?): Int {
        if (preference != PREFER_5_GHZ) return preference
        val station = stationMHz?.let(::wifiFrequencyMhzToChannel)
        return station?.takeIf { it in experimentalChannels } ?: experimentalChannels.first()
    }

    fun legacySelectionSupported(): Boolean = android.os.Build.VERSION.SDK_INT == 28 &&
        android.os.Build.MODEL == "HONG QI" && android.os.Build.DEVICE == "t1c_acrn" &&
        runCatching { android.net.wifi.p2p.WifiP2pManager::class.java.getMethod("setWifiP2pChannels",
            android.net.wifi.p2p.WifiP2pManager.Channel::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, android.net.wifi.p2p.WifiP2pManager.ActionListener::class.java) }.isSuccess

    fun frequencyMhz(channel: Int): Int? {
        require(isValid(channel)) { "Unsupported Wi-Fi Direct channel: $channel" }
        return when {
            channel == AUTO || channel == PREFER_5_GHZ -> null
            channel <= 11 -> 2407 + channel * 5
            else -> 5000 + channel * 5
        }
    }
}

internal class P2pChannelUnavailableException(channel: Int, details: String, cause: Throwable? = null) :
    java.io.IOException("Wi-Fi Direct could not use channel $channel. Choose Auto or another channel. $details", cause)
