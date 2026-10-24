package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Direct decoder output. The holder owns the Surface, including when this view is detached. */
internal class VideoSurfaceView(
    context: Context,
    private val onSurface: (Surface?) -> Unit,
    private val onSizeChanged: (Int, Int) -> Unit = { _, _ -> },
) : SurfaceView(context), java.io.Closeable {
    private var output: Surface? = null
    private var closed = false
    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (closed || output === holder.surface) return
            detachOutput()
            output = holder.surface
            onSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!closed) onSizeChanged(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            detachOutput()
        }
    }

    init { holder.addCallback(callback) }

    private fun detachOutput() {
        val previous = output ?: return
        output = null
        try { onSurface(null) } finally {
            if (!com.shilapi.xcertplay.media.VideoSurfaceHandoff.releaseSurface(previous))
                android.util.Log.e("Autunn-Video", "decoder did not release destroyed surface before deadline")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        holder.removeCallback(callback)
        detachOutput()
    }
}
