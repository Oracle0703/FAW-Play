package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.shilapi.xcertplay.ui.i18n.I18n
import com.shilapi.xcertplay.ui.i18n.tr
import com.shilapi.xcertplay.ui.theme.Radius
import com.shilapi.xcertplay.ui.theme.Space
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Loads one local image and writes a user-positioned 1:1 PNG into the AirPlay icon slot. */
class ImageCropActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cropView: SquareCropView
    private var status by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }

        I18n.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        cropView = SquareCropView(this)
        status = tr("crop.loading")

        // The crop surface stays a view; the chrome follows the Lavender system.
        val chrome = ComposeView(this).apply {
            setContent {
                XcertplayTheme(darkTheme = true) { CropChrome() }
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                cropView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                chrome,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)

        executor.execute {
            val bitmap = try {
                decodeBitmap(uri)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    status = tr("crop.decode_failed")
                } else {
                    cropView.setBitmap(bitmap)
                    status = tr("crop.help")
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        cropView.bitmap?.recycle()
        super.onDestroy()
    }

    private fun saveCrop() {
        val cropped = cropView.cropToSquare() ?: run {
            status = tr("crop.not_ready")
            return
        }
        val encoded = ByteArrayOutputStream().use { output ->
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                cropped.recycle()
                status = tr("crop.encode_failed")
                return
            }
            cropped.recycle()
            output.toByteArray()
        }
        try {
            AirPlayPersistence.saveCustomAirPlayIcon(applicationContext, encoded)
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_CROP_COMPLETE, true),
            )
            finish()
        } catch (_: Exception) {
            status = tr("crop.save_failed")
        }
    }

    @androidx.compose.runtime.Composable
    private fun CropChrome() {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s4)) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter),
                shape = RoundedCornerShape(Radius.lg),
                color = scheme.surfaceContainerHigh,
            ) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = Space.s4, vertical = Space.s2),
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter),
                shape = RoundedCornerShape(Radius.xl),
                color = scheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    TextButton(onClick = {
                        setResult(RESULT_CANCELED)
                        finish()
                    }) { Text(tr("common.cancel"), style = MaterialTheme.typography.labelLarge) }
                    Button(onClick = { saveCrop() }) {
                        Text(tr("crop.save"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class SquareCropView(context: android.content.Context) : View(context) {
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = Color.argb(145, 0, 0, 0) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
        }
        private val matrix = Matrix()
        private val viewport = RectF()
        private val mappedBitmap = RectF()
        private var minimumScale = 1f
        private var maximumScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var lastDistance = 0f

        var bitmap: Bitmap? = null
            private set

        fun setBitmap(value: Bitmap) {
            bitmap?.recycle()
            bitmap = value
            if (width > 0 && height > 0) configure()
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            configure()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val source = bitmap ?: return
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f, 0f, width.toFloat(), viewport.top, dimPaint)
            canvas.drawRect(0f, viewport.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, viewport.top, viewport.left, viewport.bottom, dimPaint)
            canvas.drawRect(viewport.right, viewport.top, width.toFloat(), viewport.bottom, dimPaint)

            val save = canvas.save()
            canvas.clipRect(viewport)
            canvas.drawBitmap(source, matrix, imagePaint)
            canvas.restoreToCount(save)

            canvas.drawRect(viewport, borderPaint)
            val thirdWidth = viewport.width() / 3f
            val thirdHeight = viewport.height() / 3f
            canvas.drawLine(viewport.left + thirdWidth, viewport.top, viewport.left + thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.right - thirdWidth, viewport.top, viewport.right - thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.left, viewport.top + thirdHeight, viewport.right, viewport.top + thirdHeight, gridPaint)
            canvas.drawLine(viewport.left, viewport.bottom - thirdHeight, viewport.right, viewport.bottom - thirdHeight, gridPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (bitmap == null) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastDistance = pointerDistance(event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (lastDistance > 0f) {
                            val factor = distance / lastDistance
                            matrix.postScale(
                                factor,
                                factor,
                                (event.getX(0) + event.getX(1)) / 2f,
                                (event.getY(0) + event.getY(1)) / 2f,
                            )
                        }
                        lastDistance = distance
                    } else {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                    }
                    lastX = event.x
                    lastY = event.y
                    constrainMatrix()
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    lastDistance = 0f
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    lastDistance = 0f
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun cropToSquare(): Bitmap? {
            val source = bitmap ?: return null
            if (viewport.width() <= 0f || viewport.height() <= 0f) return null
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val sourceRect = RectF()
            inverse.mapRect(sourceRect, viewport)
            val left = floor(sourceRect.left).toInt().coerceIn(0, source.width - 1)
            val top = floor(sourceRect.top).toInt().coerceIn(0, source.height - 1)
            val right = ceil(sourceRect.right).toInt().coerceIn(left + 1, source.width)
            val bottom = ceil(sourceRect.bottom).toInt().coerceIn(top + 1, source.height)
            val square = min(right - left, bottom - top)
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val cropLeft = (centerX - square / 2).coerceIn(0, source.width - square)
            val cropTop = (centerY - square / 2).coerceIn(0, source.height - square)
            val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, square, square)
            val output = Bitmap.createBitmap(
                OUTPUT_ICON_PIXELS,
                OUTPUT_ICON_PIXELS,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(output).drawBitmap(
                cropped,
                null,
                RectF(0f, 0f, OUTPUT_ICON_PIXELS.toFloat(), OUTPUT_ICON_PIXELS.toFloat()),
                imagePaint,
            )
            if (cropped !== source) cropped.recycle()
            return output
        }

        private fun configure() {
            val source = bitmap ?: return
            if (width <= 0 || height <= 0) return
            val side = min(width, height).toFloat() - 2f * CROP_PADDING_DP * resources.displayMetrics.density
            if (side <= 0f) return
            viewport.set(
                (width - side) / 2f,
                (height - side) / 2f,
                (width + side) / 2f,
                (height + side) / 2f,
            )
            minimumScale = max(side / source.width, side / source.height)
            maximumScale = minimumScale * MAX_ZOOM
            matrix.reset()
            matrix.postScale(minimumScale, minimumScale)
            matrix.postTranslate(
                viewport.centerX() - source.width * minimumScale / 2f,
                viewport.centerY() - source.height * minimumScale / 2f,
            )
            constrainMatrix()
            invalidate()
        }

        private fun constrainMatrix() {
            val source = bitmap ?: return
            val values = FloatArray(9)
            matrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            if (scale < minimumScale) {
                matrix.postScale(minimumScale / scale, minimumScale / scale, viewport.centerX(), viewport.centerY())
            } else if (scale > maximumScale) {
                matrix.postScale(maximumScale / scale, maximumScale / scale, viewport.centerX(), viewport.centerY())
            }
            mappedBitmap.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
            matrix.mapRect(mappedBitmap)
            var dx = 0f
            var dy = 0f
            if (mappedBitmap.left > viewport.left) dx = viewport.left - mappedBitmap.left
            if (mappedBitmap.right < viewport.right) dx = viewport.right - mappedBitmap.right
            if (mappedBitmap.top > viewport.top) dy = viewport.top - mappedBitmap.top
            if (mappedBitmap.bottom < viewport.bottom) dy = viewport.bottom - mappedBitmap.bottom
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private companion object {
            const val CROP_PADDING_DP = 24f
            const val MAX_ZOOM = 8f
        }
    }

    companion object {
        const val EXTRA_CROP_COMPLETE = "crop_complete"
        private const val MAX_DECODE_EDGE = 2048
        private const val OUTPUT_ICON_PIXELS = 1024
    }
}
