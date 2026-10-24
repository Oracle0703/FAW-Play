package com.shilapi.xcertplay.media

import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** Includes retiring decoders until native release completes, across host and mirror views. */
object VideoSurfaceHandoff {
    internal interface Owner {
        fun beginRelease(surface: Surface): Boolean
        fun awaitRelease(deadlineNs: Long): Boolean
    }

    private val owners = CopyOnWriteArraySet<Owner>()
    internal fun register(owner: Owner) { owners.add(owner) }
    internal fun unregister(owner: Owner) { owners.remove(owner) }

    /** Revoke every producer first, then wait against one deadline before the holder destroys it. */
    fun releaseSurface(surface: Surface, timeoutMillis: Long = 2_500): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val releasing = owners.filter { it.beginRelease(surface) }
        var confirmed = true
        var interrupted = false
        for (owner in releasing) {
            try { if (!owner.awaitRelease(deadline)) confirmed = false }
            catch (_: InterruptedException) { interrupted = true; confirmed = false }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return confirmed
    }
}
