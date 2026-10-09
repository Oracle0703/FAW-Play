package com.shilapi.xcertplay.media

/** Resume only the player interrupted by a confirmed Siri active-to-idle transition. */
internal class SiriPlaybackResumeState {
    private var playing = false
    private var source: String? = null
    private var phoneActive: Boolean? = null
    private var speechActive: Boolean? = null
    private var awaitingSpeech = false
    private var interrupted = false
    private var interruptedSource: String? = null
    private var pending: Long? = null
    private var generation = 0L

    fun playback(update: CarPlayNowPlaying) {
        playing = update.playing
        source = update.sourceApp
        if ((interrupted || pending != null) && source != interruptedSource) cancel()
        if (playing) pending = null
    }

    /** Capture before sending the voice key: playback metadata can pause before modesChanged. */
    fun requested(): Long? {
        pending = null
        if (speechActive == true) return null
        if (!awaitingSpeech) capturePlayback()
        awaitingSpeech = true
        return ++generation
    }

    fun expireRequest(ticket: Long) {
        if (awaitingSpeech && generation == ticket) cancel()
    }

    fun phone(active: Boolean?) {
        phoneActive = active
        if (active != false) cancel()
    }

    fun speech(active: Boolean?): Long? {
        if (active == null) {
            speechActive = null
            cancel()
            return null
        }
        if (active == speechActive) return null
        val wasActive = speechActive == true
        speechActive = active
        if (active) {
            pending = null
            generation++
            if (!awaitingSpeech) capturePlayback()
            awaitingSpeech = false
            return null
        }
        // An initial/repeated idle report must not discard a pre-request snapshot.
        if (!wasActive) return null
        awaitingSpeech = false
        val resume = interrupted && phoneActive == false && source == interruptedSource
        interrupted = false
        // The delayed check also covers a paused metadata update arriving just after idle.
        return if (resume) (++generation).also { pending = it } else null
    }

    fun cancel() {
        generation++
        pending = null
        awaitingSpeech = false
        interrupted = false
    }

    fun consume(ticket: Long): Boolean {
        if (pending != ticket) return false
        pending = null
        return speechActive == false && phoneActive == false && !playing && source == interruptedSource
    }

    private fun capturePlayback() {
        interrupted = playing && source != null && phoneActive == false
        interruptedSource = source
    }
}
