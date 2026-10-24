package com.shilapi.xcertplay

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/** Updates native app colors without changing the projected CarPlay session. */
internal class AutunnUiAppearanceWatch(
    private val activity: Activity,
    private val onAppearanceChanged: Runnable,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = AutunnUiTheme.preferences(activity)
    private var closed = false
    private var listening = false
    private var lastDark = AutunnUiTheme.isDark(activity)
    private val vehicle = HongqiNightModeSource(activity, ::vehicleChanged)
    private val refresh = Runnable { refreshAppearance() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode" || key == "dark") {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            refreshAppearance()
            handler.postDelayed(this, 2_000L)
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        handler.post(poll)
    }

    /** HongqiNightModeSource delivers validated vehicle state on the main thread. */
    internal fun vehicleChanged(night: Boolean?) {
        if (closed || AutunnUiTheme.mode(activity) != "system") return
        AutunnUiTheme.updateVehicleNight(activity, night)
        refreshAppearance()
    }

    private fun refreshAppearance() {
        if (closed) return
        val automatic = AutunnUiTheme.mode(activity) == "system"
        if (automatic && !listening) {
            listening = true
            vehicle.start()
        } else if (!automatic && listening) {
            listening = false
            vehicle.stop()
            AutunnUiTheme.updateVehicleNight(activity, null)
        }
        val dark = AutunnUiTheme.isDark(activity)
        if (dark == lastDark) return
        lastDark = dark
        onAppearanceChanged.run()
    }

    override fun close() {
        if (closed) return
        closed = true
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        handler.removeCallbacksAndMessages(null)
        vehicle.stop()
        AutunnUiTheme.updateVehicleNight(activity, null)
    }
}
