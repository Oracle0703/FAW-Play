// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.app.Presentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

/** Own one instance per Ultra session; closing it restores the existing classic map policy. */
class HongqiUltraDisplayLease(
    context: Context,
    presentation: Presentation,
    private val onDecision: (HongqiUltraDisplayDecision) -> Unit = {},
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val arbiter = HongqiUltraDisplayArbiter()
    private val vehicleLease: Closeable
    private val output: HongqiNavigation.UltraPresentationLease
    private var sessionActive = false
    private var rendererReady = false
    private var cameraActive: Boolean? = null
    private var decision: HongqiUltraDisplayDecision? = null
    @Volatile private var closed = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Display lease must be created on main" }
        vehicleLease = HongqiVehicleData.acquire(context)
        output = try { HongqiNavigation.acquireUltraPresentation(context, presentation) {
            if (!closed) {
                arbiter.interrupted(SystemClock.elapsedRealtime())
                refresh()
            }
        } } catch (failure: RuntimeException) { vehicleLease.close(); throw failure }
    }

    /** This must reflect the actual UI Sync / local renderer lifetime, not just TCP connected. */
    fun updateSession(active: Boolean, ready: Boolean) = onMain {
        sessionActive = active
        rendererReady = ready
        refresh()
    }

    /** An optional, real camera observer may veto display; no fixed-return getter is used. */
    fun updateCamera(active: Boolean?) = onMain {
        cameraActive = active
        refresh()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            refresh()
            main.postDelayed(this, POLL_MILLIS)
        }
    }

    init { main.post(poll) }

    private fun refresh() {
        if (closed) return
        val next = arbiter.evaluate(sessionActive, rendererReady,
            HongqiVehicleData.snapshot(), HongqiUltraDisplaySafety(
                output.isFactoryAvailable, output.isPreempted, cameraActive), SystemClock.elapsedRealtime())
        output.setRequested(next.requested)
        if (next != decision) {
            decision = next
            onDecision(next)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed) action() }
        else main.post { if (!closed) action() }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        arbiter.close()
        output.close()
        vehicleLease.close()
    }

    private companion object { const val POLL_MILLIS = 250L }
}
