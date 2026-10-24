// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleData
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import java.io.Closeable

/** Owns a visible diagnostic subscription; rendering reads the provider's cached snapshot only. */
internal class HongqiVehicleDataDialog(
    private val activity: Activity,
    private val acquire: (Context) -> Closeable = HongqiVehicleData::acquire,
    private val snapshot: () -> HongqiVehicleSnapshot = HongqiVehicleData::snapshot,
) {
    private val main = Handler(Looper.getMainLooper())
    private val application = activity.application
    private val status = TextView(activity)
    private val values = linkedMapOf<HongqiVehicleField, TextView>()
    private var lease: Closeable? = null
    private var registered = false
    private var closed = false
    private val dialog: AlertDialog

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(owner: Activity) {
            if (owner === activity) close()
        }
        override fun onActivityDestroyed(owner: Activity) {
            if (owner === activity) close()
        }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityResumed(owner: Activity) = Unit
        override fun onActivityPaused(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (closed || !dialog.isShowing) return
            render()
            main.postDelayed(this, 1_000)
        }
    }

    init {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        status.tag = "hongqi_vehicle_status"
        status.text = "正在读取车辆状态…"
        status.textSize = 17f
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(activity).apply {
            text = "每秒刷新，只读查看。以下数值为原始值，车速、挡位和等级暂不换算；未知表示当前没有有效数据。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        for ((field, label) in LABELS) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(42)
            }
            row.addView(TextView(activity).apply {
                text = label
                textSize = 16f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = TextView(activity).apply {
                tag = "hongqi_vehicle_${field.name}"
                text = "未知"
                textSize = 16f
                gravity = Gravity.END
                setPadding(dp(16), dp(6), 0, dp(6))
            }
            values[field] = value
            row.addView(value, LinearLayout.LayoutParams(0, -2, 1.4f))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = LandscapeSettingsLayout.dialogBuilder(activity)
            .setTitle("车辆状态（只读）")
            .setView(panel)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener { close() }
    }

    fun show(): AlertDialog {
        if (closed || dialog.isShowing || activity.isFinishing || activity.isDestroyed) return dialog
        application.registerActivityLifecycleCallbacks(lifecycle)
        registered = true
        try {
            LandscapeSettingsLayout.showDialog(dialog)
            lease = acquire(activity.applicationContext)
            refresh.run()
        } catch (error: RuntimeException) {
            status.text = "暂时无法读取车辆状态，请稍后重新打开。"
            Log.w(TAG, "Vehicle data subscription unavailable: ${error.javaClass.simpleName}")
            if (!dialog.isShowing) close()
        }
        return dialog
    }

    private fun render() {
        val current = snapshot()
        val now = SystemClock.elapsedRealtime()
        val ready = current.status == HongqiVehicleConnectionState.READY &&
            now - current.capturedAtElapsedMillis in 0..HongqiVehicleSnapshot.MAX_AGE_MILLIS
        status.text = when (current.status) {
            HongqiVehicleConnectionState.STOPPED -> "尚未读取车辆状态"
            HongqiVehicleConnectionState.CONNECTING -> "正在读取车辆状态…"
            HongqiVehicleConnectionState.READY -> if (ready) "车辆状态已更新" else "车辆数据已过期，等待更新"
            HongqiVehicleConnectionState.UNAVAILABLE -> "暂时无法读取车辆状态"
            HongqiVehicleConnectionState.UNSUPPORTED -> "当前车机暂不支持此功能"
        }
        for ((field, view) in values) {
            val reading = current.readings[field]
            val raw = reading?.takeIf {
                ready && it.failure == null && now - it.sampledAtElapsedMillis in 0..HongqiVehicleSnapshot.MAX_AGE_MILLIS
            }?.raw?.takeIf { it.isFinite() }
            view.text = if (reading == null || raw == null) "未知" else {
                val age = (now - reading.sampledAtElapsedMillis).coerceAtLeast(0) / 1_000
                "原始值 ${raw.toString().removeSuffix(".0")} · ${age} 秒前"
            }
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        val ownedLease = lease
        lease = null
        runCatching { ownedLease?.close() }.onFailure {
            Log.w(TAG, "Vehicle data subscription cleanup failed: ${it.javaClass.simpleName}")
        }
        if (registered) {
            registered = false
            application.unregisterActivityLifecycleCallbacks(lifecycle)
        }
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "HongqiVehicleDialog"
        private val LABELS = linkedMapOf(
            HongqiVehicleField.GEAR to "挡位",
            HongqiVehicleField.SPEED to "车速",
            HongqiVehicleField.DRIVER_DOOR to "主驾车门",
            HongqiVehicleField.RANGE to "剩余续航",
            HongqiVehicleField.POSITION_LIGHT to "位置灯",
            HongqiVehicleField.CLIMATE_POWER to "空调电源",
            HongqiVehicleField.DRIVER_TEMPERATURE to "主驾温度",
            HongqiVehicleField.PASSENGER_TEMPERATURE to "副驾温度",
            HongqiVehicleField.FAN to "风量",
            HongqiVehicleField.AMBIENT_TEMPERATURE to "环境温度",
            HongqiVehicleField.DRIVER_HEAT to "主驾座椅加热",
            HongqiVehicleField.PASSENGER_HEAT to "副驾座椅加热",
            HongqiVehicleField.DRIVER_VENT to "主驾座椅通风",
            HongqiVehicleField.PASSENGER_VENT to "副驾座椅通风",
            HongqiVehicleField.FRONT_HEAT_SUPPORTED to "前排加热配置",
            HongqiVehicleField.FRONT_VENT_SUPPORTED to "前排通风配置",
            HongqiVehicleField.PASSENGER_DOOR to "副驾车门",
            HongqiVehicleField.REAR_LEFT_DOOR to "左后车门",
            HongqiVehicleField.REAR_RIGHT_DOOR to "右后车门",
            HongqiVehicleField.TRUNK to "尾门",
            HongqiVehicleField.FUEL_REMAINING to "剩余燃油",
            HongqiVehicleField.TOTAL_MILEAGE to "累计里程",
            HongqiVehicleField.FRONT_LEFT_TIRE_PRESSURE to "左前胎压",
            HongqiVehicleField.FRONT_RIGHT_TIRE_PRESSURE to "右前胎压",
            HongqiVehicleField.REAR_LEFT_TIRE_PRESSURE to "左后胎压",
            HongqiVehicleField.REAR_RIGHT_TIRE_PRESSURE to "右后胎压",
            HongqiVehicleField.CLIMATE_AUTO to "空调 AUTO",
            HongqiVehicleField.CLIMATE_SYNC to "温度同步",
            HongqiVehicleField.CLIMATE_COMPRESSOR to "空调 A/C",
            HongqiVehicleField.CLIMATE_CIRCULATION to "空气循环",
            HongqiVehicleField.CLIMATE_FRONT_DEFROST to "前风挡除霜",
        )

        @JvmStatic fun show(activity: Activity): AlertDialog = HongqiVehicleDataDialog(activity).show()
    }
}
