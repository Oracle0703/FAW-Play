package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HongqiNavigation
import kotlin.math.roundToInt

/** Navigation cards on the instrument map; maneuver glyphs use Material Symbols (Apache 2.0). */
internal class ClusterTurnCardView(context: Context, previewHongqi: Boolean = false) : View(context) {
    private val preview = previewHongqi && com.shilapi.xcertplay.shared.BuildConfig.DEBUG
    private fun hongqiLayout() = preview || HongqiNavigation.clusterDefault()
    private fun tuningLayout() = hongqiLayout()
    private var guidance: ClusterTurnGuidance? = null
    private var night = false
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT

    private val accent = Color.rgb(10, 132, 255)
    /** Card opacity as percent; 100 keeps the historical fully-opaque look available. */
    private var opacityPercent = 85
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(224, 235, 235, 240); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private var glyph: Drawable? = null
    private var glyphTag: Int = -1
    private val tuningPreferences = ClusterLayoutTuning.preferences(context)
    private val tuningListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        postInvalidate()
    }

    init { applyPalette() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (tuningLayout()) tuningPreferences.registerOnSharedPreferenceChangeListener(tuningListener)
    }

    override fun onDetachedFromWindow() {
        tuningPreferences.unregisterOnSharedPreferenceChangeListener(tuningListener)
        super.onDetachedFromWindow()
    }

    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidate()
    }

    /** Percent 20..100 of the card's background alpha; text stays readable at any value. */
    fun setOpacity(percent: Int) {
        val next = percent.coerceIn(20, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    /** Day: dark glass on a light map; night: lighter translucent panel for the dark map. */
    fun setNightMode(night: Boolean) {
        if (night == this.night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (tuningLayout()) {
            glassPaint.color = if (night) Color.argb(alpha, 12, 17, 23) else Color.argb(alpha, 20, 26, 33)
            chipPaint.color = Color.argb((alpha * 0.10f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.13f).toInt().coerceAtLeast(10), 222, 230, 240)
            strokePaint.strokeWidth = dp(1f)
            distancePaint.color = Color.rgb(248, 250, 252)
            distancePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            distancePaint.fontFeatureSettings = "tnum"
            roadPaint.color = Color.rgb(183, 195, 210)
            infoPaint.color = Color.rgb(230, 237, 246)
            infoPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            infoPaint.fontFeatureSettings = "tnum"
            badgePaint.color = accent
            return
        }
        if (night) {
            glassPaint.color = Color.argb(alpha, 12, 14, 18)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.argb(255, 240, 242, 246)
            roadPaint.color = Color.argb(179, 176, 182, 192)
            infoPaint.color = Color.argb(224, 226, 228, 236)
            badgePaint.color = accent
        } else {
            glassPaint.color = Color.argb(alpha, 28, 28, 30)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.15f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.WHITE
            roadPaint.color = Color.argb(179, 199, 199, 204)
            infoPaint.color = Color.argb(224, 235, 235, 240)
            badgePaint.color = accent
        }
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        visibility = if (next == null) GONE else VISIBLE
        bringToFront()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val hongqi = hongqiLayout()
        val originalCard = if (hongqi) hongqiCard() else
            ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val tuning = tuningLayout()
        val placement = if (tuning) ClusterLayoutTuning.forDrawing(context, ClusterLayoutTuning.Target.TBT) else null
        val card = placement?.let {
            ClusterLayoutTuning.rect(it, width, height, originalCard.width, originalCard.height)
        } ?: originalCard
        val textScale = placement?.scale?.div(100f) ?: 1f
        val h = card.height.toFloat()
        val w = card.width.toFloat()

        val radius = cornerRadius(h, h * 0.30f)
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        drawTurnContent(canvas, next, card, hongqi, textScale,
            if (placement?.height != null) originalCard.height * textScale else h,
            clip = placement != null)
        // The route information bar stays independent of the turn banner.
        val infoAnchor = if (tuning) originalCard else card
        drawInfoStrip(canvas, next, infoAnchor.left, infoAnchor.top + infoAnchor.height,
            infoAnchor.width, infoAnchor.height)
    }

    private fun drawTurnContent(canvas: Canvas, next: ClusterTurnGuidance,
        card: ClusterTurnCardOverlay.CardRect, hongqi: Boolean, textScale: Float,
        contentHeight: Float, clip: Boolean) {
        val checkpoint = canvas.save()
        if (clip) canvas.clipRect(card.left, card.top, card.left + card.width, card.top + card.height)
        try {
            drawTurnLabels(canvas, next, card, hongqi, textScale, contentHeight)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawTurnLabels(canvas: Canvas, next: ClusterTurnGuidance,
        card: ClusterTurnCardOverlay.CardRect, hongqi: Boolean, textScale: Float, h: Float) {
        val tuning = tuningLayout()
        distancePaint.textSize = if (hongqi) dp(HONGQI_DISTANCE_TEXT_DP) * textScale else h * 0.30f
        roadPaint.textSize = if (hongqi) dp(HONGQI_ROAD_TEXT_DP) * textScale else h * 0.17f
        val contentTop = card.top + (card.height - h) / 2f
        val chip = h * 0.76f
        val contentGap = h * if (tuning) 0.10f else 0.14f
        val textWidth = card.width - chip - contentGap - h * if (tuning) 0.20f else 0.22f
        val road = roadLabel(next)
        val distanceText = ellipsize(distanceLabel(next.distanceMeters), textWidth, distancePaint)
        val roadText = ellipsize(road, textWidth, roadPaint)
        // Center the visible icon-and-label group, rather than an empty text column.
        val groupWidth = chip + if (textWidth > 0f) contentGap +
            maxOf(distancePaint.measureText(distanceText), roadPaint.measureText(roadText)) else 0f
        val chipLeft = card.left + if (tuning) (card.width - groupWidth) / 2f else h * 0.12f
        val chipTop = contentTop + (h - chip) / 2f
        rect.set(chipLeft, chipTop, chipLeft + chip, chipTop + chip)
        val radius = cornerRadius(chip, chip * 0.26f)
        canvas.drawRoundRect(rect, radius, radius, chipPaint)

        val exit = next.roundaboutExit.takeIf { it in 1..9 }
        drawGlyph(canvas, next, chipLeft, chipTop, chip, exit)

        val textLeft = chipLeft + chip + contentGap
        if (textWidth <= 0f) return
        val distanceMetrics = distancePaint.fontMetrics
        val roadMetrics = roadPaint.fontMetrics
        val gap = dp(6f) * textScale
        val textHeight = distanceMetrics.descent - distanceMetrics.ascent +
            if (road.isEmpty()) 0f else gap + roadMetrics.descent - roadMetrics.ascent
        val distanceBaseline = if (tuning) card.top + (card.height - textHeight) / 2f - distanceMetrics.ascent
            else contentTop + h * 0.44f
        canvas.drawText(
            distanceText,
            textLeft, distanceBaseline, distancePaint,
        )
        if (road.isNotEmpty()) {
            val roadBaseline = if (tuning) distanceBaseline + distanceMetrics.descent + gap - roadMetrics.ascent
                else contentTop + h * 0.72f
            canvas.drawText(
                roadText,
                textLeft, roadBaseline, roadPaint,
            )
        }
    }

    /** The arrival/duration/distance pill that hangs under the card, like the stock nav bar. */
    private fun drawInfoStrip(canvas: Canvas, next: ClusterTurnGuidance, left: Int, belowTop: Int, width: Int,
        cardHeight: Int) {
        val parts = infoParts(next)
        if (parts.isEmpty()) return
        val placement = if (tuningLayout())
            ClusterLayoutTuning.forDrawing(context, ClusterLayoutTuning.Target.ETE) else null
        if (placement != null) {
            drawTunedInfoStrip(canvas, parts.joinToString(infoSeparator()), placement)
            return
        }
        val hongqi = hongqiLayout()
        val h = if (hongqi) dp(HONGQI_INFO_HEIGHT_DP) else (cardHeight * 0.40f).coerceAtLeast(34f)
        val gap = cardHeight * 0.05f
        val top = (belowTop + gap).coerceAtMost(height - h)
        infoPaint.textSize = if (hongqi) dp(HONGQI_INFO_TEXT_DP) else h * 0.42f
        val text = parts.joinToString(infoSeparator())
        val textWidth = paintMeasure(text)
        // Wider than the card is fine: the strip centers on the card and may reach the panel edges.
        // left is an absolute panel coordinate, so every clamp below uses the panel width, never
        // the card width — clamping against the card width is what threw the strip to panel-left.
        val maxStrip = this.width - (dp(8f) * 2).toInt()
        val stripWidth = maxOf(width, (textWidth + h * 0.9f).toInt()).coerceAtMost(maxStrip)
        val stripLeft = (left + width / 2f - stripWidth / 2f).toInt()
            .coerceIn(4, (this.width - stripWidth - 4).coerceAtLeast(4))
        rect.set(stripLeft.toFloat(), top.toFloat(), (stripLeft + stripWidth).toFloat(), top + h)
        val radius = cornerRadius(h, h / 2f)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        val draw = if (textWidth <= stripWidth - h * 0.6f) text else ellipsize(text, stripWidth - h * 0.6f, infoPaint)
        val drawWidth = paintMeasure(draw)
        val centerX = stripLeft + stripWidth / 2f
        val baseline = if (tuningLayout()) centeredBaseline(top, h, infoPaint) else top + h * 0.69f
        canvas.drawText(draw, centerX - drawWidth / 2f, baseline, infoPaint)
    }

    private fun paintMeasure(text: String): Float = infoPaint.measureText(text)

    private fun drawTunedInfoStrip(canvas: Canvas, label: String, placement: ClusterLayoutTuning.Placement) {
        val box = ClusterLayoutTuning.rect(placement, width, height,
            dp(HONGQI_CARD_WIDTH_DP).roundToInt(), dp(HONGQI_INFO_HEIGHT_DP).roundToInt())
        val h = box.height.toFloat()
        rect.set(box.left.toFloat(), box.top.toFloat(), (box.left + box.width).toFloat(), box.top + h)
        val radius = cornerRadius(h, h / 2f)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        infoPaint.textSize = dp(HONGQI_INFO_TEXT_DP) * placement.scale / 100f
        val text = ellipsize(label, box.width - dp(12f) * 2, infoPaint)
        val checkpoint = canvas.save()
        canvas.clipRect(box.left, box.top, box.left + box.width, box.top + box.height)
        canvas.drawText(text, box.left + (box.width - paintMeasure(text)) / 2f,
            centeredBaseline(box.top.toFloat(), h, infoPaint), infoPaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun centeredBaseline(top: Float, height: Float, paint: Paint): Float {
        val metrics = paint.fontMetrics
        return top + height / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    private fun infoSeparator(): String = if (tuningLayout()) "  ·  " else "   ·   "

    private fun cornerRadius(height: Float, original: Float): Float =
        if (tuningLayout()) minOf(dp(HONGQI_TUNING_CORNER_DP), height * 0.12f) else original

    private fun hongqiCard(): ClusterTurnCardOverlay.CardRect {
        val cardWidth = dp(HONGQI_CARD_WIDTH_DP).roundToInt().coerceIn(1, width)
        val cardHeight = dp(HONGQI_CARD_HEIGHT_DP).roundToInt().coerceIn(1, height)
        val left = (width - dp(HONGQI_CARD_END_DP).roundToInt() - cardWidth).coerceAtLeast(0)
        val top = dp(HONGQI_CARD_TOP_DP).roundToInt().coerceIn(0, height - cardHeight)
        return ClusterTurnCardOverlay.CardRect(left, top, cardWidth, cardHeight)
    }

    private companion object {
        // Stock Presentation layout uses dp, not pixels, on the HS5's 213 dpi cluster.
        const val HONGQI_CARD_WIDTH_DP = 500f
        const val HONGQI_CARD_END_DP = 75f
        const val HONGQI_CARD_TOP_DP = 120f
        // Our road sits below the distance, so its two-line card needs extra height.
        const val HONGQI_CARD_HEIGHT_DP = 120f
        const val HONGQI_INFO_HEIGHT_DP = 60f
        const val HONGQI_DISTANCE_TEXT_DP = 40f
        const val HONGQI_ROAD_TEXT_DP = 30f
        const val HONGQI_INFO_TEXT_DP = 28f
        const val HONGQI_TUNING_CORNER_DP = 8f
    }

    private fun infoParts(next: ClusterTurnGuidance): List<String> {
        val parts = mutableListOf<String>()
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
            parts += context.getString(R.string.turn_card_info_arrival, time)
        }
        next.remainingSeconds?.takeIf { it > 0 }?.let { parts += durationLabel(it) }
        next.remainingMeters?.takeIf { it > 0 }?.let { meters ->
            parts += context.getString(R.string.turn_card_info_km, meters / 1000.0)
        }
        return parts
    }

    private fun durationLabel(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            context.getString(R.string.turn_card_info_duration_hm, hours.toInt(), minutes.toInt())
        } else {
            context.getString(R.string.turn_card_info_duration_m, minutes.toInt())
        }
    }

    /** Draws the tinted Material Symbols glyph; the roundabout exit number gets a corner badge. */
    private fun drawGlyph(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, side: Float, exit: Int?) {
        if (next.icon == 0) return
        val resId = glyphRes(next.icon)
        if (resId != glyphTag) {
            glyph = ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
                setTint(if (tuningLayout()) Color.rgb(248, 250, 252) else accent)
            }
            glyphTag = resId
        }
        val inset = side * if (tuningLayout()) 0.04f else 0.10f
        glyph?.setBounds(
            (left + inset).toInt(), (top + inset).toInt(),
            (left + side - inset).toInt(), (top + side - inset).toInt(),
        )
        glyph?.draw(canvas)
        if (exit != null) {
            val d = side * 0.42f
            val cx = left + side - d / 2f
            val cy = top + side - d / 2f
            canvas.drawCircle(cx, cy, d / 2f, badgePaint)
            badgeTextPaint.textSize = d * 0.62f
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(exit.toString(), cx, textY, badgeTextPaint)
        }
    }

    private fun glyphRes(icon: Int): Int = when (icon) {
        2 -> R.drawable.ic_turn_card_turn_left
        3 -> R.drawable.ic_turn_card_turn_right
        4 -> R.drawable.ic_turn_card_turn_slight_left
        5 -> R.drawable.ic_turn_card_turn_slight_right
        6 -> R.drawable.ic_turn_card_turn_sharp_left
        7 -> R.drawable.ic_turn_card_turn_sharp_right
        8 -> R.drawable.ic_turn_card_u_turn_left
        19 -> R.drawable.ic_turn_card_u_turn_right
        11, 12, 17, 18 -> R.drawable.ic_turn_card_roundabout_right
        15 -> R.drawable.ic_turn_card_sports_score
        else -> R.drawable.ic_turn_card_straight
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(
            R.string.turn_card_distance_km, meters / 100 / 10f,
        )
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
