package com.shilapi.xcertplay.orchestration

/**
 * One bounded recovery on an already-owned network. HTTP/TCP are progress, never connection proof.
 * The owner still performs pair-verify and runtime iAP2 authentication for each new session.
 */
internal class WirelessFastReconnect<S : Any>(private val nowNanos: () -> Long = System::nanoTime) {
    enum class Stage(val budgetMillis: Long) {
        DISCOVERY(3_000), CONTROL_ACCEPTED(6_000), TCP_ACCEPTED(12_000), SESSION_ACTIVE(12_000),
    }
    data class Attempt(val id: Int, val controllerId: String)
    private var nextId = 0
    private var current: Attempt? = null
    private var startedAt = 0L
    private var dueAt = 0L
    private var stage = Stage.DISCOVERY
    private var session: S? = null
    private var authenticated = false
    private var rendered = false

    @Synchronized fun begin(controllerId: String): Attempt {
        require(controllerId.isNotBlank())
        cancel()
        return Attempt(++nextId, controllerId).also {
            current = it
            startedAt = nowNanos()
            dueAt = startedAt + Stage.DISCOVERY.budgetMillis * 1_000_000
        }
    }

    @Synchronized fun attempt(): Attempt? = current
    @Synchronized fun stage(): Stage = stage
    @Synchronized fun isCurrent(attempt: Attempt): Boolean = current === attempt
    @Synchronized fun allows(controllerId: String?): Boolean =
        current?.controllerId?.let { it == controllerId && nowNanos() < dueAt } ?: true

    @Synchronized fun progress(attempt: Attempt, next: Stage): Boolean {
        if (!timely(attempt) || next.ordinal <= stage.ordinal) return false
        stage = next
        dueAt = minOf(startedAt + MAX_MILLIS * 1_000_000, nowNanos() + next.budgetMillis * 1_000_000)
        return true
    }

    @Synchronized fun activate(attempt: Attempt, candidate: S, controllerId: String?): Boolean {
        if (!timely(attempt) || attempt.controllerId != controllerId) return false
        if (session !== candidate) {
            session = candidate
            authenticated = false
            rendered = false
        }
        progress(attempt, Stage.SESSION_ACTIVE)
        return true
    }

    @Synchronized fun authenticated(attempt: Attempt, candidate: S): Boolean {
        if (!timely(attempt) || session !== candidate) return false
        authenticated = true
        return completeIfReady()
    }

    @Synchronized fun rendered(attempt: Attempt, candidate: S): Boolean {
        if (!timely(attempt) || session !== candidate) return false
        rendered = true
        return completeIfReady()
    }

    @Synchronized fun remainingMillis(attempt: Attempt): Long? =
        if (current !== attempt) null else ((dueAt - nowNanos() + 999_999) / 1_000_000).coerceAtLeast(0)

    /** Exactly one caller may consume failure and initiate Bluetooth fallback. */
    @Synchronized fun fail(attempt: Attempt): Boolean {
        if (current !== attempt) return false
        cancel()
        return true
    }

    @Synchronized fun cancel() {
        current = null
        session = null
        authenticated = false
        rendered = false
        stage = Stage.DISCOVERY
    }

    private fun completeIfReady(): Boolean {
        if (!authenticated || !rendered) return false
        cancel()
        return true
    }

    private fun timely(attempt: Attempt): Boolean = current === attempt && nowNanos() < dueAt

    private companion object { const val MAX_MILLIS = 20_000L }
}
