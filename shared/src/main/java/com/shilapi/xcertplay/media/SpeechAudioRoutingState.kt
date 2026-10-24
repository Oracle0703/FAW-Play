package com.shilapi.xcertplay.media

import android.os.SystemClock

internal enum class SpeechAudioRoute { WAIT, ASSISTANT, MEDIA }

/** iPhone camera sessions can carry still-playing music on a speechRecognition stream. */
internal class SpeechAudioRoutingState(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var playing = false
    private var source: String? = null
    private var playingSince = 0L
    private var phoneActive: Boolean? = null
    private var speechActive: Boolean? = null
    private var siriRequestedAt: Long? = null
    private var siriConfirmed = false

    @Synchronized
    fun update(playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?): Boolean {
        val changed = playing != playback.playing || source != playback.sourceApp || phoneActive != phone || speechActive != speech
        if (playback.playing && (!playing || source != playback.sourceApp)) playingSince = now()
        playing = playback.playing
        source = playback.sourceApp
        phoneActive = phone
        speechActive = speech
        if (siriRequestedAt != null) {
            if (speech == true) siriConfirmed = true
            if (siriConfirmed && speech == false) clearSiriRequest()
        }
        return changed
    }

    @Synchronized
    fun siriRequested() {
        siriRequestedAt = now()
        siriConfirmed = speechActive == true
    }

    @Synchronized
    fun interruptsNavigationTail(): Boolean = phoneActive == true || siriRequestedAt?.let {
        siriConfirmed || now() - it < SIRI_REQUEST_TIMEOUT_MILLIS
    } == true

    @Synchronized
    fun route(streamStartedAt: Long): SpeechAudioRoute {
        val time = now()
        val requestedAt = siriRequestedAt
        if (requestedAt != null && !siriConfirmed && time - requestedAt >= SIRI_REQUEST_TIMEOUT_MILLIS)
            clearSiriRequest()
        if (!playing || source == null || phoneActive == true || siriRequestedAt != null)
            return SpeechAudioRoute.ASSISTANT
        // Pause metadata can follow SETUP. Buffer this ambiguous start instead of briefly
        // playing music through the louder voice group or routing a Siri reply as music.
        if (time - maxOf(streamStartedAt, playingSince) < SETTLE_MILLIS) return SpeechAudioRoute.WAIT
        return if (phoneActive == false) SpeechAudioRoute.MEDIA else SpeechAudioRoute.ASSISTANT
    }

    @Synchronized
    fun reset() {
        playing = false
        source = null
        playingSince = 0
        phoneActive = null
        speechActive = null
        clearSiriRequest()
    }

    private fun clearSiriRequest() {
        siriRequestedAt = null
        siriConfirmed = false
    }

    companion object {
        const val SETTLE_MILLIS = 350L
        const val SIRI_REQUEST_TIMEOUT_MILLIS = 5_000L
    }
}
