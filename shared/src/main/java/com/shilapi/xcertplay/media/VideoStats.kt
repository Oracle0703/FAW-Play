package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    private var presented = 0
    private var textureUpdates = 0
    private var lastPresentationNs = 0L
    private var lastTextureNs = 0L
    private var maxPresentationGapNs = 0L
    private var maxTextureGapNs = 0L
    private var maxQueueAgeNs = 0L
    private var maxInputWaitNs = 0L
    private var maxRebuildNs = 0L
    private var maxKeyFrameWaitNs = 0L

    @Synchronized fun onPresented(atNs: Long) {
        if (lastPresentationNs != 0L) maxPresentationGapNs = maxOf(maxPresentationGapNs, atNs - lastPresentationNs)
        lastPresentationNs = atNs
        presented++
    }
    @Synchronized fun onTextureUpdated() {
        val now = nanoTime()
        if (lastTextureNs != 0L) maxTextureGapNs = maxOf(maxTextureGapNs, now - lastTextureNs)
        lastTextureNs = now
        textureUpdates++
    }
    @Synchronized fun onQueueAge(nanos: Long) { maxQueueAgeNs = maxOf(maxQueueAgeNs, nanos) }
    @Synchronized fun onInputWait(nanos: Long) { maxInputWaitNs = maxOf(maxInputWaitNs, nanos) }
    @Synchronized fun onRebuild(nanos: Long) { maxRebuildNs = maxOf(maxRebuildNs, nanos) }
    @Synchronized fun onKeyFrameWait(nanos: Long) { maxKeyFrameWaitNs = maxOf(maxKeyFrameWaitNs, nanos) }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0 && presented == 0 && textureUpdates == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        )
        TouchLatencyProbe.maxSendNs = 0
        val measured = line + " codecDelivered=shown touch2frameEnds=receive" +
            " frameRenderedFps=${"%.1f".format(presented / seconds)} frameRenderedMaxGapMs=${maxPresentationGapNs / 1_000_000}" +
            " textureFps=${"%.1f".format(textureUpdates / seconds)} textureMaxGapMs=${maxTextureGapNs / 1_000_000}" +
            " queueAgeMaxMs=${maxQueueAgeNs / 1_000_000} inputWaitMaxMs=${maxInputWaitNs / 1_000_000}" +
            " rebuildMaxMs=${maxRebuildNs / 1_000_000} keyFrameWaitMaxMs=${maxKeyFrameWaitNs / 1_000_000}" +
            " touchQueueWaitMaxMs=${TouchLatencyProbe.maxQueueWaitNs / 1_000_000} presentationEnds=surface_callback"
        Log.i(TAG, measured)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        presented = 0; textureUpdates = 0
        maxPresentationGapNs = 0; maxTextureGapNs = 0; maxQueueAgeNs = 0
        maxInputWaitNs = 0; maxRebuildNs = 0; maxKeyFrameWaitNs = 0
        if (label.isEmpty()) TouchLatencyProbe.maxQueueWaitNs = 0
        return measured
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
