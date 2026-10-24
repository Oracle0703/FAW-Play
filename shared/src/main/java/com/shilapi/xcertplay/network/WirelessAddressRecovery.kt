package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** One alternate family per connection cycle, shared by all of its Controller retries. */
class WirelessAddressRecovery {
    data class Snapshot(val preferredFamily: String? = null, val switched: Boolean = false, val confirmedFamily: String? = null)

    private var state = Snapshot()
    private var revision = 0L

    @Synchronized fun token(): Long = revision
    @Synchronized fun snapshot(): Snapshot = state

    /** A missing preferred family never adds a wait to the normal startup path. */
    @Synchronized fun select(primary: InetAddress?, candidates: List<InetAddress>): InetAddress? {
        val usable = candidates.filter(::usable).distinct()
        return usable.firstOrNull { family(it) == state.preferredFamily }
            // Inet6Address.equals ignores scope. Return the fresh interface sample.
            ?: usable.firstOrNull { it == primary }
            ?: usable.firstOrNull()
    }

    /** Call with a fresh sample of the current GO interface, never a previous group's snapshot. */
    @Synchronized fun reserveAlternate(
        token: Long,
        published: InetAddress,
        candidates: List<InetAddress>,
    ): Boolean {
        if (token != revision || state.switched) return false
        val currentFamily = family(published) ?: return false
        val alternate = candidates.firstOrNull { usable(it) && family(it) != currentFamily } ?: return false
        state = state.copy(preferredFamily = family(alternate), switched = true)
        return true
    }

    /** A late confirmation from a previous connection cannot reset the new cycle's budget. */
    @Synchronized fun confirmed(token: Long, published: InetAddress): Boolean {
        if (token != revision || !usable(published)) return false
        // A remembered family may be absent on this interface. Confirm the actual publication,
        // including an ordinary first success, rather than the requested preference.
        val confirmed = Snapshot(family(published), confirmedFamily = family(published))
        val changed = state != confirmed
        state = confirmed
        revision++
        return changed
    }

    @Synchronized fun beginCycle() {
        state = Snapshot(state.confirmedFamily, confirmedFamily = state.confirmedFamily)
        revision++
    }

    @Synchronized fun reset(): Boolean {
        val changed = state != Snapshot()
        state = Snapshot()
        revision++
        return changed
    }

    @Synchronized fun restore(saved: Snapshot) {
        state = saved.copy(preferredFamily = saved.preferredFamily?.takeIf { it == IPV4 || it == IPV6 },
            confirmedFamily = saved.confirmedFamily?.takeIf { it == IPV4 || it == IPV6 })
        revision++
    }

    private fun family(address: InetAddress): String? = when (address) {
        is Inet4Address -> IPV4
        is Inet6Address -> IPV6
        else -> null
    }

    private fun usable(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress
        is Inet6Address -> address.isLinkLocalAddress && address.scopeId > 0
        else -> false
    }

    companion object {
        const val IPV4 = "IPv4"
        const val IPV6 = "IPv6"
    }
}
