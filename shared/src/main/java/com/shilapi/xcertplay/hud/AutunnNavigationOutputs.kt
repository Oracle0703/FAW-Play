package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object AutunnNavigationOutputs {
    // Retain this facade's ABI and preference keys for installed versions. This HS5 build
    // delegates output only to Hongqi; the route decoder below is shared CarPlay protocol code.
    fun onAppOpened(context: Context) {
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
    }
    fun setDiagnosticHold(hold: Boolean) {}
    @Volatile private var streamControl: ((Boolean) -> Unit)? = null
    private val streamControlLock = Any()
    @Volatile private var outputContext: Context? = null
    private var followClusterVisibility = false
    private var lastStreamRequest: Boolean? = null
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    @Volatile private var laneListener: ((ClusterLaneGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private var publishedRouteActive = false
    private var publishedLanes: ClusterLaneGuidance? = null
    private var lastLaneDiagnostic: String? = null
    private var lastRouteDiagnostic: String? = null
    private val laneRoute = AutunnLaneGuidanceState()
    private val overlayRoute = AutunnHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
    )
    /** Follow the physical HS5 map only when no other output needs its retained stream. */
    fun setClusterMapShown(shown: Boolean) = synchronized(streamControlLock) {
        followClusterVisibility = shown
        publishClusterVisibility()
    }
    fun setClusterStreamControl(control: (Boolean) -> Unit) = synchronized(streamControlLock) {
        streamControl = control
        lastStreamRequest = null
        publishClusterVisibility()
    }
    fun clearClusterStreamControl(control: (Boolean) -> Unit) = synchronized(streamControlLock) {
        if (streamControl == control) { streamControl = null; lastStreamRequest = null }
    }
    fun ownsClusterStreamControl(control: (Boolean) -> Unit): Boolean = streamControl == control
    fun clusterStreamRequestedShown(): Boolean = synchronized(streamControlLock) {
        !com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED ||
            !HongqiNavigation.supported() || !followClusterVisibility ||
            outputContext?.let { HongqiNavigation.mapShown(it) } != false
    }
    /** Settings and native return events share this boundary; its callback only queues work. */
    @JvmStatic fun refreshClusterMapVisibility() = synchronized(streamControlLock) { publishClusterVisibility() }
    /** One explicit content request when a new route starts; no frame-rate watchdog or reconnect. */
    @JvmStatic fun requestClusterContent() = synchronized(streamControlLock) {
        if (clusterStreamRequestedShown()) publishClusterVisibility(force = true)
    }
    private fun publishClusterVisibility(force: Boolean = false) {
        val shown = clusterStreamRequestedShown()
        if (!force && lastStreamRequest == shown) return
        val control = streamControl ?: return
        lastStreamRequest = shown
        control(shown)
    }
    // Retained ABI only: vehicle speed comes from the HS5 Android vehicle HAL.
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        com.shilapi.xcertplay.transport.VehicleStatusProvider { null }
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        HongqiVehicleSpeed(context)
    fun parked(context: Context): Boolean? = null

    fun start(context: Context) {
        outputContext = context.applicationContext
        HongqiNavigation.start(context.applicationContext)
        refreshClusterMapVisibility()
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) return
        if (frame.messageId != AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != AutunnLaneGuidanceState.LANE_GUIDANCE_INFORMATION) return
        updateOverlay(frame)
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) {
            listener?.invoke(null)
            return
        }
        synchronized(overlayLock) {
            overlayListener = listener
            val next = currentOverlay()
            // A host can attach after the first route packet. Publish to both outputs before
            // recording the value, otherwise refresh treats undelivered guidance as unchanged.
            val routeActive = overlayRoute.isRouteActive()
            HongqiNavigation.onRouteGuidance(routeActive, next)
            publishedRouteActive = routeActive
            publishedOverlay = next
            listener?.invoke(next)
        }
    }

    /** Independent of the custom turn card: native instruments also need vehicle lane metadata. */
    fun setLaneOverlayListener(listener: ((ClusterLaneGuidance?) -> Unit)?) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) {
            listener?.invoke(null)
            return
        }
        synchronized(overlayLock) {
            laneListener = listener
            publishedLanes = laneRoute.current()
            listener?.invoke(publishedLanes)
        }
    }

    fun clearLaneOverlayListener(listener: (ClusterLaneGuidance?) -> Unit) {
        synchronized(overlayLock) {
            if (laneListener === listener) laneListener = null
        }
    }

    fun clearTurnOverlayListener(listener: (ClusterTurnGuidance?) -> Unit) {
        synchronized(overlayLock) {
            if (overlayListener === listener) overlayListener = null
        }
    }

    private fun updateOverlay(frame: Iap2Frame) {
        synchronized(overlayLock) {
            if (frame.messageId == AutunnLaneGuidanceState.LANE_GUIDANCE_INFORMATION) {
                android.util.Log.i("Autunn-Lanes", "received bytes=${frame.payload.size}")
            }
            overlayRoute.accept(frame.messageId, frame.payload)
            laneRoute.accept(frame.messageId, frame.payload)
            refreshTurnOverlay()
        }
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) return
        synchronized(overlayLock) {
            val routeDiagnostic = overlayRoute.diagnosticState()
            if (routeDiagnostic != lastRouteDiagnostic) {
                lastRouteDiagnostic = routeDiagnostic
                android.util.Log.i("Autunn-Route", "state=$routeDiagnostic")
            }
            val current = currentOverlay()
            val routeActive = overlayRoute.isRouteActive()
            if (routeActive != publishedRouteActive || current != publishedOverlay) {
                HongqiNavigation.onRouteGuidance(routeActive, current)
                publishedRouteActive = routeActive
                publishedOverlay = current
                overlayListener?.invoke(current)
            }
            val lanes = laneRoute.current()
            val laneDiagnostic = laneRoute.diagnosticState()
            if (laneDiagnostic != lastLaneDiagnostic) {
                lastLaneDiagnostic = laneDiagnostic
                android.util.Log.i("Autunn-Lanes", "state=$laneDiagnostic")
            }
            if (lanes != publishedLanes) {
                publishedLanes = lanes
                // Counts and indices are enough to diagnose the lane path; no road or route payload.
                android.util.Log.i("Autunn-Lanes", if (lanes == null) "hidden" else
                    "display index=${lanes.index} lanes=${lanes.lanes.size}")
                laneListener?.invoke(lanes)
            }
        }
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(AutunnClusterFrame.from(apple)).copy(
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
        synchronized(overlayLock) {
            overlayRoute.clear()
            laneRoute.clear()
            lastLaneDiagnostic = null
            refreshTurnOverlay()
        }
    }
}
