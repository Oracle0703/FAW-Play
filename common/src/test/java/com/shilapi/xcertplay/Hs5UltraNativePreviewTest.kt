package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Off-car visual QA. Every vehicle value and the video placeholder are explicitly synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Hs5UltraNativePreviewTest {
    @Test fun renderDarkAndLightOverviewFromAnExplicitSyntheticSnapshot() {
        for (dark in listOf(true, false)) {
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val background = if (dark) Color.rgb(26, 37, 48) else Color.rgb(216, 228, 236)
            canvas.drawColor(background)
            renderer(snapshot(0.0), dark).draw(canvas)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.rgb(180, 207, 217) else Color.rgb(39, 76, 93)
                textSize = 30f
            }
            canvas.drawText("PREVIEW · SYNTHETIC DATA · NOT A VEHICLE CAPTURE", 356f, 74f, paint)
            paint.textSize = 38f
            canvas.drawText("CarPlay 导航视频区域", 700f, 324f, paint)
            paint.textSize = 27f
            canvas.drawText("预览占位 · 合成数据 · 非实车", 730f, 384f, paint)
            paint.textSize = 24f
            canvas.drawText("Fixture: P / 0, driver door open, trunk closed, 25 °C / 24 °C", 356f, 666f, paint)
            val file = File(if (dark) "/tmp/autunn-hs5-ultra-dark.png" else "/tmp/autunn-hs5-ultra-light.png")
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.isFile && file.length() > 10_000)
            assertEquals(1920, bitmap.width)
            assertEquals(720, bitmap.height)
        }
    }

    @Test fun theLongestLegalRawSpeedCannotPaintAcrossTheVideoBoundary() {
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        renderer(snapshot(32765.0), true).draw(Canvas(bitmap))
        for (y in 180..320) for (x in 320..400) {
            assertEquals("Speed overflow at ($x,$y)", Color.TRANSPARENT, bitmap.getPixel(x, y))
        }
    }

    private fun renderer(snapshot: HongqiVehicleSnapshot, dark: Boolean): Hs5UltraClusterView =
        Hs5UltraClusterView(RuntimeEnvironment.getApplication()).apply {
            val layout = HongqiHs5UiLayouts.ALL.first { it.id == HongqiHs5UiLayouts.OVERVIEW }
            update(HongqiHs5UiState(layoutId = layout.id, activeComponents = layout.components,
                appearanceMode = if (dark) "dark" else "light"), dark, snapshot, 1000)
            measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1920, 720)
        }

    private fun snapshot(speed: Double) = HongqiVehicleSnapshot(
        HongqiVehicleConnectionState.READY,
        mapOf(
            HongqiVehicleField.SPEED to speed,
            HongqiVehicleField.GEAR to 0.0,
            HongqiVehicleField.DRIVER_DOOR to 1.0,
            HongqiVehicleField.PASSENGER_DOOR to 0.0,
            HongqiVehicleField.REAR_LEFT_DOOR to 0.0,
            HongqiVehicleField.REAR_RIGHT_DOOR to 0.0,
            HongqiVehicleField.TRUNK to 0.0,
            HongqiVehicleField.POSITION_LIGHT to 1.0,
            HongqiVehicleField.CLIMATE_POWER to 1.0,
            HongqiVehicleField.DRIVER_TEMPERATURE to 25.0,
            HongqiVehicleField.PASSENGER_TEMPERATURE to 24.0,
        ).mapValues { (_, value) -> HongqiVehicleReading(value, 1000) },
        capturedAtElapsedMillis = 1000,
    )
}
