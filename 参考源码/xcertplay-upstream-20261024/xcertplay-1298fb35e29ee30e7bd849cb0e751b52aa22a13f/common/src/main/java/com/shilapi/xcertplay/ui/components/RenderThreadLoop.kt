package com.shilapi.xcertplay.ui.components

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/** One tinted [AnimatedVectorDrawable] of a [RenderThreadLoop]. */
data class LoopLayer(@DrawableRes val drawable: Int, val tint: Color)

/**
 * Loops animated vectors, stacked in order, while on screen. The platform runs an
 * AnimatedVectorDrawable on the RenderThread, so the loop keeps moving while the main thread is
 * busy composing; a Compose animation would stall with it.
 */
@Composable
fun RenderThreadLoop(layers: List<LoopLayer>, modifier: Modifier = Modifier) {
    AndroidView(factory = ::LoopView, modifier = modifier, update = { it.bind(layers) })
}

private class LoopView(context: Context) : ImageView(context) {
    private var ids: List<Int> = emptyList()
    private var drawables: List<AnimatedVectorDrawable> = emptyList()

    init {
        scaleType = ScaleType.FIT_XY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(layers: List<LoopLayer>) {
        val wanted = layers.map { it.drawable }
        if (wanted != ids) {
            drawables.forEach { it.stop() }
            ids = wanted
            drawables = wanted.map { context.getDrawable(it)!!.mutate() as AnimatedVectorDrawable }
            setImageDrawable(drawables.singleOrNull() ?: LayerDrawable(drawables.toTypedArray()))
            if (isAttachedToWindow) drawables.forEach { it.start() }
        }
        drawables.forEachIndexed { index, drawable -> drawable.setTint(layers[index].tint.toArgb()) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawables.forEach { it.start() }
    }

    override fun onDetachedFromWindow() {
        drawables.forEach { it.stop() }
        super.onDetachedFromWindow()
    }
}
