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

    private var lastSenderNs = 0L
    private var maxSenderGapNs = 0L
    private var lastCodecOutputNs = 0L
    private val codecInputs = LinkedHashMap<Long, Long>()
    private var inputs = 0
    private var outputs = 0
    private var maxDecodeNs = 0L
    private var maxReceiveToSubmitNs = 0L
    private var touchPresentSamples = 0
    private var touchPresentSumNs = 0L
    private var touchPresentMaxNs = 0L

    @Synchronized fun onReceived(size: Int, arrivalNanos: Long = nanoTime(), senderNanos: Long = 0L) {
        val now = arrivalNanos
        maxReceiveToSubmitNs = maxOf(maxReceiveToSubmitNs, (nanoTime() - now).coerceAtLeast(0))
        if (senderNanos > 0 && lastSenderNs > 0 && senderNanos >= lastSenderNs)
            maxSenderGapNs = maxOf(maxSenderGapNs, senderNanos - lastSenderNs)
        if (senderNanos > 0) lastSenderNs = senderNanos
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap >= 0) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
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
    private var lastPresentationNs = 0L
    private var maxPresentationGapNs = 0L
    private var maxQueueAgeNs = 0L
    private var maxInputWaitNs = 0L
    private var maxRebuildNs = 0L
    private var maxKeyFrameWaitNs = 0L

    @Synchronized fun onPresented(atNs: Long, ptsUs: Long = 0L) {
        if (label.isEmpty()) {
            val latency = TouchLatencyProbe.onPresented(ptsUs, atNs)
            if (latency >= 0) {
                touchPresentSamples++; touchPresentSumNs += latency
                touchPresentMaxNs = maxOf(touchPresentMaxNs, latency)
            }
        }
        if (lastPresentationNs != 0L) maxPresentationGapNs = maxOf(maxPresentationGapNs, atNs - lastPresentationNs)
        lastPresentationNs = atNs
        presented++
    }
    @Synchronized fun onCodecInput(ptsUs: Long, atNs: Long) {
        inputs++
        codecInputs[ptsUs] = atNs
        if (codecInputs.size > 128) codecInputs.remove(codecInputs.keys.first())
    }
    @Synchronized fun onCodecOutput(ptsUs: Long) {
        val now = nanoTime()
        outputs++; lastCodecOutputNs = now
        codecInputs.remove(ptsUs)?.let { maxDecodeNs = maxOf(maxDecodeNs, now - it) }
    }
    @Synchronized fun onDecoderReset() { codecInputs.clear() }

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
        if (lastArrivalNs == 0L && received == 0 && touchSamples == 0 && presented == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        )
        if (label.isEmpty()) TouchLatencyProbe.maxSendNs = 0
        val measured = line + " codecDelivered=shown touch2frameEnds=receive" +
            " frameRenderedFps=${"%.1f".format(presented / seconds)} frameRenderedMaxGapMs=${maxPresentationGapNs / 1_000_000}" +
            " queueAgeMaxMs=${maxQueueAgeNs / 1_000_000} inputWaitMaxMs=${maxInputWaitNs / 1_000_000}" +
            " rebuildMaxMs=${maxRebuildNs / 1_000_000} keyFrameWaitMaxMs=${maxKeyFrameWaitNs / 1_000_000}" +
            " touchQueueWaitMaxMs=${TouchLatencyProbe.maxQueueWaitNs / 1_000_000} presentationEnds=codec_frame_rendered renderer=SurfaceView" +
            " codecInput=$inputs codecOutput=$outputs outputPending=${codecInputs.size}" +
            " outputPendingAgeMs=${codecInputs.values.firstOrNull()?.let { (now - it).coerceAtLeast(0) / 1_000_000 } ?: 0}" +
            " decodeMaxMs=${maxDecodeNs / 1_000_000} receiveToSubmitMaxMs=${maxReceiveToSubmitNs / 1_000_000}" +
            " receiveIdleMs=${(now - lastArrivalNs).coerceAtLeast(0) / 1_000_000}" +
            " outputIdleMs=${if (lastCodecOutputNs == 0L) -1 else (now - lastCodecOutputNs).coerceAtLeast(0) / 1_000_000}" +
            " senderGapMaxMs=${maxSenderGapNs / 1_000_000}" +
            " touch2present avg=${if (touchPresentSamples == 0) -1 else touchPresentSumNs / touchPresentSamples / 1_000_000}ms" +
            " max=${touchPresentMaxNs / 1_000_000}ms n=$touchPresentSamples touchAwaitingMs=${if (label.isEmpty()) TouchLatencyProbe.awaitingNanos(now) / 1_000_000 else 0}" +
            " gapClassification=unknown pageComplete=unmeasured"
        Log.i(TAG, measured)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        presented = 0
        maxPresentationGapNs = 0; maxQueueAgeNs = 0
        maxInputWaitNs = 0; maxRebuildNs = 0; maxKeyFrameWaitNs = 0
        inputs = 0; outputs = 0; maxDecodeNs = 0; maxReceiveToSubmitNs = 0; maxSenderGapNs = 0
        touchPresentSamples = 0; touchPresentSumNs = 0; touchPresentMaxNs = 0
        if (label.isEmpty()) TouchLatencyProbe.maxQueueWaitNs = 0
        return measured
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
    }
}
