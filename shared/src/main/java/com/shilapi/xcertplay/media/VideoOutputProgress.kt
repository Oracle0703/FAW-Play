package com.shilapi.xcertplay.media

/** One refresh probe per playback episode for accepted input that has not reached output. */
internal class VideoOutputProgress {
    private var queuedPtsUs = Long.MIN_VALUE
    private var outputPtsUs = Long.MIN_VALUE
    private var lastProgressNs = 0L
    private var probed = false
    private var resumedInputStartNs = Long.MIN_VALUE
    private var previousInputNs = Long.MIN_VALUE

    fun reset() {
        queuedPtsUs = Long.MIN_VALUE; outputPtsUs = Long.MIN_VALUE
        lastProgressNs = 0; probed = false
        clearInputRun()
    }

    /**
     * Start a new user interaction or independently identified playback episode.
     * A short multi-frame refresh reply must not be mistaken for a new playback episode.
     * Keep the pending pictures and codec reference frames intact.
     */
    fun beginEpisode(nowNs: Long) {
        probed = false
        lastProgressNs = nowNs
        clearInputRun()
    }

    fun onInput(ptsUs: Long, nowNs: Long) {
        if (probed) {
            // Autonomous animation can resume without a touch. Require sustained traffic,
            // rather than the few closely spaced pictures returned by our refresh request.
            if (resumedInputStartNs == Long.MIN_VALUE || nowNs - previousInputNs !in 0..120_000_000L) {
                resumedInputStartNs = nowNs
            } else if (nowNs - resumedInputStartNs >= 500_000_000L) {
                beginEpisode(nowNs)
            }
            previousInputNs = nowNs
        }
        queuedPtsUs = maxOf(queuedPtsUs, ptsUs)
        lastProgressNs = nowNs
    }

    fun onOutput(ptsUs: Long, nowNs: Long) {
        outputPtsUs = maxOf(outputPtsUs, ptsUs)
        lastProgressNs = nowNs
    }

    fun ageNanos(nowNs: Long): Long = (nowNs - lastProgressNs).coerceAtLeast(0)

    fun shouldProbe(nowNs: Long): Boolean {
        return queuedPtsUs > outputPtsUs && !probed && ageNanos(nowNs) >= 500_000_000L
    }

    fun probeScheduled() { probed = true; clearInputRun() }
    fun probeFailed() { probed = false; clearInputRun() }

    private fun clearInputRun() {
        resumedInputStartNs = Long.MIN_VALUE
        previousInputNs = Long.MIN_VALUE
    }
}
