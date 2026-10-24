package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.HongqiUltraDisplayLease
import com.shilapi.xcertplay.hud.HongqiVehicleData
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice (DiLink 5) or com.xdja.containerservice (DiLink 4). The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display), Hs5UltraRenderer {
    private var waitingLabel: TextView? = null
    private var turnCardView: ClusterTurnCardView? = null
    private var laneView: ClusterLaneGuidanceView? = null
    private var rootView: FrameLayout? = null
    private var videoView: VideoSurfaceView? = null
    private var classicVideoLayout: FrameLayout.LayoutParams? = null
    private var classicWaitingLayout: FrameLayout.LayoutParams? = null
    private var classicBackground: android.graphics.drawable.Drawable? = null
    private var ultraView: Hs5UltraClusterView? = null
    private var ultraState: HongqiHs5UiState? = null
    private var ultraLease: HongqiUltraDisplayLease? = null
    private var streamActive = false
    internal val ultraRendererReady: Boolean get() = ultraState?.let { state ->
        state.activeComponents.any { it in setOf("speed", "gear", "vehicleStatus") } ||
            streamActive && "navigation" in state.activeComponents
    } ?: false
    private var currentTurnGuidance: ClusterTurnGuidance? = null
    private var currentLaneGuidance: ClusterLaneGuidance? = null
    private val ultraMain = Handler(Looper.getMainLooper())
    private val ultraRefresh = object : Runnable {
        override fun run() {
            val state = ultraState ?: return
            ultraView?.update(state, ultraDark(state), HongqiVehicleData.snapshot(), SystemClock.elapsedRealtime())
            ultraMain.postDelayed(this, 250L)
        }
    }
    var outputSurface: Surface? = null
        private set
    var mapVisible = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.HongqiNavigation.preparePresentation(this)
        val size = sizeOf(display)
        val adaptive = DiLink51ClusterLayout.supported()
        val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
        val dark = DiLink51ClusterLayout.dark(context, theme)
        val backdrop = if (dark) Color.rgb(15, 22, 30) else Color.rgb(207, 218, 229)
        val root = FrameLayout(context).apply {
            setBackgroundColor(if (plan == null) Color.BLACK else if (plan.fullMap) backdrop else Color.TRANSPARENT)
        }
        rootView = root
        classicBackground = root.background
        if (plan != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val videoParams = if (plan == null) FrameLayout.LayoutParams(-1, -1) else
            FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        val video = VideoSurfaceView(context, onSurface = { surface ->
            outputSurface = surface
            onSurface(surface)
        })
        videoView = video
        classicVideoLayout = FrameLayout.LayoutParams(videoParams)
        classicWaitingLayout = FrameLayout.LayoutParams(videoParams)
        root.addView(video, videoParams)
        if (plan != null) {
            if (plan.fullMap) root.addView(InstrumentContrastView(context, plan, backdrop), FrameLayout.LayoutParams(-1, -1))
            Log.i(TAG, "layout=$theme viewport=$plan renderer=SurfaceView")
        }
        waitingLabel = TextView(context).apply {
            text = context.getString(R.string.cluster_waiting_for_map)
            setTextColor(if (plan != null && !dark) Color.DKGRAY else Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
        root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
        if (com.shilapi.xcertplay.hud.HongqiNavigation.supported()) {
            laneView = ClusterLaneGuidanceView(context).apply { visibility = View.GONE }
            root.addView(laneView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
    }

    /** Hides the placeholder once the phone streams the cluster screen. */
    fun setStreamActive(active: Boolean) {
        streamActive = active
        waitingLabel?.visibility = if (active) View.GONE else View.VISIBLE
        com.shilapi.xcertplay.hud.HongqiNavigation.streamActive(this, active)
        ultraLease?.updateSession(true, ultraRendererReady)
    }

    override fun onStop() {
        clearUltraState()
        laneView?.setGuidance(null)
        com.shilapi.xcertplay.hud.HongqiNavigation.presentationStopped(this)
        super.onStop()
    }

    /** Window alpha hides the pixels without destroying the SurfaceView/decoder surface. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        if (ultraState != null) return // The session lease owns visibility during a local layout.
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) {
        turnCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setTurnCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
        laneView?.setNightMode(night)
    }

    fun setTurnCardOpacity(percent: Int) {
        turnCardView?.setOpacity(percent)
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        currentTurnGuidance = guidance
        if (ultraState == null) turnCardView?.setGuidance(guidance)
    }

    fun setLaneGuidance(guidance: ClusterLaneGuidance?) {
        currentLaneGuidance = guidance
        if (ultraState == null) laneView?.setGuidance(guidance)
    }

    /** Apply only the local, documented Autunn resource schema; opaque commands fail honestly. */
    override fun applyUltraState(state: HongqiHs5UiState): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || !supportedUltraState(state)) return false
        if (state.layoutId == null) { clearUltraState(); return true }
        val root = rootView ?: return false
        val video = videoView ?: return false
        if (!isShowing || sizeOf(display) != Point(1920, 720) ||
            !com.shilapi.xcertplay.hud.HongqiNavigation.supported()) return false
        val lease = ultraLease ?: try { HongqiUltraDisplayLease(context, this) }
            catch (failure: RuntimeException) {
                Log.w(TAG, "local layout output unavailable: ${failure.javaClass.simpleName}")
                return false
            }
        ultraLease = lease
        ultraState = state.copy(activeComponents = state.activeComponents.toSet(),
            driveModeToLayoutIdAssignments = state.driveModeToLayoutIdAssignments.toMap())
        val overview = state.layoutId == HongqiHs5UiLayouts.OVERVIEW
        video.layoutParams = if (overview) FrameLayout.LayoutParams(1280, 480, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = 320; topMargin = 120
        } else FrameLayout.LayoutParams(1920, 720, Gravity.TOP or Gravity.LEFT)
        waitingLabel?.layoutParams = FrameLayout.LayoutParams(video.layoutParams as FrameLayout.LayoutParams)
        root.setBackgroundColor(if (ultraDark(state)) Color.rgb(13, 20, 28) else Color.rgb(238, 243, 247))
        turnCardView?.setGuidance(null)
        laneView?.setGuidance(null)
        val overlay = ultraView ?: Hs5UltraClusterView(context).also {
            ultraView = it
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        overlay.update(state, ultraDark(state), HongqiVehicleData.snapshot(), SystemClock.elapsedRealtime())
        ultraMain.removeCallbacks(ultraRefresh)
        ultraMain.postDelayed(ultraRefresh, 250L)
        lease.updateSession(true, ultraRendererReady)
        return true
    }

    override fun clearUltraState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ultraMain.post { clearUltraState() }
            return
        }
        if (ultraState == null && ultraLease == null && ultraView == null) return
        ultraMain.removeCallbacks(ultraRefresh)
        ultraState = null
        ultraView?.let { rootView?.removeView(it) }
        ultraView = null
        classicVideoLayout?.let { videoView?.layoutParams = FrameLayout.LayoutParams(it) }
        classicWaitingLayout?.let { waitingLabel?.layoutParams = FrameLayout.LayoutParams(it) }
        rootView?.background = classicBackground
        val closingLease = ultraLease
        ultraLease = null
        turnCardView?.setGuidance(currentTurnGuidance)
        laneView?.setGuidance(currentLaneGuidance)
        if (!com.shilapi.xcertplay.hud.HongqiNavigation.supported()) setMapVisible(mapVisible)
        closingLease?.close()
    }

    private fun ultraDark(state: HongqiHs5UiState): Boolean {
        val preference = if (state.appearanceOverride == true) state.appearancePreference
            else state.appearanceMode ?: state.appearancePreference
        return when (preference) {
            "dark" -> true
            "light" -> false
            else -> DiLink51ClusterLayout.dark(context, theme)
        }
    }

    private fun supportedUltraState(state: HongqiHs5UiState): Boolean {
        val layout = state.layoutId?.let { id -> HongqiHs5UiLayouts.ALL.firstOrNull { it.id == id } ?: return false }
        if (layout == null && state.activeComponents.isNotEmpty()) return false
        if (layout != null && !layout.components.containsAll(state.activeComponents)) return false
        if (state.fadeOutOldLayout || state.focusToken != null) return false
        if (state.appearanceMode != null && state.appearanceMode !in setOf("dark", "light")) return false
        if (state.appearancePreference != null && state.appearancePreference !in setOf("dark", "light", "system")) return false
        if (state.appearanceOverride != null && state.appearanceOverride !is Boolean) return false
        if (state.appearanceOverride == true && state.appearancePreference == null) return false
        if (state.driveModeToLayoutIdAssignments.values.any { id -> HongqiHs5UiLayouts.ALL.none { it.id == id } }) return false
        if (state.driveMode != null && (!state.driveModeToLayoutIdAssignments.containsKey(state.driveMode) ||
                state.layoutId == null || state.driveModeToLayoutIdAssignments[state.driveMode] != state.layoutId)) return false
        if (state.uiConfiguration != null && state.uiConfiguration != HongqiHs5UiLayouts.RESOURCE_VERSION &&
            state.uiConfiguration != state.layoutId) return false
        if (state.uiConfigurationData != null && state.uiConfigurationData != emptyMap<String, Any?>()) return false
        return true
    }

    companion object {
        const val TAG = "DiPlay-Cluster"

        /** Keep the 5/5.1 selection order, then try the measured DiLink 4 projection display. */
        fun findDisplay(context: Context, theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context)): Display? {
            if (com.shilapi.xcertplay.hud.HongqiNavigation.supported()) {
                return com.shilapi.xcertplay.hud.HongqiNavigation.findDisplay(context)
            }
            val displays = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            val name = DiLink51ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            )
            if (name == null) {
                // Never replace a missing 5.1 side layer with a full-screen display.
                if (DiLink51ClusterLayout.supported()) return null
                return displays.firstOrNull { display ->
                    val size = sizeOf(display)
                    DiLink4ClusterDisplay.matches(display.name, size.x, size.y)
                }
            }
            return displays.firstOrNull { it.name == name }?.takeIf {
                if (!DiLink51ClusterLayout.supported()) true else {
                    val size = sizeOf(it)
                    DiLink51ClusterLayout.plan(size.x, size.y, theme) != null
                }
            }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString {
                    val size = sizeOf(it)
                    "${it.displayId}:${it.name} ${size.x}x${size.y} flags=${it.flags} valid=${it.isValid}"
                }.orEmpty()

        fun diagnosticReport(context: Context): String = buildString {
            appendLine("clusterEnabled=${AirPlayPersistence.loadClusterMapEnabled(context)}")
            appendLine("navigationReceiverAvailable=${com.shilapi.xcertplay.hud.AutunnOutputSettings.navigationAvailable(context)}")
            appendLine("allDisplays=${describeDisplays(context)}")
            val presentations = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
            appendLine("presentationDisplayIds=${presentations.joinToString { it.displayId.toString() }}")
            val selected = findDisplay(context)
            append("selectedCluster=${selected?.let { "${it.displayId}:${it.name}" } ?: "none"}")
        }

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(
    context: Context,
    private val plan: DiLink51ClusterLayout.Plan,
    private val background: Int,
) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            fade(left, top, left + 460, top, soft, clear, left, top, left + 460, bottom)
            fade(right - 460, top, right, top, clear, soft, right - 460, top, right, bottom)
        }
        paint.shader = null
    }
}
