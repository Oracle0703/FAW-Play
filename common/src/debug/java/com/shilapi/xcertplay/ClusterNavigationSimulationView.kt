package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content

/** Synthetic artwork only; no AMap assets or undocumented vehicle messages are used. */
internal class ClusterNavigationSimulationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val junctionImages = List(3) { createJunctionImage(it) }
    private var content = Content.INSTRUMENTS
    private var scene = 0
    private var distanceMeters = 320

    fun show(content: Content, scene: Int, distanceMeters: Int) {
        this.content = content
        this.scene = scene.coerceIn(0, 2)
        this.distanceMeters = distanceMeters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 1920f, height / 720f)
        drawMap(canvas)
        if (content != Content.MAP_WITH_CUSTOM_CARD) {
            if (content == Content.TURN_CARD) {
                paint.color = Color.rgb(17, 25, 34)
                canvas.drawRect(0f, 178f, 1920f, 720f, paint)
            }
            drawNativeExample(canvas)
        }
        drawVehicleOverlay(canvas)
        canvas.restore()
    }

    private fun drawMap(canvas: Canvas) {
        canvas.drawColor(Color.rgb(23, 38, 47))
        paint.color = Color.rgb(50, 69, 77)
        paint.strokeWidth = 30f
        paint.style = Paint.Style.STROKE
        for (y in 235..720 step 145) canvas.drawLine(0f, y.toFloat(), 1920f, y.toFloat(), paint)
        for (x in 160..1920 step 270) canvas.drawLine(x.toFloat(), 178f, x.toFloat(), 720f, paint)
        path.reset()
        path.moveTo(810f, 680f)
        path.lineTo(810f, 420f)
        path.quadTo(810f, 365f, 865f, 365f)
        path.lineTo(1260f, 365f)
        paint.color = Color.rgb(55, 144, 244)
        paint.strokeWidth = 20f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(810f, 470f)
        path.lineTo(788f, 522f)
        path.lineTo(810f, 510f)
        path.lineTo(832f, 522f)
        path.close()
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        label(canvas, "示例道路", 560f, 344f, 24f, Color.rgb(177, 193, 204))
        label(canvas, "模拟地图 · 非高德实时输出", 630f, 586f, 25f, Color.rgb(177, 193, 204))
    }

    private fun drawNativeExample(canvas: Canvas) {
        val cardOnly = content == Content.TURN_CARD
        val imageLeft = if (cardOnly) 650f else 1125f
        val contentTop = 215f
        val infoTop = 622f
        val infoHeight = 53f
        val laneHeight = 93f
        val laneTop = infoTop + infoHeight - laneHeight
        val imageTop = contentTop
        val imageWidth = 720f
        val imageHeight = laneTop - imageTop - 16f
        panel(canvas, imageLeft, imageTop, imageWidth, imageHeight)
        val junctionTop = imageTop + 100f
        val junctionHeight = imageHeight - 100f
        canvas.save()
        canvas.clipRect(imageLeft + 2f, junctionTop, imageLeft + imageWidth - 2f, imageTop + imageHeight - 2f)
        // The upper part of this card contains TBT, clear of the stock speed display on the left.
        val junction = junctionImages[scene]
        val imageScale = maxOf((imageWidth - 4f) / junction.width, (junctionHeight - 2f) / junction.height)
        val scaledWidth = junction.width * imageScale
        val scaledHeight = junction.height * imageScale
        val bitmapLeft = imageLeft + (imageWidth - scaledWidth) / 2f
        val bitmapTop = junctionTop + (junctionHeight - scaledHeight) / 2f
        canvas.drawBitmap(junction, null,
            RectF(bitmapLeft, bitmapTop, bitmapLeft + scaledWidth, bitmapTop + scaledHeight), paint)
        canvas.restore()
        val title = listOf("路口放大图", "立交出口放大图", "靠右驶入匝道")[scene]
        label(canvas, "$title · 模拟图片", imageLeft + 24f, junctionTop + 32f, 23f, Color.WHITE)
        arrow(canvas, imageLeft + 28f, imageTop + 17f, 42f, Color.WHITE, turn = true)
        label(canvas, "$distanceMeters 米", imageLeft + 114f, imageTop + 48f, 43f, Color.WHITE, bold = true)
        label(canvas, listOf("右转进入示例道路", "从右侧驶出高速", "沿右侧匝道行驶")[scene],
            imageLeft + 114f, imageTop + 83f, 24f, Color.rgb(188, 203, 216))
        panel(canvas, imageLeft, laneTop, imageWidth, laneHeight)
        label(canvas, "车道", imageLeft + 23f, laneTop + 57f, 24f, Color.rgb(188, 203, 216))
        for (lane in 0..3) {
            val selected = lane >= 2
            arrow(canvas, imageLeft + 170f + lane * 118f, laneTop + 15f, 36f,
                if (selected) Color.rgb(99, 210, 255) else Color.rgb(111, 129, 146), turn = selected)
        }
        panel(canvas, 80f, infoTop, 530f, infoHeight)
        label(canvas, "14:50 到达  ·  47 分  ·  25.0 公里", 100f, infoTop + 35f,
            26f, Color.rgb(231, 239, 247))
    }

    private fun drawVehicleOverlay(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(73, 76, 111)
        canvas.drawRoundRect(80f, 215f, 535f, 405f, 12f, 12f, paint)
        label(canvas, "0", 258f, 352f, 103f, Color.WHITE)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawRoundRect(412f, 259f, 508f, 309f, 8f, 8f, paint)
        label(canvas, "P", 447f, 298f, 37f, Color.WHITE)
        label(canvas, "km/h", 414f, 356f, 27f, Color.WHITE)
        label(canvas, "原车车速区域（模拟预留）", 80f, 442f, 24f, Color.rgb(177, 193, 204))
        paint.color = Color.rgb(179, 195, 207)
        canvas.drawRect(207f, 691f, 438f, 698f, paint)
        canvas.drawRect(1515f, 691f, 1746f, 698f, paint)
        label(canvas, "水温", 122f, 713f, 20f, Color.rgb(179, 195, 207))
        label(canvas, "130       90       50", 208f, 719f, 19f, Color.rgb(179, 195, 207))
        label(canvas, "0        1/2        1", 1515f, 719f, 19f, Color.rgb(179, 195, 207))
        label(canvas, "油量", 1773f, 713f, 20f, Color.rgb(179, 195, 207))
    }

    private fun panel(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(14, 22, 32)
        canvas.drawRoundRect(x, y, x + w, y + h, 9f, 9f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(68, 86, 104)
        canvas.drawRoundRect(x, y, x + w, y + h, 9f, 9f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.isFakeBoldText = bold
        canvas.drawText(text, x, y, paint)
        paint.isFakeBoldText = false
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, turn: Boolean) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .19f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(x + size * .25f, y + size * 1.55f)
        if (turn) {
            path.lineTo(x + size * .25f, y + size * .5f)
            path.quadTo(x + size * .25f, y + size * .25f, x + size * .6f, y + size * .25f)
            path.lineTo(x + size, y + size * .25f)
            path.moveTo(x + size * .75f, y)
            path.lineTo(x + size, y + size * .25f)
            path.lineTo(x + size * .75f, y + size * .5f)
        } else {
            path.lineTo(x + size * .25f, y)
            path.moveTo(x, y + size * .3f)
            path.lineTo(x + size * .25f, y)
            path.lineTo(x + size * .5f, y + size * .3f)
        }
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun createJunctionImage(scene: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(39, 65, 64))
        paint.color = Color.rgb(53, 81, 74)
        for (x in 0..720 step 105) canvas.drawRect(x.toFloat(), 90f, x + 70f, 320f, paint)
        val road = Path().apply {
            moveTo(230f, 380f)
            lineTo(313f, 64f)
            lineTo(399f, 64f)
            lineTo(512f, 380f)
            close()
        }
        paint.color = Color.rgb(62, 72, 80)
        canvas.drawPath(road, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 92f
        paint.color = Color.rgb(72, 82, 91)
        val ramp = Path().apply {
            moveTo(393f, 370f)
            if (scene == 0) {
                lineTo(373f, 188f)
                quadTo(370f, 165f, 425f, 165f)
                lineTo(740f, 165f)
            } else {
                quadTo(393f, 235f, 500f, 184f)
                quadTo(580f, 153f, 745f, 120f)
            }
        }
        canvas.drawPath(ramp, paint)
        paint.color = Color.rgb(212, 222, 223)
        paint.strokeWidth = 3f
        for (lane in 0..2) {
            val x = 300f + lane * 67f
            canvas.drawLine(x, 360f, 336f + lane * 21f, 70f, paint)
        }
        paint.color = Color.rgb(115, 231, 251)
        paint.strokeWidth = 24f
        canvas.drawPath(ramp, paint)
        paint.color = Color.rgb(242, 250, 255)
        paint.strokeWidth = 4f
        canvas.drawPath(ramp, paint)
        paint.style = Paint.Style.FILL
        val tip = Path().apply {
            moveTo(682f, if (scene == 0) 165f else 130f)
            lineTo(642f, if (scene == 0) 139f else 109f)
            lineTo(644f, if (scene == 0) 190f else 157f)
            close()
        }
        paint.color = Color.rgb(115, 231, 251)
        canvas.drawPath(tip, paint)
        paint.color = Color.rgb(29, 99, 77)
        canvas.drawRoundRect(414f, 65f, 689f, 119f, 5f, 5f, paint)
        label(canvas, if (scene == 0) "示例道路 →" else "示例出口  →", 440f, 100f, 29f, Color.WHITE)
        label(canvas, "非高德原图 · 仅显示测试", 22f, 340f, 20f, Color.rgb(224, 237, 238))
        return bitmap
    }
}
