package com.shilapi.xcertplay.media

/** Per-track reserve changes only within an explicit envelope; it never undercuts the configured start. */
internal class AdaptiveAudioReserve(
    val minimumMillis: Int,
    val maximumMillis: Int,
    private val increaseMillis: Int,
    nowNs: Long = System.nanoTime(),
) {
    var targetMillis = minimumMillis
        private set
    private var lastInstabilityNs = nowNs
    private var lastReductionNs = nowNs

    init { require(minimumMillis > 0 && maximumMillis >= minimumMillis && increaseMillis > 0) }

    fun underrun(nowNs: Long): Boolean {
        lastInstabilityNs = nowNs
        lastReductionNs = nowNs
        val previous = targetMillis
        targetMillis = (targetMillis + increaseMillis).coerceAtMost(maximumMillis)
        return previous != targetMillis
    }

    fun stable(nowNs: Long): Boolean {
        if (nowNs - lastInstabilityNs < 30_000_000_000L ||
            nowNs - lastReductionNs < 10_000_000_000L || targetMillis <= minimumMillis) return false
        targetMillis = (targetMillis - 20).coerceAtLeast(minimumMillis)
        lastReductionNs = nowNs
        return true
    }

    companion object {
        fun forChannel(channel: AudioChannel, mediaMillis: Int): AdaptiveAudioReserve? = when (channel) {
            AudioChannel.MEDIA -> MediaAudioBuffer.sanitize(mediaMillis).let { AdaptiveAudioReserve(it, it + 300, 80) }
            AudioChannel.NAVIGATION -> AdaptiveAudioReserve(240, 320, 40)
            AudioChannel.PHONE, AudioChannel.ASSISTANT -> null
        }
    }
}
