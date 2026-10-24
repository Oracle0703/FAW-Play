package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    /**
     * A hardware underrun can leave a small stale value in the written/playback ledger. Treat
     * that low-water mark as drained so the renderer can pause and refill the track again.
     * The default keeps callers that only need an exact drain check source-compatible.
     */
    // Compressed packets waiting for decode are not yet playable hardware reserve. Recover
    // before writing the next burst, even if that burst has already filled the input queue.
    fun shouldRebuffer(enabled: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        rawHead: Int, floorBytes: Long = 0L): Boolean =
        enabled && playing && underrunSinceStart &&
            queuedBytes(rawHead) <= floorBytes
}
