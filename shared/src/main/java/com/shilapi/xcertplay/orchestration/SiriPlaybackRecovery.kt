package com.shilapi.xcertplay.orchestration

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayPhoneState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySpeechState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.media.SiriPlaybackResumeState
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object SiriPlaybackRecovery {
    private const val RESUME_GRACE_MILLIS = 1_200L
    private const val REQUEST_EXPIRY_MILLIS = 5_000L
    private val states = WeakHashMap<AirPlaySession, SiriPlaybackResumeState>()
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    @Synchronized
    fun onPlayback(session: AirPlaySession?, update: CarPlayNowPlaying) {
        if (session != null) state(session).playback(update)
    }

    @JvmStatic
    @Synchronized
    fun onRequested(session: AirPlaySession, playback: CarPlayNowPlaying) {
        val state = state(session)
        state.phone(AirPlayPhoneState.active(session))
        state.playback(playback)
        val ticket = state.requested() ?: return
        val expected = WeakReference(session)
        main.postDelayed({
            val peer = expected.get() ?: return@postDelayed
            synchronized(this) {
                if (states[peer] === state) state.expireRequest(ticket)
            }
        }, REQUEST_EXPIRY_MILLIS)
    }

    @JvmStatic
    @Synchronized
    fun onModesChanged(controller: CarPlayController, session: AirPlaySession, type: String) {
        if (type != "modesChanged") return
        val state = state(session)
        state.phone(AirPlayPhoneState.active(session))
        val ticket = state.speech(AirPlaySpeechState.active(session)) ?: return
        val token = controller.activeAirPlaySessionToken() ?: return
        val expectedController = WeakReference(controller)
        val expectedSession = WeakReference(session)
        session.logDebug("Audio: Siri ended; music recovery scheduled")
        main.postDelayed({
            val current = expectedController.get() ?: return@postDelayed
            val peer = expectedSession.get() ?: return@postDelayed
            synchronized(this) {
                if (current.isClosed() || current.activeAirPlaySessionToken() !== token ||
                    AirPlayPhoneState.active(peer) != false || AirPlaySpeechState.active(peer) != false ||
                    states[peer] !== state || !state.consume(ticket)) return@synchronized
                val sent = current.resumeMediaAutomatically()
                peer.logDebug("Audio: post-Siri music resume requested=$sent")
            }
        }, RESUME_GRACE_MILLIS)
    }

    @JvmStatic
    @Synchronized
    fun onMediaButton(session: AirPlaySession, index: Int) {
        if (index == CarPlayMediaButton.PAUSE || index == CarPlayMediaButton.PLAY_PAUSE) {
            states[session]?.cancel()
        }
    }

    @JvmStatic
    @Synchronized
    fun onSessionEnded(session: AirPlaySession) {
        states.remove(session)?.cancel()
    }

    private fun state(session: AirPlaySession) = states.getOrPut(session) { SiriPlaybackResumeState() }
}
