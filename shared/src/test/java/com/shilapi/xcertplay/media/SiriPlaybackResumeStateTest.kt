package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class SiriPlaybackResumeStateTest {
    private fun update(playing: Boolean, source: String? = "music") =
        CarPlayNowPlaying(sourceApp = source, playing = playing)

    private fun state(playing: Boolean = true) = SiriPlaybackResumeState().apply {
        phone(false)
        speech(false)
        playback(update(playing))
    }

    private fun interrupt(state: SiriPlaybackResumeState) {
        state.requested()
        state.speech(true)
        state.playback(update(false))
    }

    @Test fun resumesTheInterruptedPlayerOnceAfterSiriEnds() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        assertTrue(state.consume(ticket))
        assertFalse(state.consume(ticket))
        assertNull(state.speech(false))
    }

    @Test fun musicPausedBeforeSiriStaysPaused() {
        val state = state(false)
        interrupt(state)
        assertNull(state.speech(false))
    }

    @Test fun capturesPlaybackBeforeDelayedSpeechActivation() {
        val state = state()
        state.requested()
        state.playback(update(false))
        state.requested()
        assertNull(state.speech(false))
        assertNull(state.speech(false))
        state.speech(true)
        state.requested() // The second voice press closes Siri, preserving the original snapshot.
        val ticket = state.speech(false)!!
        assertTrue(state.consume(ticket))
    }

    @Test fun idleCanArriveBeforePausedMetadata() {
        val state = state()
        state.requested()
        state.speech(true)
        val ticket = state.speech(false)!!
        state.playback(update(false))
        assertTrue(state.consume(ticket))
    }

    @Test fun spontaneousPlaybackCancelsPendingResume() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        state.playback(update(true))
        state.playback(update(false))
        assertFalse(state.consume(ticket))
    }

    @Test fun changingPlayerDuringOrAfterSiriCancelsResume() {
        val during = state()
        interrupt(during)
        during.playback(update(false, "video"))
        during.playback(update(false))
        assertNull(during.speech(false))
        val after = state()
        interrupt(after)
        val ticket = after.speech(false)!!
        after.playback(update(false, "video"))
        after.playback(update(false))
        assertFalse(after.consume(ticket))
    }

    @Test fun unknownPlayerDoesNotAuthorizeResume() {
        val state = state()
        state.playback(update(true, null))
        state.requested()
        state.speech(true)
        state.playback(update(false, null))
        assertNull(state.speech(false))
    }

    @Test fun callOverlapCancelsSiriRecoveryEvenAfterCallEnds() {
        val state = state()
        interrupt(state)
        state.phone(true)
        state.phone(false)
        assertNull(state.speech(false))
    }

    @Test fun callStartedDuringGracePeriodCancelsPendingResume() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        state.phone(true)
        state.phone(false)
        assertFalse(state.consume(ticket))
    }

    @Test fun unknownPhoneOrSpeechStateCannotResumeMusic() {
        val state = state()
        interrupt(state)
        state.phone(null)
        state.phone(false)
        assertNull(state.speech(false))
        val other = state()
        interrupt(other)
        val ticket = other.speech(false)!!
        other.speech(null)
        other.speech(false)
        assertFalse(other.consume(ticket))
    }

    @Test fun newSiriRequestInvalidatesPreviousRecovery() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        state.requested()
        assertFalse(state.consume(ticket))
        state.speech(true)
        assertNull(state.speech(false))
    }

    @Test fun newSpeechActivationInvalidatesPreviousRecovery() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        state.speech(true)
        assertFalse(state.consume(ticket))
        assertNull(state.speech(false))
    }

    @Test fun expiredUnconfirmedRequestCannotResumeAnUnrelatedInterruption() {
        val state = state()
        val request = state.requested()!!
        state.playback(update(false))
        state.expireRequest(request)
        state.speech(true)
        assertNull(state.speech(false))
    }

    @Test fun requestExpiryDoesNotCancelConfirmedSiriOrANewerRequest() {
        val state = state()
        val first = state.requested()!!
        val latest = state.requested()!!
        state.expireRequest(first)
        state.playback(update(false))
        state.speech(true)
        state.expireRequest(latest)
        val ticket = state.speech(false)!!
        assertTrue(state.consume(ticket))
    }

    @Test fun explicitPauseOrSessionCancellationInvalidatesRecovery() {
        val state = state()
        interrupt(state)
        val ticket = state.speech(false)!!
        state.cancel()
        assertFalse(state.consume(ticket))
    }

    @Test fun speechWithoutAWheelRequestCanRecoverKnownPlayingMusic() {
        val state = state()
        state.speech(true)
        state.playback(update(false))
        val ticket = state.speech(false)!!
        assertTrue(state.consume(ticket))
    }

    @Test fun initialAndRepeatedIdleDoNotResumeOrdinaryPausedMusic() {
        val state = state()
        state.playback(update(false))
        assertNull(state.speech(false))
        assertNull(state.speech(false))
    }
}
