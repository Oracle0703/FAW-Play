package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CallPlaybackResumeStateTest {
    @Test fun explicitPauseDuringOrAfterACallCancelsAutomaticPlay() {
        for (during in listOf(true, false)) {
            val state = CallPlaybackResumeState()
            state.playback(CarPlayNowPlaying(playing = true))
            state.phone(true); state.playback(CarPlayNowPlaying(playing = false))
            if (during) { state.cancel(); assertNull(state.phone(false)) }
            else { val ticket = state.phone(false)!!; state.cancel(); assertFalse(state.consume(ticket)) }
        }
    }
    private fun update(playing: Boolean, source: String = "music") =
        CarPlayNowPlaying(sourceApp = source, playing = playing)

    @Test fun resumesMusicInterruptedByCallOnce() {
        val state = CallPlaybackResumeState()
        state.playback(update(true))
        assertNull(state.phone(true))
        state.playback(update(false))
        val ticket = state.phone(false)!!
        assertTrue(state.consume(ticket))
        assertFalse(state.consume(ticket))
        assertNull(state.phone(false))
    }

    @Test fun pausedBeforeCallStaysPaused() {
        val state = CallPlaybackResumeState()
        state.playback(update(false))
        state.phone(true)
        state.playback(update(false))
        assertNull(state.phone(false))
    }

    @Test fun iphoneOwnResumeCancelsPendingPlay() {
        val state = CallPlaybackResumeState()
        state.playback(update(true))
        state.phone(true)
        state.playback(update(false))
        val ticket = state.phone(false)!!
        state.playback(update(true))
        assertFalse(state.consume(ticket))
    }

    @Test fun anotherCallInvalidatesThePreviousTicket() {
        val state = CallPlaybackResumeState()
        state.playback(update(true))
        state.phone(true)
        state.playback(update(false))
        val ticket = state.phone(false)!!
        state.phone(true)
        assertFalse(state.consume(ticket))
        assertNull(state.phone(false))
    }

    @Test fun changingPlayerDuringCallCancelsResume() {
        val state = CallPlaybackResumeState()
        state.playback(update(true))
        state.phone(true)
        state.playback(update(false, "other"))
        assertNull(state.phone(false))
    }

    @Test fun changingPlayerAfterHangupCancelsResume() {
        val state = CallPlaybackResumeState()
        state.playback(update(true))
        state.phone(true)
        state.playback(update(false))
        val ticket = state.phone(false)!!
        state.playback(update(false, "other"))
        assertFalse(state.consume(ticket))
    }

    @Test fun initialIdleUnknownAndRepeatedActiveDoNotTriggerResume() {
        val state = CallPlaybackResumeState()
        assertNull(state.phone(null))
        assertNull(state.phone(false))
        state.playback(update(true))
        assertNull(state.phone(true))
        state.playback(update(false))
        assertNull(state.phone(true))
        val ticket = state.phone(false)!!
        assertTrue(state.consume(ticket))
    }
}
