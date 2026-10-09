package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object FawNavigationOutputs {
    // Retain this facade's ABI and preference keys for installed versions. This HS5 build
    // delegates output only to Hongqi; the route decoder below is shared CarPlay protocol code.
    fun onAppOpened(context: Context) {
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
    }
    fun setDiagnosticHold(hold: Boolean) {}
    @Volatile private var streamControl: ((Boolean) -> Unit)? = null
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = FawHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
    )
    fun setClusterMapShown(shown: Boolean) {}
    fun setClusterStreamControl(control: (Boolean) -> Unit) { streamControl = control }
    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (streamControl == control) streamControl = null
    }
    // Retained ABI only: vehicle speed comes from the HS5 Android vehicle HAL.
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        com.shilapi.xcertplay.transport.VehicleStatusProvider { null }
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        HongqiVehicleSpeed(context)
    fun parked(context: Context): Boolean? = null

    fun start(context: Context) { HongqiNavigation.start(context.applicationContext) }

    internal fun onFrame(frame: Iap2Frame) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED) return
        if (frame.messageId != FawHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != FawHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        updateOverlay(frame)
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED) {
            listener?.invoke(null)
            return
        }
        synchronized(overlayLock) {
            overlayListener = listener
            val next = currentOverlay()
            // A host can attach after the first route packet. Publish to both outputs before
            // recording the value, otherwise refresh treats undelivered guidance as unchanged.
            HongqiNavigation.onGuidance(next)
            publishedOverlay = next
            listener?.invoke(next)
        }
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != FawHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED) return
        synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            HongqiNavigation.onGuidance(current)
            publishedOverlay = current
            overlayListener?.invoke(current)
        }
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(FawClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

    // Old callers remain linkable, with no vendor service or ADB operation.
    fun clusterSongChanged(enabled: Boolean) {}
    fun clusterSongOnChangeChanged() {}
    fun dashboardNote(text: String, source: Int? = null) {}

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        HongqiNavigation.clear()
        synchronized(overlayLock) { overlayRoute.clear() }
        refreshTurnOverlay()
    }
}
