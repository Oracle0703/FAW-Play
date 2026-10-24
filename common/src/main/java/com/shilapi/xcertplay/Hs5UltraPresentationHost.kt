package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncTarget
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiSyncTarget
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface Hs5UltraRenderer {
    fun applyUltraState(state: HongqiHs5UiState): Boolean
    fun clearUltraState()
}

/** Connects session-owned RCS commands to the currently visible OEM display, without owning UI. */
internal object Hs5UltraPresentationHost {
    private val main = Handler(Looper.getMainLooper())
    private val profiles = AtomicLong()
    private val channels = AtomicLong()
    private var renderer = WeakReference<Hs5UltraRenderer>(null)
    private var owner: Long? = null // main thread only
    private var ownerProfile: Long? = null
    private var state: HongqiHs5UiState? = null

    fun beginProfile(): Long {
        val profile = profiles.incrementAndGet()
        dispatch {
            if (profiles.get() == profile && ownerProfile != profile) {
                renderer.get()?.clearUltraState()
                owner = null
                ownerProfile = null
                state = null
            }
        }
        return profile
    }

    fun attach(value: Hs5UltraRenderer) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (renderer.get() !== value) renderer.get()?.clearUltraState()
        renderer = WeakReference(value)
        state?.let { if (!value.applyUltraState(it)) state = null }
    }

    fun detach(value: Hs5UltraRenderer) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (renderer.get() !== value) return
        value.clearUltraState()
        renderer.clear()
    }

    fun target(profile: Long): CarPlayUiSyncTarget {
        val channel = channels.incrementAndGet()
        val activated = onMain {
            if (profiles.get() != profile) false else {
                renderer.get()?.clearUltraState()
                state = null
                owner = channel
                ownerProfile = profile
                true
            }
        }
        return HongqiHs5UiSyncTarget(onApply = { next ->
            activated && onMain {
                if (profiles.get() != profile || owner != channel) false else {
                    // Reset is meaningful even before a display exists. Layout commands require
                    // a renderer and its synchronous acceptance; queuing a Runnable is no ACK.
                    val accepted = if (next == HongqiHs5UiState()) {
                        renderer.get()?.clearUltraState()
                        true
                    } else next.layoutId != null && renderer.get()?.applyUltraState(next) == true
                    if (accepted) state = next
                    accepted
                }
            }
        }, onInactive = {
            dispatch {
                if (profiles.get() == profile && owner == channel) {
                    renderer.get()?.clearUltraState()
                    owner = null
                    ownerProfile = null
                    state = null
                }
            }
        })
    }

    private fun onMain(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val lock = Any()
        val finished = CountDownLatch(1)
        var retired = false
        var result = false
        if (!main.post {
            synchronized(lock) {
                if (!retired) result = runCatching(action).getOrDefault(false)
                retired = true
            }
            finished.countDown()
        }) return false
        try {
            finished.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // A queued task can be retired. An executing main-thread commit finishes under this
        // lock, so an already applied operation cannot be reported as a timed-out rejection.
        return synchronized(lock) {
            retired = true
            result
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
