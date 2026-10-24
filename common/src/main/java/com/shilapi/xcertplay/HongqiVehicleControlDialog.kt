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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.hud.HongqiVehicleClimateSwitch
import com.shilapi.xcertplay.hud.HongqiVehicleCirculation
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleControlCommand
import com.shilapi.xcertplay.hud.HongqiVehicleControlResult
import com.shilapi.xcertplay.hud.HongqiVehicleControlStatus
import com.shilapi.xcertplay.hud.HongqiVehicleData
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleSeatLevel
import com.shilapi.xcertplay.hud.HongqiVehicleSide
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import java.io.Closeable
import java.util.Locale

/** Visible, leased vehicle controls. Only a button click submits a typed, single-use request. */
internal class HongqiVehicleControlDialog(
    private val activity: Activity,
    private val acquire: (Context) -> Closeable = HongqiVehicleData::acquire,
    private val snapshot: () -> HongqiVehicleSnapshot = HongqiVehicleData::snapshot,
    private val submit: (HongqiVehicleControlCommand, (HongqiVehicleControlResult) -> Unit) -> Closeable =
        HongqiVehicleData::submit,
) {
    private class Action(
        val button: Button,
        val command: (Double) -> HongqiVehicleControlCommand?,
    )

    private class Row(
        val field: HongqiVehicleField,
        val value: TextView,
        val valid: (Double) -> Boolean,
        val format: (Double) -> String,
        val capability: HongqiVehicleField?,
        val actions: List<Action>,
    )

    private val main = Handler(Looper.getMainLooper())
    private val application = activity.application
    private val status = TextView(activity)
    private val outcome = TextView(activity)
    private val rows = mutableListOf<Row>()
    private var lease: Closeable? = null
    private var request: Closeable? = null
    private var requestSerial = 0L
    private var pending: Long? = null
    private var registered = false
    private var closed = false
    private val dialog: AlertDialog

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(owner: Activity) { if (owner === activity) close() }
        override fun onActivityDestroyed(owner: Activity) { if (owner === activity) close() }
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
        status.tag = "hongqi_control_status"
        status.text = "正在读取车辆状态…"
        status.textSize = 17f
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        outcome.tag = "hongqi_control_result"
        outcome.text = "点击按钮调整，当前值以车辆反馈为准。"
        outcome.textSize = 14f
        outcome.accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        outcome.setPadding(0, dp(8), 0, dp(8))
        panel.addView(outcome, LinearLayout.LayoutParams(-1, -2))

        temperature(panel, "主驾温度", HongqiVehicleSide.DRIVER, HongqiVehicleField.DRIVER_TEMPERATURE)
        temperature(panel, "副驾温度", HongqiVehicleSide.PASSENGER, HongqiVehicleField.PASSENGER_TEMPERATURE)
        row(panel, "风量", HongqiVehicleField.FAN,
            valid = { it in 0.0..7.0 && it == kotlin.math.floor(it) },
            format = { if (it == 0.0) "0 档" else "${it.toInt()} 档" },
            actions = listOf(
                "降低" to { raw -> (raw.toInt() - 1).takeIf { it in 0..7 }?.let { HongqiVehicleControlCommand.Fan(it) } },
                "升高" to { raw -> (raw.toInt() + 1).takeIf { it in 0..7 }?.let { HongqiVehicleControlCommand.Fan(it) } },
            ))
        climateSwitch(panel, "空调电源", HongqiVehicleClimateSwitch.POWER, HongqiVehicleField.CLIMATE_POWER)
        climateSwitch(panel, "自动模式 AUTO", HongqiVehicleClimateSwitch.AUTO, HongqiVehicleField.CLIMATE_AUTO)
        climateSwitch(panel, "空调 A/C", HongqiVehicleClimateSwitch.COMPRESSOR, HongqiVehicleField.CLIMATE_COMPRESSOR)
        climateSwitch(panel, "温度同步", HongqiVehicleClimateSwitch.SYNC, HongqiVehicleField.CLIMATE_SYNC)
        row(panel, "空气循环", HongqiVehicleField.CLIMATE_CIRCULATION,
            valid = { it == 1.0 || it == 2.0 },
            format = { if (it == 1.0) "外循环" else "内循环" },
            actions = listOf(
                "外循环" to { HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.FRESH_AIR) },
                "内循环" to { HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.RECIRCULATE) },
            ))
        climateSwitch(panel, "前风挡除霜", HongqiVehicleClimateSwitch.FRONT_DEFROST, HongqiVehicleField.CLIMATE_FRONT_DEFROST)
        seat(panel, "主驾座椅加热", HongqiVehicleSide.DRIVER, heat = true)
        seat(panel, "副驾座椅加热", HongqiVehicleSide.PASSENGER, heat = true)
        seat(panel, "主驾座椅通风", HongqiVehicleSide.DRIVER, heat = false)
        seat(panel, "副驾座椅通风", HongqiVehicleSide.PASSENGER, heat = false)

        dialog = LandscapeSettingsLayout.dialogBuilder(activity)
            .setTitle("空调与座椅")
            .setView(panel)
            .setNeutralButton("停止等待", null)
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
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = false
                setOnClickListener { cancelPending() }
            }
            lease = acquire(activity.applicationContext)
            refresh.run()
        } catch (error: RuntimeException) {
            status.text = "暂时无法读取车辆状态，请稍后重新打开。"
            Log.w(TAG, "Vehicle controls unavailable: ${error.javaClass.simpleName}")
            if (!dialog.isShowing) close()
        }
        return dialog
    }

    private fun temperature(panel: LinearLayout, label: String, side: HongqiVehicleSide, field: HongqiVehicleField) {
        fun target(raw: Double, delta: Double): HongqiVehicleControlCommand? {
            val value = (raw + delta).coerceIn(
                HongqiVehicleControlCommand.MIN_CELSIUS.toDouble(),
                HongqiVehicleControlCommand.MAX_CELSIUS.toDouble(),
            ).toFloat()
            if (delta < 0 && value >= raw || delta > 0 && value <= raw) return null
            return HongqiVehicleControlCommand.Temperature(side, value)
        }
        row(panel, label, field,
            valid = { it in 15.0..35.0 && it * 2 == kotlin.math.floor(it * 2) },
            format = { String.format(Locale.ROOT, "%.1f °C", it) },
            actions = listOf("降低" to { target(it, -0.5) }, "升高" to { target(it, 0.5) }))
    }

    private fun climateSwitch(panel: LinearLayout, label: String, setting: HongqiVehicleClimateSwitch, field: HongqiVehicleField) {
        row(panel, label, field,
            valid = { it in 0.0..2.0 && it == kotlin.math.floor(it) },
            format = {
                val on = if (setting == HongqiVehicleClimateSwitch.COMPRESSOR) it == 1.0 || it == 2.0 else it == 1.0
                if (on) "开启" else "关闭"
            },
            actions = listOf(
                "关闭" to { HongqiVehicleControlCommand.Switch(setting, false) },
                "开启" to { HongqiVehicleControlCommand.Switch(setting, true) },
            ))
    }

    private fun seat(panel: LinearLayout, label: String, side: HongqiVehicleSide, heat: Boolean) {
        val driver = side == HongqiVehicleSide.DRIVER
        val field = if (heat) {
            if (driver) HongqiVehicleField.DRIVER_HEAT else HongqiVehicleField.PASSENGER_HEAT
        } else if (driver) HongqiVehicleField.DRIVER_VENT else HongqiVehicleField.PASSENGER_VENT
        val names = listOf("关闭", "低", "中", "高")
        row(panel, label, field,
            valid = { it in 0.0..3.0 && it == kotlin.math.floor(it) },
            format = { names[it.toInt()] },
            capability = if (heat) HongqiVehicleField.FRONT_HEAT_SUPPORTED else HongqiVehicleField.FRONT_VENT_SUPPORTED,
            actions = HongqiVehicleSeatLevel.entries.mapIndexed { index, level ->
                names[index] to { _: Double ->
                    if (heat) HongqiVehicleControlCommand.SeatHeat(side, level)
                    else HongqiVehicleControlCommand.SeatVent(side, level)
                }
            })
    }

    private fun row(
        panel: LinearLayout,
        label: String,
        field: HongqiVehicleField,
        valid: (Double) -> Boolean,
        format: (Double) -> String,
        capability: HongqiVehicleField? = null,
        actions: List<Pair<String, (Double) -> HongqiVehicleControlCommand?>>,
    ) {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        header.addView(TextView(activity).apply { text = label; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(activity).apply {
            tag = "hongqi_control_value_${field.name}"
            text = "当前：未知"
            gravity = Gravity.END
        }
        header.addView(value, LinearLayout.LayoutParams(-2, -2))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val choices = actions.mapIndexed { index, (title, factory) ->
            val button = Button(activity).apply {
                tag = "hongqi_control_${field.name}_$index"
                text = title
                contentDescription = "$label，$title"
                isAllCaps = false
                isEnabled = false
                minHeight = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            buttons.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
            Action(button, factory)
        }
        val row = Row(field, value, valid, format, capability, choices)
        for (action in choices) action.button.setOnClickListener { dispatch(row, action) }
        rows += row
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2))
    }

    private fun stateReady(current: HongqiVehicleSnapshot, now: Long): Boolean =
        current.status == HongqiVehicleConnectionState.READY &&
            now - current.capturedAtElapsedMillis in 0..HongqiVehicleSnapshot.MAX_AGE_MILLIS

    private fun raw(current: HongqiVehicleSnapshot, field: HongqiVehicleField, now: Long): Double? {
        if (!stateReady(current, now)) return null
        val reading = current.readings[field] ?: return null
        if (reading.failure != null || now - reading.sampledAtElapsedMillis !in 0..HongqiVehicleSnapshot.MAX_AGE_MILLIS) return null
        return reading.raw?.takeIf { it.isFinite() }
    }

    private fun rowValue(current: HongqiVehicleSnapshot, row: Row, now: Long): Double? =
        raw(current, row.field, now)?.takeIf(row.valid)

    private fun render() {
        val current = snapshot()
        val now = SystemClock.elapsedRealtime()
        status.text = when (current.status) {
            HongqiVehicleConnectionState.READY -> if (stateReady(current, now)) "车辆状态已更新" else "车辆数据已过期，等待更新"
            HongqiVehicleConnectionState.STOPPED, HongqiVehicleConnectionState.CONNECTING -> "正在读取车辆状态…"
            HongqiVehicleConnectionState.UNAVAILABLE -> "暂时无法读取车辆状态"
            HongqiVehicleConnectionState.UNSUPPORTED -> "当前车机暂不支持此功能"
        }
        for (row in rows) {
            val value = rowValue(current, row, now)
            val supported = row.capability?.let { raw(current, it, now) }
            row.value.text = when {
                row.capability != null && supported == 0.0 -> "当前配置不支持"
                row.capability != null && supported != 1.0 -> "配置未知"
                value == null -> "当前：未知"
                else -> "当前：${row.format(value)}"
            }
            for (action in row.actions) {
                action.button.isEnabled = lease != null && pending == null && value != null &&
                    (row.capability == null || supported == 1.0) && action.command(value) != null
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = pending != null
    }

    private fun dispatch(row: Row, action: Action) {
        if (closed || pending != null || lease == null) return
        val current = snapshot()
        val now = SystemClock.elapsedRealtime()
        val value = rowValue(current, row, now) ?: run { render(); return }
        if (row.capability != null && raw(current, row.capability, now) != 1.0) { render(); return }
        val command = action.command(value) ?: return
        val serial = ++requestSerial
        pending = serial
        outcome.text = "正在提交，等待车辆反馈…"
        render()
        try {
            val owned = submit(command) { result ->
                val deliver = Runnable { acceptResult(serial, command, result) }
                if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else main.post(deliver)
            }
            // submit may synchronously finish. Never retain its handle after that completion.
            if (!closed && pending == serial) request = owned else release(owned)
        } catch (error: RuntimeException) {
            if (pending == serial) {
                pending = null
                outcome.text = "请求未完成，结果未确认。"
                render()
            }
            Log.w(TAG, "Vehicle request failed: ${error.javaClass.simpleName}")
        }
    }

    private fun acceptResult(serial: Long, command: HongqiVehicleControlCommand, result: HongqiVehicleControlResult) {
        if (closed || pending != serial || result.command != command) return
        if (result.status == HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED) {
            outcome.text = "请求已提交，等待车辆反馈…"
            return
        }
        pending = null
        val owned = request
        request = null
        release(owned)
        outcome.text = when (result.status) {
            HongqiVehicleControlStatus.CONFIRMED -> "反馈已确认"
            HongqiVehicleControlStatus.TIMED_OUT -> "等待超时，结果未确认。请查看车辆当前状态。"
            HongqiVehicleControlStatus.CANCELLED -> "已停止等待，结果未确认。已发出的请求可能仍会生效。"
            HongqiVehicleControlStatus.REJECTED -> "请求未被接受，结果未确认。"
            HongqiVehicleControlStatus.UNAVAILABLE -> "车辆连接或数据不可用，结果未确认。"
            HongqiVehicleControlStatus.UNSUPPORTED -> "当前车机或车辆配置不支持此操作。"
            HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED -> error("handled above")
        }
        render()
    }

    private fun cancelPending() {
        if (pending == null) return
        pending = null
        val owned = request
        request = null
        release(owned)
        outcome.text = "已停止等待，结果未确认。已发出的请求可能仍会生效。"
        render()
    }

    private fun release(owned: Closeable?) {
        runCatching { owned?.close() }.onFailure {
            Log.w(TAG, "Vehicle subscription cleanup failed: ${it.javaClass.simpleName}")
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        pending = null
        main.removeCallbacksAndMessages(null)
        val ownedRequest = request
        request = null
        release(ownedRequest)
        val ownedLease = lease
        lease = null
        release(ownedLease)
        if (registered) {
            registered = false
            application.unregisterActivityLifecycleCallbacks(lifecycle)
        }
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "HongqiVehicleControls"
        @JvmStatic fun show(activity: Activity): AlertDialog = HongqiVehicleControlDialog(activity).show()
    }
}
