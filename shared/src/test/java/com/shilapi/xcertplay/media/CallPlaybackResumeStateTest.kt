package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CallPlaybackResumeStateTest {
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
