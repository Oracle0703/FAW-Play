package com.shilapi.xcertplay.orchestration

/** Cold-start protection after TCP; reception is independent of a foreground display Surface. */
internal class WirelessSessionProgress<S : Any>(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: (Stage) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    enum class Stage(val millis: Long) { SESSION(15_000), MAIN_SCREEN(10_000), VIDEO(15_000) }
    private var startedAt: Long? = null
    private var dueAt = 0L
    private var stage = Stage.SESSION
    private var session: S? = null
    private var finished = false
    private var timerVersion = 0
    private var cancel: (() -> Unit)? = null

    @Synchronized fun accepted(atNanos: Long) {
        if (finished || startedAt != null) return
        startedAt = atNanos
        arm(Stage.SESSION, atNanos)
    }

    /** The caller admits only its current active session; replacement cannot extend the total. */
    @Synchronized fun active(candidate: S, mainReady: Boolean, videoReceived: Boolean) {
        if (finished || startedAt == null || nowNanos() >= dueAt) return
        val next = if (mainReady) Stage.VIDEO else Stage.MAIN_SCREEN
        val changed = session !== candidate
        session = candidate
        if (mainReady && videoReceived) {
            finish()
        } else if (changed || next.ordinal > stage.ordinal) arm(next)
    }

    @Synchronized fun close() = finish()

    private fun finish() {
        finished = true
        timerVersion++
        cancel?.invoke()
        cancel = null
        session = null
    }

    private fun arm(next: Stage, stageStartedAt: Long = nowNanos()) {
        stage = next
        val now = nowNanos()
        dueAt = minOf(requireNotNull(startedAt) + TOTAL_MILLIS * 1_000_000, stageStartedAt + next.millis * 1_000_000)
        cancel?.invoke()
        val version = ++timerVersion
        cancel = schedule(((dueAt - now).coerceAtLeast(0) + 999_999) / 1_000_000) { expire(version) }
    }

    private fun expire(version: Int) {
        val failure = synchronized(this) {
            if (finished || version != timerVersion) return
            if (nowNanos() < dueAt) {
                cancel = schedule((dueAt - nowNanos() + 999_999) / 1_000_000) { expire(version) }
                return
            }
            stage.also { finish() }
        }
        onTimeout(failure)
    }

    private companion object { const val TOTAL_MILLIS = 30_000L }
}
