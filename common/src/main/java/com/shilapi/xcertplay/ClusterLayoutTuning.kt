package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.shared.BuildConfig
import kotlin.math.roundToInt

/** A separate preference file keeps layout calibration out of connection and vehicle state. */
internal object ClusterLayoutTuning {
    const val PREFS = "faw_cluster_layout_tuning"
    const val WIDTH = 1920
    const val HEIGHT = 720
    const val MIN_SCALE = 50
    const val MAX_SCALE = 150
    const val MIN_BOX_WIDTH = 80
    const val MIN_BOX_HEIGHT = 40
    // TUNE1's separate duration capsule is not the route information bar.
    enum class Target(val wire: String, val preferenceKey: String) {
        TBT("tbt", "tbt"), ETE("ete", "route_info")
    }
    data class Placement(val x: Int, val y: Int, val scale: Int, val width: Int? = null, val height: Int? = null)

    fun active(): Boolean = BuildConfig.AUTUNN_CLUSTER_TUNING_ENABLED && HongqiNavigation.clusterDefault()

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaults(target: Target): Placement = when (target) {
        Target.TBT -> Placement(1396, 81, 69, 457, 164)
        Target.ETE -> Placement(90, 622, 80, 450, 45)
    }

    private fun originalDefaults(target: Target): Placement = when (target) {
        Target.TBT -> Placement(1154, 160, 100)
        Target.ETE -> Placement(1154, 328, 100)
    }

    fun custom(context: Context, target: Target): Placement? {
        val values = preferences(context).all
        val key = target.preferenceKey
        val x = values["${key}_x"] as? Int ?: return null
        val y = values["${key}_y"] as? Int ?: return null
        val scale = values["${key}_scale"] as? Int ?: return null
        val width = values["${key}_width"]
        val height = values["${key}_height"]
        if (width != null && width !is Int || height != null && height !is Int) return null
        return Placement(x, y, scale, width as? Int, height as? Int).takeIf(::valid)
    }

    private fun original(context: Context, target: Target): Boolean =
        preferences(context).all["${target.preferenceKey}_original"] == true

    fun load(context: Context, target: Target): Placement = custom(context, target) ?:
        if (original(context, target)) originalDefaults(target) else defaults(target)

    /** The release layout is independent of whether its ADB tuning receiver is enabled. */
    fun forDrawing(context: Context, target: Target): Placement? = custom(context, target) ?:
        if (original(context, target)) null else defaults(target)

    fun valid(value: Placement): Boolean = value.x in 0 until WIDTH && value.y in 0 until HEIGHT &&
        value.scale in MIN_SCALE..MAX_SCALE &&
        (value.width == null || value.width in MIN_BOX_WIDTH..WIDTH) &&
        (value.height == null || value.height in MIN_BOX_HEIGHT..HEIGHT)

    fun referenceSize(target: Target, value: Placement): Pair<Int, Int> {
        val baseWidth = 666
        val baseHeight = if (target == Target.TBT) 160 else 80
        return (value.width ?: (baseWidth * value.scale / 100f).roundToInt()) to
            (value.height ?: (baseHeight * value.scale / 100f).roundToInt())
    }

    fun save(context: Context, target: Target, value: Placement) {
        require(valid(value)) { "位置或尺寸超出范围" }
        val key = target.preferenceKey
        val edit = preferences(context).edit()
            .remove("${key}_original")
            .putInt("${key}_x", value.x)
            .putInt("${key}_y", value.y)
            .putInt("${key}_scale", value.scale)
        if (value.width == null) edit.remove("${key}_width")
        else edit.putInt("${key}_width", value.width)
        if (value.height == null) edit.remove("${key}_height")
        else edit.putInt("${key}_height", value.height)
        check(edit.commit()) { "无法保存仪表布局" }
    }

    fun reset(context: Context, target: Target?) {
        val edit = preferences(context).edit()
        for (item in target?.let(::listOf) ?: Target.entries) {
            for (suffix in listOf("x", "y", "scale", "width", "height", "original")) edit.remove("${item.preferenceKey}_$suffix")
        }
        check(edit.commit()) { "无法恢复仪表布局" }
    }

    fun restoreNavi29(context: Context) {
        val edit = preferences(context).edit().clear()
        Target.entries.forEach { edit.putBoolean("${it.preferenceKey}_original", true) }
        check(edit.commit()) {
            "无法恢复 NAVI29 布局"
        }
    }

    /** Positions use the projection canvas, never the iPhone screenshot or panel specification. */
    fun rect(value: Placement, width: Int, height: Int, baseWidth: Int, baseHeight: Int): ClusterTurnCardOverlay.CardRect {
        val w = (value.width?.let { it * width.toFloat() / WIDTH }
            ?: (baseWidth * value.scale / 100f)).roundToInt().coerceIn(1, width)
        val h = (value.height?.let { it * height.toFloat() / HEIGHT }
            ?: (baseHeight * value.scale / 100f)).roundToInt().coerceIn(1, height)
        val x = (value.x * width.toDouble() / WIDTH).roundToInt().coerceIn(0, width - w)
        val y = (value.y * height.toDouble() / HEIGHT).roundToInt().coerceIn(0, height - h)
        return ClusterTurnCardOverlay.CardRect(x, y, w, h)
    }
}
