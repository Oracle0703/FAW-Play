package com.shilapi.xcertplay.media

/** Remembers a call interruption without turning an already-paused player on. */
internal class CallPlaybackResumeState {
    private var playing = false
    private var source: String? = null
    private var callActive = false
    private var interrupted = false
    private var interruptedSource: String? = null
    private var pending: Int? = null
    private var generation = 0

    fun playback(update: CarPlayNowPlaying) {
        playing = update.playing
        source = update.sourceApp
        if (playing || (interrupted && source != interruptedSource)) {
            pending = null
            if (source != interruptedSource) interrupted = false
        }
    }

    /** A ticket is issued only for a real active-to-idle transition. */
    fun phone(active: Boolean?): Int? {
        if (active == null || active == callActive) return null
        generation++
        pending = null
        callActive = active
        if (active) {
            interrupted = playing
            interruptedSource = source
            return null
        }
        val resume = interrupted && !playing && source == interruptedSource
        interrupted = false
        return if (resume) generation.also { pending = it } else null
    }

    /** Consume once after allowing the iPhone to resume playback itself. */
    fun consume(ticket: Int): Boolean {
        if (pending != ticket || callActive || playing || source != interruptedSource) return false
        pending = null
        return true
    }
}
