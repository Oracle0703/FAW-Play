package com.shilapi.xcertplay.media

/** Covers the measured Wi-Fi gaps without adding the music buffer to calls. */
internal object NavigationAudioBuffer {
    private const val START_MILLIS = 160L
    private const val CAPACITY_MILLIS = 280L

    fun plan(sampleRate: Int, channels: Int, minBufferBytes: Int): MediaAudioBuffer.Plan {
        val frameBytes = channels.coerceIn(1, 2) * 2
        val bytesPerSecond = sampleRate.toLong() * frameBytes
        return MediaAudioBuffer.Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, (bytesPerSecond * CAPACITY_MILLIS / 1000).toInt()),
            startBytes = maxOf(minBufferBytes, (bytesPerSecond * START_MILLIS / 1000).toInt()),
        )
    }
}
