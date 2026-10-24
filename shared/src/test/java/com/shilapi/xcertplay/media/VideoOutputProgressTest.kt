package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoOutputProgressTest {
    @Test fun idleScreenWithNoUnreleasedInputDoesNotRequestRefresh() {
        val progress = VideoOutputProgress()
        assertFalse(progress.shouldProbe(10_000_000_000))
        progress.onInput(1, 1)
        progress.onOutput(1, 10)
        assertFalse(progress.shouldProbe(10_000_000_000))
    }

    @Test fun acceptedTailGetsOneProbeAndANewEpisodeCanProbeAgain() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        progress.onInput(2, 30_000_000)
        progress.onOutput(1, 40_000_000)
        assertFalse(progress.shouldProbe(539_999_999))
        assertTrue(progress.shouldProbe(540_000_000))
        progress.probeScheduled()
        assertFalse(progress.shouldProbe(2_000_000_000))
        progress.onInput(3, 2_000_000_000)
        progress.onOutput(2, 2_010_000_000)
        assertFalse(progress.shouldProbe(5_000_000_000))
        progress.beginEpisode(5_000_000_000)
        progress.onInput(4, 5_000_000_000)
        progress.onInput(5, 5_033_000_000)
        assertTrue(progress.shouldProbe(5_533_000_000))
    }

    @Test fun continuingInputOrOutputMakesForwardProgressAndResetEndsTheOldEpisode() {
        val progress = VideoOutputProgress()
        repeat(100) { progress.onInput(it.toLong(), it * 33_000_000L + 1); assertFalse(progress.shouldProbe(it * 33_000_000L + 1)) }
        progress.reset()
        assertFalse(progress.shouldProbe(10_000_000_000))
    }

    @Test fun multiFrameRefreshRepliesCannotRearmTheirOwnProbe() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        progress.onInput(2, 30_000_000)
        progress.onOutput(1, 40_000_000)
        assertTrue(progress.shouldProbe(540_000_000))
        progress.probeScheduled()

        // A forceKeyFrame response can contain several pictures, leaving another tail.
        repeat(5) { burst ->
            repeat(3) { frame ->
                val now = (1L + burst) * 1_000_000_000L + frame * 33_000_000L
                val ptsUs = 3L + burst * 3 + frame
                progress.onInput(ptsUs, now)
                progress.onOutput(ptsUs - 1, now + 1)
            }
            assertFalse(progress.shouldProbe((2L + burst) * 1_000_000_000L - 1))
        }
        assertFalse(progress.shouldProbe(10_000_000_000))
    }

    @Test fun sustainedAnimationStartsANewEpisodeAndItsNextTailCanRecover() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        progress.probeScheduled()

        // Resume real 30fps animation after the refresh, without another touch.
        repeat(17) { frame ->
            val now = 1_000_000_000L + frame * 33_000_000L
            progress.onInput(2L + frame, now)
            progress.onOutput(1L + frame, now + 1)
            assertFalse(progress.shouldProbe(now))
        }
        assertFalse(progress.shouldProbe(2_027_999_999))
        assertTrue(progress.shouldProbe(2_028_000_001))
        progress.probeScheduled()
        assertFalse(progress.shouldProbe(3_000_000_000))
    }

    @Test fun denseInputMustContinueForHalfASecondToStartANewEpisode() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        progress.probeScheduled()
        repeat(5) { frame -> progress.onInput(2L + frame, 1_000_000_000L + frame * 100_000_000L) }
        assertFalse(progress.shouldProbe(1_900_000_000))
        progress.onInput(7, 1_500_000_000)
        assertTrue(progress.shouldProbe(2_000_000_000))
    }

    @Test fun gapBetweenShortBurstsRestartsTheContinuousInputCandidate() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        progress.probeScheduled()
        repeat(5) { frame -> progress.onInput(2L + frame, 1_000_000_000L + frame * 100_000_000L) }
        // One nanosecond beyond the allowed input gap must break the first candidate.
        repeat(5) { frame -> progress.onInput(7L + frame, 1_520_000_001L + frame * 100_000_000L) }
        assertFalse(progress.shouldProbe(2_420_000_001))
        progress.onInput(12, 2_020_000_001)
        assertTrue(progress.shouldProbe(2_520_000_001))
    }

    @Test fun catchingUpWithinARefreshResponseDoesNotCreateANewEpisode() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        assertTrue(progress.shouldProbe(500_000_001))
        progress.probeScheduled()
        progress.onInput(2, 1_000_000_000)
        progress.onOutput(2, 1_010_000_000)
        progress.onInput(3, 1_033_000_000)
        assertFalse(progress.shouldProbe(2_000_000_000))
    }

    @Test fun newInteractionWaitsForProgressBeforeProbingThePendingTail() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        assertTrue(progress.shouldProbe(500_000_001))
        progress.probeScheduled()
        progress.beginEpisode(2_000_000_000)
        assertFalse(progress.shouldProbe(2_499_999_999))
        assertTrue(progress.shouldProbe(2_500_000_000))
        progress.probeScheduled()
        assertFalse(progress.shouldProbe(3_000_000_000))
    }

    @Test fun newEpisodeWithoutAPendingPictureDoesNotProbe() {
        val progress = VideoOutputProgress()
        progress.beginEpisode(1_000_000_000)
        assertFalse(progress.shouldProbe(2_000_000_000))
        progress.onInput(1, 2_000_000_000)
        progress.onOutput(1, 2_010_000_000)
        progress.beginEpisode(3_000_000_000)
        assertFalse(progress.shouldProbe(4_000_000_000))
    }

    @Test fun throttledOrPendingRequestDoesNotConsumeTheTailProbe() {
        val progress = VideoOutputProgress()
        progress.onInput(1, 1)
        assertTrue(progress.shouldProbe(500_000_001))
        assertTrue(progress.shouldProbe(1_000_000_001))
        progress.probeScheduled()
        assertFalse(progress.shouldProbe(2_000_000_001))
        progress.probeFailed()
        assertTrue(progress.shouldProbe(2_000_000_001))
    }
}
