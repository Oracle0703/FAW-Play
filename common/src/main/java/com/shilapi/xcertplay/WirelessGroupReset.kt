package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/** One explicit, user-confirmed reset. The deadline includes stopping the old session. */
internal class WirelessGroupReset(
    context: Context,
    private val allowed: () -> Boolean,
    private val complete: (Boolean) -> Unit,
    private val timeoutMillis: Long = 10_000,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(WifiP2pManager::class.java)
    private val ownership = app.getSharedPreferences("carplay_wifi_p2p", 0)
    private val owner = ownership.getString("active_owner", null)
    private var channel: WifiP2pManager.Channel? = null
    private var finished = false
    private val timeout = Runnable { finish(false) }

    fun start(stop: (() -> Unit) -> Unit) {
        handler.postDelayed(timeout, timeoutMillis)
        try {
            stop { handler.post { begin() } }
        } catch (_: RuntimeException) { finish(false) }
    }

    fun cancel() { finish(false, notify = false) }

    private fun valid(): Boolean = !finished && allowed() && ownership.getString("active_owner", null) == owner

    private fun begin() {
        if (!valid() || manager == null) { finish(false); return }
        try {
            val current = manager.initialize(app, Looper.getMainLooper()) { finish(false) }
                ?: run { finish(false); return }
            channel = current
            manager.requestGroupInfo(current) { group ->
                if (!valid()) { finish(false); return@requestGroupInfo }
                if (group == null) { finish(true); return@requestGroupInfo }
                val name = group.networkName
                val iface = group.`interface`
                val isOwner = group.isGroupOwner
                try {
                    manager.removeGroup(current, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { poll(current, name, iface, isOwner) }
                        override fun onFailure(reason: Int) { finish(false) }
                    })
                } catch (_: RuntimeException) { finish(false) }
            }
        } catch (_: RuntimeException) { finish(false) }
    }

    private fun poll(current: WifiP2pManager.Channel, name: String?, iface: String?, isOwner: Boolean) {
        if (!valid() || channel !== current) { finish(false); return }
        try {
            manager!!.requestGroupInfo(current) { group ->
                if (!valid() || channel !== current) { finish(false); return@requestGroupInfo }
                when {
                    group == null -> finish(true)
                    group.networkName != name || group.`interface` != iface || group.isGroupOwner != isOwner -> finish(false)
                    else -> handler.postDelayed({ poll(current, name, iface, isOwner) }, 100)
                }
            }
        } catch (_: RuntimeException) { finish(false) }
    }

    private fun finish(success: Boolean, notify: Boolean = true) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        val current = channel
        channel = null
        runCatching { current?.close() }
        if (notify) complete(success)
    }
}
