package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAudioRoutingStateTest {
    private var time = 0L
    private val state = SpeechAudioRoutingState { time }
    private fun update(playing: Boolean = true, source: String? = "music", phone: Boolean? = false,
        speech: Boolean? = true) = state.update(CarPlayNowPlaying(sourceApp = source, playing = playing), phone, speech)
    private fun settle() { time += SpeechAudioRoutingState.SETTLE_MILLIS }

    @Test fun qqCameraKeepsPlayingMusicOnMediaAfterMetadataSettles() {
        update()
        assertEquals(SpeechAudioRoute.WAIT, state.route(0))
        settle()
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
    }

    @Test fun delayedIphonePauseDuringHeySiriSelectsVoiceWithoutWaiting() {
        update()
        time = 100
        update(playing = false)
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
    }

    @Test fun everyNewSpeechStreamWaitsForItsOwnPauseMetadata() {
        update()
        settle()
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
        assertEquals(SpeechAudioRoute.WAIT, state.route(time))
    }

    @Test fun unknownPlayerCannotSelectMedia() {
        update(source = null)
        settle()
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
    }

    @Test fun unknownPhoneOwnershipCannotSelectMedia() {
        update(phone = null)
        assertEquals(SpeechAudioRoute.WAIT, state.route(0))
        settle()
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        update(phone = false)
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
    }

    @Test fun callOrLatePauseImmediatelyRevokesMediaRouting() {
        update(); settle()
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
        update(phone = true)
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        update(playing = false)
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
    }

    @Test fun wheelSiriRequestBlocksStalePlayingMetadataUntilSiriEnds() {
        update(speech = false); settle()
        state.siriRequested()
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        update(speech = true)
        time += 10_000
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        update(speech = false)
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
    }

    @Test fun unconfirmedSiriRequestExpiresWithoutPermanentlyBlockingCameraMusic() {
        update(speech = false); settle()
        state.siriRequested()
        time += SpeechAudioRoutingState.SIRI_REQUEST_TIMEOUT_MILLIS - 1
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        time++
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
    }

    @Test fun repeatedIdleDoesNotClearAnUnconfirmedWheelRequest() {
        update(speech = false); settle()
        state.siriRequested()
        update(speech = false)
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
    }

    @Test fun changedOrResumedPlayerMustSettleAgain() {
        update(); settle()
        update(source = "other-music")
        assertEquals(SpeechAudioRoute.WAIT, state.route(0))
        settle()
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
        update(playing = false)
        update()
        assertEquals(SpeechAudioRoute.WAIT, state.route(0))
    }

    @Test fun sessionResetDiscardsMusicAndSiriState() {
        update(); settle(); state.siriRequested(); state.reset()
        assertEquals(SpeechAudioRoute.ASSISTANT, state.route(0))
        update(); settle()
        assertEquals(SpeechAudioRoute.MEDIA, state.route(0))
    }

    @Test fun navigationTailCannotStartAfterACallOrSiriRequest() {
        update(phone = false, speech = false)
        assertFalse(state.interruptsNavigationTail())
        update(phone = true)
        assertTrue(state.interruptsNavigationTail())
        update(phone = false, speech = false)
        state.siriRequested()
        assertTrue(state.interruptsNavigationTail())
        time += SpeechAudioRoutingState.SIRI_REQUEST_TIMEOUT_MILLIS
        assertFalse(state.interruptsNavigationTail())
        state.siriRequested()
        update(speech = true)
        time += 10_000
        assertTrue(state.interruptsNavigationTail())
        update(speech = false)
        assertFalse(state.interruptsNavigationTail())
        state.reset()
        assertFalse(state.interruptsNavigationTail())
    }
}
