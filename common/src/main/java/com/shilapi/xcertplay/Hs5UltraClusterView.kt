// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot

/** Autunn-owned local vector composition. No Apple / OEM dashboard package is impersonated. */
internal class Hs5UltraClusterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state = HongqiHs5UiState()
    private var values = Hs5UltraVehicleText()
    private var dark = true

    fun update(next: HongqiHs5UiState, night: Boolean, snapshot: HongqiVehicleSnapshot, now: Long) {
        state = next
        dark = night
        values = Hs5UltraVehicleText.from(snapshot, now)
        contentDescription = listOf("车速 ${values.speed}", "挡位 ${values.gear}", values.doors,
            values.trunk, values.lights, values.climate).joinToString("，")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 1920f, height / 720f)
        if (scale <= 0) return
        canvas.save()
        canvas.scale(scale, scale)
        val background = if (dark) Color.rgb(13, 20, 28) else Color.rgb(238, 243, 247)
        val textColor = if (dark) Color.WHITE else Color.rgb(23, 35, 45)
        val accent = if (dark) Color.rgb(109, 211, 230) else Color.rgb(0, 94, 118)
        val overview = state.layoutId == HongqiHs5UiLayouts.OVERVIEW
        paint.color = background
        if (overview) {
            canvas.drawRect(0f, 0f, 320f, 720f, paint)
            canvas.drawRect(1600f, 0f, 1920f, 720f, paint)
            canvas.drawRect(320f, 0f, 1600f, 120f, paint)
            canvas.drawRect(320f, 600f, 1600f, 720f, paint)
        }
        if ("navigation" !in state.activeComponents) {
            canvas.drawRect(if (overview) 320f else 0f, if (overview) 120f else 0f,
                if (overview) 1600f else 1920f, if (overview) 600f else 720f, paint)
        }
        if (overview) {
            drawText(canvas, "Autunn", 42f, 66f, 30f, accent)
            if ("speed" in state.activeComponents) {
                drawText(canvas, "车速", 42f, 154f, 28f, textColor)
                drawText(canvas, values.speed, 42f, 304f, 120f, textColor)
            }
            if ("gear" in state.activeComponents) {
                drawText(canvas, "挡位", 42f, 427f, 28f, textColor)
                drawText(canvas, values.gear, 42f, 566f, 104f, accent)
            }
            if ("vehicleStatus" in state.activeComponents) {
                drawText(canvas, "车辆状态", 1636f, 66f, 30f, accent)
                listOf(values.doors, values.trunk, values.lights, values.climate).forEachIndexed { index, line ->
                    drawText(canvas, line, 1636f, 170f + index * 92f, 28f, textColor)
                }
            }
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (size >= 100f) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.DEFAULT
        // Qisi's valid raw speed may have five digits; preserve it without intruding on video.
        val panelRight = if (x < 320f) 320f else 1920f
        val available = (panelRight - x - 36f).coerceAtLeast(1f)
        val measured = paint.measureText(text)
        if (measured > available) paint.textSize *= available / measured
        canvas.save()
        canvas.clipRect(x, 0f, panelRight - 36f, 720f)
        canvas.drawText(text, x, y, paint)
        canvas.restore()
    }
}

internal data class Hs5UltraVehicleText(
    val speed: String = "—",
    val gear: String = "—",
    val doors: String = "车门 未知",
    val trunk: String = "尾门 未知",
    val lights: String = "灯光 未知",
    val climate: String = "空调 未知",
) {
    companion object {
        fun from(snapshot: HongqiVehicleSnapshot, now: Long): Hs5UltraVehicleText {
            fun value(field: HongqiVehicleField): Double? {
                if (snapshot.status != HongqiVehicleConnectionState.READY) return null
                val reading: HongqiVehicleReading = snapshot.readings[field] ?: return null
                if (reading.failure != null) return null
                return reading.raw?.takeIf { it.isFinite() && now - reading.sampledAtElapsedMillis in 0..2500L }
            }
            val doors = listOf(HongqiVehicleField.DRIVER_DOOR, HongqiVehicleField.PASSENGER_DOOR,
                HongqiVehicleField.REAR_LEFT_DOOR, HongqiVehicleField.REAR_RIGHT_DOOR).map(::value)
            fun switch(label: String, field: HongqiVehicleField) = when (value(field)) {
                0.0 -> "$label 关闭"
                1.0 -> "$label 开启"
                else -> "$label 未知"
            }
            return Hs5UltraVehicleText(
                speed = value(HongqiVehicleField.SPEED)?.takeIf { it >= 0 && it < 32766 && it % 1 == 0.0 }
                    ?.toInt()?.toString() ?: "—",
                gear = when (value(HongqiVehicleField.GEAR)) {
                    0.0 -> "P"; 1.0 -> "R"; 2.0 -> "N"
                    3.0, 4.0, 5.0, 6.0, 7.0, 8.0 -> "D"
                    else -> "—"
                },
                doors = when {
                    doors.any { it == 1.0 } -> "车门 未关闭"
                    doors.all { it == 0.0 } -> "车门 已关闭"
                    else -> "车门 未知"
                },
                trunk = switch("尾门", HongqiVehicleField.TRUNK),
                lights = switch("灯光", HongqiVehicleField.POSITION_LIGHT),
                climate = when (value(HongqiVehicleField.CLIMATE_POWER)) {
                    0.0, 2.0 -> "空调 关闭"
                    1.0 -> "空调 开启"
                    else -> "空调 未知"
                },
            )
        }
    }
}
