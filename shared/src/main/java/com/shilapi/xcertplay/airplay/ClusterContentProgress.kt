package com.shilapi.xcertplay.airplay

/** Receiver-side facts only. Even a rendered frame does not identify the pixels or OEM visibility. */
data class ClusterContentProgress(
    val stream: Int,
    val revision: Long,
    val shown: Boolean,
    val requestWritten: Boolean,
    val validVideoReceived: Boolean,
    val frameRendered: Boolean,
    val attempts: Int,
    val failure: String? = null,
)

/** A finite first-output check, not a frame-rate watchdog for an already working static map. */
internal class ClusterContentMonitor {
    enum class Action { NONE, RETRY, REPORT }
    private var current: ClusterContentProgress? = null
    private var nextRevision = 0L
    private var source: Any? = null
    private var firstWriteNs: Long? = null
    private var lastWriteNs: Long? = null

    @Synchronized fun begin(stream: Int, source: Any?, shown: Boolean): ClusterContentProgress {
        this.source = source
        firstWriteNs = null
        lastWriteNs = null
        return ClusterContentProgress(stream, ++nextRevision, shown, false, false, false, 0)
            .also { current = it }
    }

    @Synchronized fun request(stream: Int, shown: Boolean): ClusterContentProgress? {
        val before = current?.takeIf { it.stream == stream } ?: return null
        firstWriteNs = null
        lastWriteNs = null
        return before.copy(revision = ++nextRevision, shown = shown, requestWritten = false,
            validVideoReceived = false, frameRendered = false, attempts = if (shown) 1 else 0,
            failure = null).also { current = it }
    }

    @Synchronized fun written(stream: Int, revision: Long, nowNs: Long): ClusterContentProgress? {
        val before = matching(stream, revision) ?: return null
        if (firstWriteNs == null) firstWriteNs = nowNs
        lastWriteNs = nowNs
        return before.copy(requestWritten = true).also { current = it }
    }

    @Synchronized fun received(source: Any): ClusterContentProgress? {
        val before = current ?: return null
        if (this.source !== source || !before.shown || !before.requestWritten || before.validVideoReceived) return null
        return before.copy(validVideoReceived = true, failure = null).also { current = it }
    }

    @Synchronized fun rendered(source: Any): ClusterContentProgress? {
        val before = current ?: return null
        if (this.source !== source || !before.shown || !before.requestWritten || before.frameRendered) return null
        return before.copy(frameRendered = true, failure = null).also { current = it }
    }

    @Synchronized fun check(stream: Int, revision: Long, nowNs: Long): Action {
        val before = matching(stream, revision) ?: return Action.NONE
        if (!before.shown || !before.requestWritten || before.validVideoReceived || before.frameRendered || before.failure != null) return Action.NONE
        val first = firstWriteNs ?: return Action.NONE
        val age = nowNs - first
        if (age >= TOTAL_WAIT_NS) {
            current = before.copy(failure = "no-valid-video")
            return Action.REPORT
        }
        val last = lastWriteNs ?: return Action.NONE
        if (!before.validVideoReceived && before.attempts < MAX_ATTEMPTS && nowNs - last >= RETRY_WAIT_NS) {
            current = before.copy(attempts = before.attempts + 1)
            return Action.RETRY
        }
        return Action.NONE
    }

    @Synchronized fun snapshot(): ClusterContentProgress? = current
    @Synchronized fun clear() { current = null; source = null; firstWriteNs = null; lastWriteNs = null }

    private fun matching(stream: Int, revision: Long): ClusterContentProgress? =
        current?.takeIf { it.stream == stream && it.revision == revision }

    companion object {
        const val RETRY_WAIT_NS = 5_000_000_000L
        const val TOTAL_WAIT_NS = 15_000_000_000L
        const val MAX_ATTEMPTS = 3
    }
}
