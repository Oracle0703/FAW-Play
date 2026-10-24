package com.shilapi.xcertplay.orchestration

/** Confirms one current session only after authenticated tunnel readiness AND a rendered frame. */
internal class WirelessConnectionProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var authenticatedSession: S? = null
    private var rendered = false
    private var confirmation: (() -> Unit)? = null

    @Synchronized fun begin(generation: Int, confirmation: () -> Unit) {
        clear()
        this.generation = generation
        this.confirmation = confirmation
    }

    @Synchronized fun activate(generation: Int, session: S) {
        if (this.generation != generation || this.session === session) return
        this.session = session
        rendered = false
    }

    @Synchronized fun authenticated(generation: Int, current: S) {
        if (this.generation != generation) return
        authenticatedSession = current
        confirmIfReady()
    }

    @Synchronized fun isAuthenticated(generation: Int, current: S?): Boolean =
        this.generation == generation && current != null && authenticatedSession === current

    @Synchronized fun rendered(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        rendered = true
        confirmIfReady()
    }

    @Synchronized fun hasRenderedFrame(generation: Int): Boolean =
        this.generation == generation && session != null && rendered

    @Synchronized fun hasRenderedFrame(generation: Int, current: S?): Boolean =
        this.generation == generation && current != null && session === current && rendered

    @Synchronized fun authenticationEnded(generation: Int, current: S) {
        if (this.generation == generation && authenticatedSession === current) authenticatedSession = null
    }

    @Synchronized fun end(generation: Int, session: S) {
        if (this.generation != generation) return
        if (authenticatedSession === session) authenticatedSession = null
        if (this.session !== session) return
        this.session = null
        rendered = false
    }

    @Synchronized fun clear() {
        generation = -1
        session = null
        authenticatedSession = null
        rendered = false
        confirmation = null
    }

    private fun confirmIfReady() {
        if (session == null || authenticatedSession !== session || !rendered) return
        val callback = confirmation ?: return
        confirmation = null
        callback()
    }
}
