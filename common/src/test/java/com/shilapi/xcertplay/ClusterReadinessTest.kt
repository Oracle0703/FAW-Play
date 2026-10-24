package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.ClusterContentProgress
import org.junit.Assert.*
import org.junit.Test

class ClusterReadinessTest {
    private val pending = ClusterContentProgress(7, 2, true, true, false, false, 1)

    @Test fun lateWriterSnapshotsCannotEraseDecoderOrRendererFacts() {
        val state = CarPlayBackgroundSession.ChannelReadiness(mainFrameRendered = true)
            .withCluster(pending.copy(validVideoReceived = true, frameRendered = true, attempts = 2))
            .withCluster(pending)
        assertTrue(state.mainFrameRendered)
        assertTrue(state.cluster!!.requestWritten)
        assertTrue(state.cluster!!.validVideoReceived)
        assertTrue(state.cluster!!.frameRendered)
        assertEquals(2, state.cluster!!.attempts)
    }

    @Test fun lateTimeoutCannotRestoreFailureAfterValidContent() {
        val timeout = pending.copy(failure = "no-valid-video", attempts = 3)
        val firstTimeout = CarPlayBackgroundSession.ChannelReadiness().withCluster(timeout)
        assertEquals("no-valid-video", firstTimeout.withCluster(pending).cluster!!.failure)
        val recovered = firstTimeout.withCluster(pending.copy(validVideoReceived = true))
        assertNull(recovered.cluster!!.failure)
        assertNull(recovered.withCluster(timeout).cluster!!.failure)
    }

    @Test fun aNewHideRevisionClearsPreviousRequestFactsAndRejectsLateVideo() {
        val visible = pending.copy(validVideoReceived = true, frameRendered = true)
        val hidden = pending.copy(revision = 3, shown = false, requestWritten = false, attempts = 0)
        val state = CarPlayBackgroundSession.ChannelReadiness().withCluster(visible)
            .withCluster(hidden).withCluster(visible)
        assertEquals(hidden, state.cluster)
    }

    @Test fun missingSnapshotsAndMismatchedStreamCannotReplaceCurrentRevision() {
        val state = CarPlayBackgroundSession.ChannelReadiness().withCluster(pending)
        assertSame(state, state.withCluster(null))
        assertSame(state, state.withCluster(pending.copy(stream = 9, frameRendered = true)))
    }
}
