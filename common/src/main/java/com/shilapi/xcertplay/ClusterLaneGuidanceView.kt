package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.ClusterLaneStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Draws only the phone's actual lane directions, independently of the native turn card. */
internal class ClusterLaneGuidanceView(context: Context) : View(context) {
    private var guidance: ClusterLaneGuidance? = null
    private var night = false
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(224, 18, 26, 34)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 183, 195, 210)
    }

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setGuidance(next: ClusterLaneGuidance?) {
        // Keep all lanes in their transmitted order. A row that cannot remain
        // readable is hidden as a whole; it must never silently omit a real lane.
        val shown = next?.takeIf { it.lanes.size in 1..MAX_VISIBLE_LANES }?.let {
            it.copy(lanes = it.lanes.map { lane -> lane.copy(angles = lane.angles.toList()) })
        }
        visibility = if (shown == null) GONE else VISIBLE
        if (guidance == shown) return
        guidance = shown
        invalidate()
    }

    fun setNightMode(next: Boolean) {
        if (night == next) return
        night = next
        backgroundPaint.color = if (next) Color.argb(236, 12, 18, 25)
            else Color.argb(224, 18, 26, 34)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lanes = guidance?.lanes ?: return
        if (width <= 0 || height <= 0) return
        val xScale = width / PANEL_WIDTH.toFloat()
        val yScale = height / PANEL_HEIGHT.toFloat()
        val row = stripBounds(width, height, lanes.size)
        val padding = 12f * xScale
        val laneWidth = (row.width() - 2f * padding) / lanes.size
        val scale = min(min(xScale, yScale), laneWidth / 48f)
        canvas.drawRoundRect(row, 12f * scale, 12f * scale, backgroundPaint)
        arrowPaint.strokeWidth = (4f * scale).coerceAtLeast(1.5f)
        separatorPaint.strokeWidth = (1f * scale).coerceAtLeast(1f)
        val centerY = row.centerY()
        lanes.forEachIndexed { position, lane ->
            val centerX = row.left + padding + laneWidth * (position + 0.5f)
            if (position > 0) {
                val divider = row.left + padding + laneWidth * position
                canvas.drawLine(divider, row.top + 14f * yScale,
                    divider, row.bottom - 14f * yScale, separatorPaint)
            }
            val highlight = lane.highlightedAngle
            arrowPaint.color = when {
                lane.status == ClusterLaneStatus.NOT_GOOD -> NOT_GOOD_COLOR
                lane.status == ClusterLaneStatus.PREFERRED && highlight == null -> PREFERRED_COLOR
                else -> GOOD_COLOR
            }
            // Multiple allowed directions share a stem; none is inferred from the route turn.
            lane.angles.distinct().forEach { angle ->
                canvas.drawPath(directionPath(centerX, centerY, scale, angle), arrowPaint)
            }
            if (highlight != null) {
                arrowPaint.color = PREFERRED_COLOR
                canvas.drawPath(directionPath(centerX, centerY, scale, highlight), arrowPaint)
            }
        }
    }

    companion object {
        private const val PANEL_WIDTH = 1920
        private const val PANEL_HEIGHT = 720
        private const val MAX_VISIBLE_LANES = 12
        internal val NOT_GOOD_COLOR = Color.rgb(119, 131, 143)
        internal val GOOD_COLOR = Color.rgb(239, 244, 248)
        internal val PREFERRED_COLOR = Color.rgb(91, 213, 255)

        /** Left of the native junction area and above the existing bottom-left ETA row. */
        internal fun stripBounds(width: Int, height: Int, lanes: Int): RectF {
            val xScale = width / PANEL_WIDTH.toFloat()
            val yScale = height / PANEL_HEIGHT.toFloat()
            val rowWidth = (lanes * 48f + 24f).coerceAtMost(450f)
            return RectF(90f * xScale, 516f * yScale,
                (90f + rowWidth) * xScale, 602f * yScale)
        }

        private fun directionPath(centerX: Float, centerY: Float, scale: Float, angle: Int): Path {
            // CarPlay uses signed degrees: negative left, positive right, zero forward.
            val radians = Math.toRadians(angle.toDouble())
            val dx = sin(radians).toFloat()
            val dy = -cos(radians).toFloat()
            val jointY = centerY - 2f * scale
            val endX = centerX + if (abs(angle) > 90) {
                (if (angle < 0) -20f else 20f) * scale
            } else dx * 22f * scale
            val endY = jointY + dy * 22f * scale
            return Path().apply {
                moveTo(centerX, centerY + 25f * scale)
                lineTo(centerX, jointY)
                if (angle == 0) lineTo(endX, endY)
                else cubicTo(centerX, jointY - 20f * scale,
                    endX - dx * 10f * scale, endY - dy * 10f * scale, endX, endY)
                // The head follows the actual angle even for sharp turns and signed U-turns.
                moveTo(endX - dx * 8f * scale - dy * 5f * scale,
                    endY - dy * 8f * scale + dx * 5f * scale)
                lineTo(endX, endY)
                lineTo(endX - dx * 8f * scale + dy * 5f * scale,
                    endY - dy * 8f * scale - dx * 5f * scale)
            }
        }
    }
}
