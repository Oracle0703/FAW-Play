package com.shilapi.xcertplay.media

/** One instance per codec/Surface binding. Old output callbacks cannot prove a new binding. */
internal class VideoFirstPresentation {
    private val releasedPts = LinkedHashSet<Long>()
    private var firstReleaseNs: Long? = null
    @Volatile var confirmed = false
        private set

    @Synchronized fun submitted(ptsUs: Long, nowNs: Long) {
        if (confirmed) return
        if (firstReleaseNs == null) firstReleaseNs = nowNs
        releasedPts.add(ptsUs)
        // Vendor callbacks may be batched. Keep bounded evidence for recent submitted pictures.
        if (releasedPts.size > 64) releasedPts.remove(releasedPts.first())
    }

    @Synchronized fun presented(ptsUs: Long): Boolean {
        if (confirmed || ptsUs !in releasedPts) return false
        confirmed = true
        releasedPts.clear()
        return true
    }

    /** Some Android 9 codecs accept the listener but never invoke it, even for a static image. */
    @Synchronized fun useSubmittedFallback(nowNs: Long): Boolean {
        val submittedNs = firstReleaseNs ?: return false
        if (confirmed || nowNs - submittedNs < 500_000_000L) return false
        confirmed = true
        releasedPts.clear()
        return true
    }
}
