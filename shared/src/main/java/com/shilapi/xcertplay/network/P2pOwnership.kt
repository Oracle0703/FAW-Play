package com.shilapi.xcertplay.network

import java.io.IOException
import java.security.MessageDigest

/** Reinstall-stable namespace; never treats a generic DIRECT-* name as ours. */
internal object P2pOwnership {
    fun prefix(packageName: String, scopedId: String): String {
        return "DIRECT-AP-Autunn-${deviceId(packageName, scopedId).take(8)}-"
    }

    fun previousPrefix(packageName: String, scopedId: String): String =
        "DIRECT-FP-FAW-Play-${deviceId(packageName, scopedId).take(8)}-"

    fun legacyPrefix(packageName: String, scopedId: String): String =
        "DIRECT-dp${deviceId(packageName, scopedId)}-"

    fun deviceName(prefix: String): String =
        prefix.removePrefix("DIRECT-AP-").removePrefix("DIRECT-FP-").removeSuffix("-")

    private fun deviceId(packageName: String, scopedId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$scopedId".toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun canReclaim(isOwner: Boolean, ssid: String?, recorded: String?, prefix: String,
        legacyPrefix: String? = null, previousPrefix: String? = null): Boolean =
        isOwner && !ssid.isNullOrBlank() &&
            (ssid == recorded || matchesOwnedName(ssid, prefix) ||
                (legacyPrefix != null && matchesPrefix(ssid, legacyPrefix)) ||
                (previousPrefix != null && matchesOwnedName(ssid, previousPrefix)))

    private fun matchesOwnedName(ssid: String, prefix: String): Boolean =
        matchesPrefix(ssid, prefix) ||
            ssid.matches(Regex("DIRECT-[A-Za-z0-9]{2}-${Regex.escape(deviceName(prefix))}"))

    private fun matchesPrefix(ssid: String, prefix: String): Boolean =
        ssid.startsWith(prefix) && ssid.removePrefix(prefix).matches(Regex("[A-Za-z0-9]{4}"))
}

class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")
