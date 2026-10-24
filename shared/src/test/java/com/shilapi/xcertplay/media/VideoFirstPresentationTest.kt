package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoFirstPresentationTest {
    @Test fun submissionAloneDoesNotConfirmPresentationAndTheCallbackDoesNotWaitForTheFallback() {
        val first = VideoFirstPresentation()
        first.submitted(123, 1_000)
        assertFalse(first.confirmed)
        assertFalse(first.useSubmittedFallback(499_001_000))
        assertTrue(first.presented(123))
        assertTrue(first.confirmed)
        assertFalse(first.presented(123))
        assertFalse(first.useSubmittedFallback(900_001_000))
    }

    @Test fun aNewBindingRejectsCallbacksFromPreviouslySubmittedPictures() {
        val old = VideoFirstPresentation()
        old.submitted(123, 0)
        val replacement = VideoFirstPresentation()
        replacement.submitted(124, 20_000)
        assertFalse(replacement.presented(123))
        assertTrue(old.presented(123))
        assertFalse(replacement.confirmed)
        assertTrue(replacement.presented(124))
    }

    @Test fun missingVendorCallbacksHaveABoundedFallbackEvenForASingleStaticPicture() {
        val first = VideoFirstPresentation()
        assertFalse(first.useSubmittedFallback(Long.MAX_VALUE))
        first.submitted(123, 0)
        assertFalse(first.useSubmittedFallback(499_999_999))
        assertTrue(first.useSubmittedFallback(500_000_000))
        assertTrue(first.confirmed)
        assertFalse(first.useSubmittedFallback(1_000_000_000))
        assertFalse(first.presented(123))
    }

    @Test fun batchedCallbacksAcceptRecentSubmittedTimestampsButRejectUnknownOnes() {
        val first = VideoFirstPresentation()
        repeat(80) { first.submitted(it.toLong(), it * 1_000L) }
        assertFalse(first.presented(100))
        assertTrue(first.presented(79))
    }
}
