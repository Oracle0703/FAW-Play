package com.shilapi.xcertplay.orchestration

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayPhoneState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CallPlaybackResumeState
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Session-scoped recovery for music paused by a telephone call. */
internal object CallPlaybackRecovery {
    private val states = WeakHashMap<AirPlaySession, CallPlaybackResumeState>()
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    @Synchronized
    fun onPlayback(session: AirPlaySession?, update: CarPlayNowPlaying) {
        if (session != null) states.getOrPut(session) { CallPlaybackResumeState() }.playback(update)
    }

    @JvmStatic
    @Synchronized
    fun onPhoneState(controller: CarPlayController, session: AirPlaySession, type: String) {
        if (type != "modesChanged") return
        val state = states.getOrPut(session) { CallPlaybackResumeState() }
        val ticket = state.phone(AirPlayPhoneState.active(session)) ?: return
        val token = controller.activeAirPlaySessionToken() ?: return
        val expectedController = WeakReference(controller)
        val expectedSession = WeakReference(session)
        main.postDelayed({
            val current = expectedController.get() ?: return@postDelayed
            val peer = expectedSession.get() ?: return@postDelayed
            synchronized(this) {
                if (current.isClosed() || current.activeAirPlaySessionToken() !== token ||
                    AirPlayPhoneState.active(peer) != false || states[peer] !== state ||
                    !state.consume(ticket)) return@synchronized
                val sent = current.sendMediaButton(CarPlayMediaButton.PLAY)
                peer.logDebug("Audio: post-call music resume requested=$sent")
            }
        }, 1_200L)
    }
}
