package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** Geometry measured against the stock full-map and right-hand side-card layers on this firmware. */
internal object DiLink51ClusterLayout {
    const val FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
    const val BASE = "fission_bg_XDJAScreenProjection"
    const val FULL = "shared_${BASE}_0"
    const val SIDE = "shared_${BASE}_1"
    private const val PREFS = "diplay_cluster_layout"

    enum class Theme(val label: String) {
        SCENARIO("Scenario · side map"), MAP("Map · full map"), SIMPLE("Simple · side map")
    }
    enum class Contrast(val label: String) {
        DEFAULT("Theme default"), LIGHT("Light background · dark instruments"), DARK("Dark background · light instruments")
    }
    data class Plan(val left: Int, val top: Int, val width: Int, val height: Int, val fullMap: Boolean)
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    /** Ask the iPhone for the visible viewport; every stream pixel is displayed. */
    fun streamConfig(
        theme: Theme = Theme.SCENARIO,
        content: CarPlayClusterDisplay.Content = CarPlayClusterDisplay.Content.MAP,
    ): AirPlayDisplayConfig {
        val viewport = checkNotNull(plan(STREAM_WIDTH, STREAM_HEIGHT, theme))
        val panel = CarPlayClusterDisplay.config(STREAM_WIDTH, STREAM_HEIGHT, scalePercent = 100)
        return CarPlayClusterDisplay.config(
            viewport.width, viewport.height, scalePercent = 100, content = content,
        ).copy(
            safeArea = null,
            widthPhysicalMm = checkNotNull(panel.widthPhysicalMm) * viewport.width / STREAM_WIDTH,
            heightPhysicalMm = checkNotNull(panel.heightPhysicalMm) * viewport.height / STREAM_HEIGHT,
        )
    }
    fun supported(fingerprint: String = Build.FINGERPRINT): Boolean = fingerprint == FINGERPRINT
    fun theme(context: Context): Theme = Theme.entries.firstOrNull {
        it.name == context.getSharedPreferences(PREFS, 0).getString("theme", null)
    } ?: Theme.SCENARIO
    fun saveTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS, 0).edit().putString("theme", theme.name).apply()
    }
    fun automatic(context: Context): Boolean = supported() && context.getSharedPreferences(PREFS, 0).getBoolean("automatic", false)
    fun saveAutomatic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("automatic", enabled).apply()
    }
    fun contrast(context: Context): Contrast = Contrast.entries.firstOrNull {
        it.name == context.getSharedPreferences(PREFS, 0).getString("contrast", null)
    } ?: Contrast.DEFAULT
    fun saveContrast(context: Context, contrast: Contrast) {
        context.getSharedPreferences(PREFS, 0).edit().putString("contrast", contrast.name).apply()
    }
    fun dark(context: Context, theme: Theme = theme(context)): Boolean = when (contrast(context)) {
        Contrast.DEFAULT -> theme == Theme.SIMPLE
        Contrast.LIGHT -> false
        Contrast.DARK -> true
    }

    fun displayName(names: List<String>, fingerprint: String, theme: Theme): String? {
        if (supported(fingerprint)) {
            // Never substitute the full layer for a missing side layer: that hides Scenario/Simple.
            return (if (theme == Theme.MAP) FULL else SIDE).takeIf { it in names }
        }
        // Preserve PR #5's selection order on DiLink 5 and all unverified firmware.
        return names.firstOrNull { it == BASE }
            ?: names.firstOrNull { it.contains(BASE) && it.endsWith("_0") }
            ?: names.firstOrNull { it.contains(BASE) }
    }

    fun plan(width: Int, height: Int, theme: Theme): Plan? {
        // The measured side-map bounds are x=1320..1920 on the 1920x720 cluster.
        if (width != 1920 || height != 720) return null
        return if (theme == Theme.MAP) {
            // Protect speed/status and gear/range; CarPlay renders for this viewport.
            Plan(0, 144, width, 480, true)
        } else Plan(1320, 0, 600, height, false)
    }
}
